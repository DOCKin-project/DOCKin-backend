#!/usr/bin/env bash
#
# 팽창 스냅샷을 파일로 남긴다. (docs/DB-IMPROVEMENT-PLAN.md A1)
#
# [실행]
#   ./scripts/db/bloat-snapshot.sh                       # measure/bloat/bloat-YYYYmmddTHHMMSS.txt
#   OUT_DIR=measure/e8/raw ./scripts/db/bloat-snapshot.sh   # 측정 산출물 폴더 옆에
#   TAG=after-delete ./scripts/db/bloat-snapshot.sh      # 파일명에 꼬리표
#
# [언제 부르나]
#   벤치·측정 스크립트의 앞뒤. 대량 삭제 절차(docs/db/after-bulk-delete.sql)의 앞뒤.
#   "전"이 없으면 "후"만 봐서는 줄었는지 알 수 없다 -- 밤 3이 창 앞뒤로 두 번 읽은 것과 같은 이유.
#
# [무엇을 읽나]
#   scripts/db/bloat-snapshot.sql. 통계 수집기 값(공짜)과 pgstattuple_approx(가시성 맵으로
#   건너뛰는 추정)만 쓴다. 힙 전체를 읽는 정확값은 일부러 빼 두었다.
#
# [.env를 읽는다]
#   backup/db-backup.sh와 같다. 컨테이너 안 로컬 소켓은 trust라 비밀번호가 필요 없다.
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
OUT_DIR=${OUT_DIR:-measure/bloat}
TAG=${TAG:-}

STAMP=$(date +%Y%m%dT%H%M%S)
FILE="$OUT_DIR/bloat-$STAMP${TAG:+-$TAG}.txt"

docker inspect -f '{{.State.Running}}' "$DB_CONTAINER" 2>/dev/null | grep -q true \
    || { echo "DB 컨테이너가 돌고 있지 않다: $DB_CONTAINER" >&2; exit 1; }

mkdir -p "$OUT_DIR"

{
    echo "# bloat snapshot  $(date +%FT%T%z)  container=$DB_CONTAINER db=$DB_NAME${TAG:+ tag=$TAG}"
    docker exec -i "$DB_CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -X -q -v ON_ERROR_STOP=1 -f - \
        < scripts/db/bloat-snapshot.sql
} > "$FILE"

echo "$FILE"
