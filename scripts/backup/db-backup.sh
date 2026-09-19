#!/usr/bin/env bash
#
# DB 백업 — 논리 덤프 한 개를 만들고, 읽히는지 확인하고, 오래된 것을 지운다.
# (docs/OPERATIONS-BACKUP.md, PRODUCTION-READINESS.md D1)
#
# [실행]
#   ./scripts/backup/db-backup.sh                 # backups/db/dockindb-YYYYmmddTHHMMSS.dump
#   S3_BACKUP_URI=s3://bucket/db ./scripts/backup/db-backup.sh    # + 오프사이트 복사
#
# [무엇을 만드나]
#   pg_dump -Fc (custom 포맷). 압축돼 있고, pg_restore로 테이블 단위 선택 복구가 되며,
#   --list로 내용을 열어볼 수 있다. plain SQL보다 이쪽을 고른 이유는 마지막 것 -- 덤프가
#   "만들어졌다"와 "읽힌다"는 다른 사실이고, 이 스크립트는 둘 다 확인한 뒤에야 성공이라 한다.
#
# [왜 컨테이너 안에서 pg_dump를 부르나]
#   호스트에 pg_dump가 없어도 되고, 서버와 같은 메이저 버전(17)이 보장된다.
#   pg_dump는 서버보다 낮은 버전이면 거부하므로 호스트 것을 쓰면 그 문제가 생긴다.
#
# [실패하면 0이 아닌 코드로 끝난다]
#   cron이 그 코드를 보고 알린다(docs 참고). 실패한 덤프는 .tmp인 채로 남겨 두지 않고 지운다 --
#   반쪽짜리 파일이 "최신 백업"으로 보이는 것이 백업이 없는 것보다 나쁘다.
#
# [.env를 읽는다]
#   compose가 쓰는 .env에서 DB_USERNAME을 가져온다. 비밀번호는 필요 없다 --
#   컨테이너 안의 로컬 소켓 접속은 trust다.
#
set -euo pipefail

cd "$(dirname "$0")/../.."

if [[ -f .env ]]; then
    # shellcheck disable=SC1091
    set -a; source .env; set +a
fi

DB_CONTAINER=${DB_CONTAINER:-dockin-db}
DB_USER=${DB_USER:-${DB_USERNAME:-root}}
DB_NAME=${DB_NAME:-dockindb}
BACKUP_DIR=${BACKUP_DIR:-backups/db}
KEEP=${KEEP:-14}                 # 남길 덤프 수. 하루 하나면 2주.
S3_BACKUP_URI=${S3_BACKUP_URI:-} # 비어 있으면 오프사이트 복사를 하지 않는다.

STAMP=$(date +%Y%m%dT%H%M%S)
FILE="$BACKUP_DIR/$DB_NAME-$STAMP.dump"
LOG="$BACKUP_DIR/backup.log"

mkdir -p "$BACKUP_DIR"

log() { printf '%s %s\n' "$(date +%FT%T)" "$*" | tee -a "$LOG" >&2; }
fail() { log "FAIL $*"; rm -f "$FILE.tmp"; exit 1; }

docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null | grep -q true \
    || fail "DB 컨테이너가 돌고 있지 않다: $DB_CONTAINER"

# ---- 1. 덤프 -------------------------------------------------------------------
t0=$(date +%s)
docker exec "$DB_CONTAINER" pg_dump -U "$DB_USER" -d "$DB_NAME" -Fc --no-owner --no-privileges \
    > "$FILE.tmp" || fail "pg_dump 실패"
t1=$(date +%s)

BYTES=$(stat -c %s "$FILE.tmp" 2>/dev/null || stat -f %z "$FILE.tmp")
[[ "$BYTES" -gt 0 ]] || fail "덤프가 비어 있다"

# ---- 2. 읽히는가 -- 카탈로그를 열어 항목 수를 센다 -----------------------------------
# 만들어진 파일이 깨졌으면 여기서 걸린다. 테이블 데이터 항목이 0이면 스키마만 있는 것이라 실패다.
ENTRIES=$(docker exec -i "$DB_CONTAINER" pg_restore --list < "$FILE.tmp" | grep -c '^[0-9]' || true)
TABLE_DATA=$(docker exec -i "$DB_CONTAINER" pg_restore --list < "$FILE.tmp" | grep -c 'TABLE DATA' || true)
[[ "$ENTRIES" -gt 0 && "$TABLE_DATA" -gt 0 ]] || fail "덤프를 읽을 수 없거나 데이터가 없다 (entries=$ENTRIES, table_data=$TABLE_DATA)"

mv "$FILE.tmp" "$FILE"
sha256sum "$FILE" | awk '{print $1}' > "$FILE.sha256"

# ---- 3. 오프사이트 ----------------------------------------------------------------
# 같은 디스크에만 있는 백업은 디스크가 죽으면 같이 죽는다. URI가 있으면 S3로 보낸다.
if [[ -n "$S3_BACKUP_URI" ]]; then
    aws s3 cp "$FILE" "$S3_BACKUP_URI/" --only-show-errors --sse AES256 \
        && aws s3 cp "$FILE.sha256" "$S3_BACKUP_URI/" --only-show-errors --sse AES256 \
        || fail "S3 복사 실패: $S3_BACKUP_URI"
fi

# ---- 4. 회전 ---------------------------------------------------------------------
# 최신 KEEP개만 남긴다. S3 쪽 수명은 버킷 수명주기 규칙이 맡는다(docs 참고).
ls -1t "$BACKUP_DIR"/"$DB_NAME"-*.dump 2>/dev/null | tail -n +"$((KEEP + 1))" | while read -r old; do
    rm -f "$old" "$old.sha256"
    log "회전: 삭제 $old"
done

log "OK $FILE bytes=$BYTES entries=$ENTRIES table_data=$TABLE_DATA dump_sec=$((t1 - t0)) s3=${S3_BACKUP_URI:-없음}"
