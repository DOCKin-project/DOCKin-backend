#!/usr/bin/env bash
#
# 슬로우 쿼리 + 대기 이벤트 보고서를 파일로 남긴다. 주 1회. (docs/OPERATIONS-SLOW-QUERY.md, PRODUCTION-READINESS O4)
#
# [실행]
#   ./scripts/db/slow-query-report.sh                      # measure/slow-query/report-YYYYmmddTHHMMSS.txt
#   OUT_DIR=measure/e8/raw ./scripts/db/slow-query-report.sh   # 측정 산출물 옆에
#
# [무엇을 남기나]
#   scripts/db/slow-query-report.sql의 여섯 표(누적/평균/호출 top 10, 지금 기다리는 세션, 오래 열린 트랜잭션, 락 설정)
#   + 서버 로그에서 "still waiting for" 줄 수 (log_lock_waits=on이 남긴 것). 둘을 한 파일에.
#
# [.env를 읽는다] backup/db-backup.sh와 같다.
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
OUT_DIR=${OUT_DIR:-measure/slow-query}
LOG_SINCE=${LOG_SINCE:-168h}     # 서버 로그를 얼마나 거슬러 셀 것인가. 주 1회면 7일

STAMP=$(date +%Y%m%dT%H%M%S)
FILE="$OUT_DIR/report-$STAMP.txt"

docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null | grep -q true \
    || { echo "DB 컨테이너가 돌고 있지 않다: $DB_CONTAINER" >&2; exit 1; }

mkdir -p "$OUT_DIR"

{
    echo "# slow query report  $(date +%FT%T%z)  container=$DB_CONTAINER db=$DB_NAME"
    docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -X -q -v ON_ERROR_STOP=1 -f - \
        < scripts/db/slow-query-report.sql
    echo
    echo "[7] 서버 로그의 락 대기 (최근 $LOG_SINCE, log_lock_waits=on)"
    LOCK_LOG=$(docker logs --since "$LOG_SINCE" "$DB_CONTAINER" 2>&1 | grep -E "still waiting for|deadlock detected" || true)
    # grep -c는 0건이어도 "0"을 찍고 exit 1이라 || echo 0을 붙이면 0이 두 번 나온다. || true로.
    echo "  still waiting  : $(grep -c 'still waiting for' <<< "$LOCK_LOG" || true)"
    echo "  deadlock       : $(grep -c 'deadlock detected' <<< "$LOCK_LOG" || true)"
    [[ -n "$LOCK_LOG" ]] && { echo; sed 's/^/  /' <<< "$LOCK_LOG" | tail -20; }
} > "$FILE"

echo "$FILE"
