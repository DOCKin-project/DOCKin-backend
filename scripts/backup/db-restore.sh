#!/usr/bin/env bash
#
# DB 복구 — 덤프를 새 데이터베이스에 풀고, 원본과 대조하고, 원하면 자리를 바꾼다.
# (docs/OPERATIONS-BACKUP.md, PRODUCTION-READINESS.md D1)
#
# [실행]
#   ./scripts/backup/db-restore.sh backups/db/dockindb-20260914T020000.dump
#       → dockindb_restore_<ts>에 풀고 테이블별 행 수를 현재 dockindb와 비교한 뒤 지운다. (리허설)
#   ./scripts/backup/db-restore.sh <dump> --keep
#       → 대조까지 하고 복구본을 남긴다. 직접 들여다볼 때.
#   ./scripts/backup/db-restore.sh <dump> --swap
#       → 대조 뒤 현재 dockindb를 dockindb_old_<ts>로 밀어내고 복구본을 dockindb로 이름 바꾼다. (실제 복구)
#         앱이 떠 있으면 거부한다. 앱을 먼저 내린다: docker compose stop dockin-app
#
# [왜 제자리에 덮어쓰지 않고 옆에 풀어서 바꿔치기하나]
#   제자리 복구는 "DROP → 풀기" 사이에 DB가 없는 시간이 생기고, 풀다가 실패하면 원본도 복구본도
#   없는 상태가 된다. 옆에 풀면 실패해도 원본은 그대로고, 성공하면 RENAME 두 번(수 ms)으로 바뀐다.
#   옛 DB는 지우지 않고 _old_<ts>로 남긴다 -- 복구가 잘못됐을 때 되돌릴 유일한 길이다.
#
# [대조가 곧 검증이다]
#   pg_restore가 0으로 끝났다는 것과 데이터가 다 들어왔다는 것은 다르다. 테이블마다 행 수를
#   원본과 나란히 찍는다. 리허설에서는 같아야 하고, 실제 복구에서는 다른 것이 정상이다
#   (덤프 이후 쌓인 만큼) -- 그 차이가 잃은 양이다. 그것을 숫자로 보고 결정한다.
#
set -euo pipefail

cd "$(dirname "$0")/../.."

if [[ -f .env ]]; then
    # shellcheck disable=SC1091
    set -a; source .env; set +a
fi

DB_CONTAINER=${DB_CONTAINER:-dockin-db}
APP_CONTAINER=${APP_CONTAINER:-dockin-app}
DB_USER=${DB_USER:-${DB_USERNAME:-root}}
DB_NAME=${DB_NAME:-dockindb}

DUMP=${1:-}
MODE=rehearsal
for arg in "${@:2}"; do
    case "$arg" in
        --keep) MODE=keep ;;
        --swap) MODE=swap ;;
        *) echo "모르는 옵션: $arg" >&2; exit 2 ;;
    esac
done
[[ -n "$DUMP" && -f "$DUMP" ]] || { echo "usage: $0 <dump> [--keep|--swap]" >&2; exit 2; }

STAMP=$(date +%Y%m%dT%H%M%S)
RESTORE_DB="${DB_NAME}_restore_$STAMP"

psql_admin() { docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d postgres -v ON_ERROR_STOP=1 -qAt "$@"; }
psql_in()    { docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$1" -v ON_ERROR_STOP=1 -qAt; }

docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null | grep -q true \
    || { echo "DB 컨테이너가 돌고 있지 않다: $DB_CONTAINER" >&2; exit 1; }

if [[ -f "$DUMP.sha256" ]]; then
    echo "$(cat "$DUMP.sha256")  $DUMP" | sha256sum -c --quiet || { echo "체크섬 불일치: $DUMP" >&2; exit 1; }
fi

if [[ "$MODE" == swap ]] && docker inspect -f '{{.State.Running}}' "$APP_CONTAINER" 2>/dev/null | grep -q true; then
    echo "앱이 떠 있다. 먼저 내린다: docker compose stop $APP_CONTAINER" >&2
    exit 1
fi

# ---- 1. 옆에 풀기 ------------------------------------------------------------------
echo "복구 대상: $RESTORE_DB  (덤프 $DUMP, $(stat -c %s "$DUMP" 2>/dev/null || stat -f %z "$DUMP") bytes)"
psql_admin -c "CREATE DATABASE \"$RESTORE_DB\""
t0=$(date +%s)
# --no-owner/--no-privileges: 덤프도 그렇게 떴다. --exit-on-error: 조용히 반쯤 들어온 DB를 만들지 않는다.
docker exec -i "$DB_CONTAINER" pg_restore -U "$DB_USER" -d "$RESTORE_DB" --no-owner --no-privileges --exit-on-error \
    < "$DUMP" || { echo "pg_restore 실패 -- $RESTORE_DB를 지운다" >&2; psql_admin -c "DROP DATABASE \"$RESTORE_DB\""; exit 1; }
t1=$(date +%s)
echo "pg_restore 완료: $((t1 - t0))초"

# ---- 2. 대조 -----------------------------------------------------------------------
COUNT_SQL="SELECT relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE n.nspname = 'public' AND c.relkind = 'r' ORDER BY 1"
tables=$(psql_in "$RESTORE_DB" <<< "$COUNT_SQL")
has_source=$(psql_admin -c "SELECT 1 FROM pg_database WHERE datname = '$DB_NAME'" || true)

printf '\n%-32s %14s %14s %s\n' "테이블" "복구본" "현재 DB" "차이"
mismatch=0
for t in $tables; do
    r=$(psql_in "$RESTORE_DB" <<< "SELECT count(*) FROM \"$t\"")
    if [[ "$has_source" == 1 ]]; then
        s=$(psql_in "$DB_NAME" <<< "SELECT count(*) FROM \"$t\"" 2>/dev/null || echo "-")
    else
        s="-"
    fi
    d="" ; [[ "$s" != "-" && "$s" != "$r" ]] && { d="≠"; mismatch=$((mismatch + 1)); }
    printf '%-32s %14s %14s %s\n' "$t" "$r" "$s" "$d"
done
flyway=$(psql_in "$RESTORE_DB" <<< "SELECT coalesce(max(version::text), '없음') FROM flyway_schema_history" 2>/dev/null || echo "없음")
echo
echo "flyway 버전(복구본): $flyway   행 수가 다른 테이블: $mismatch   복구 소요: $((t1 - t0))초"

# ---- 3. 마무리 ---------------------------------------------------------------------
case "$MODE" in
    rehearsal)
        psql_admin -c "DROP DATABASE \"$RESTORE_DB\""
        echo "리허설 끝. 복구본을 지웠다."
        ;;
    keep)
        echo "복구본을 남겼다: $RESTORE_DB   (지우려면: DROP DATABASE \"$RESTORE_DB\")"
        ;;
    swap)
        OLD_DB="${DB_NAME}_old_$STAMP"
        # 접속을 끊어야 RENAME이 된다. 앱은 위에서 내렸고, 남은 것은 셸 세션 정도다.
        psql_admin -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$DB_NAME' AND pid <> pg_backend_pid()" >/dev/null
        psql_admin -c "ALTER DATABASE \"$DB_NAME\" RENAME TO \"$OLD_DB\""
        psql_admin -c "ALTER DATABASE \"$RESTORE_DB\" RENAME TO \"$DB_NAME\""
        echo "바꿔치기 완료. 옛 DB는 $OLD_DB 로 남아 있다. 앱을 올린다: docker compose start $APP_CONTAINER"
        echo "되돌리려면: 앱 내리고 RENAME을 반대로."
        ;;
esac
