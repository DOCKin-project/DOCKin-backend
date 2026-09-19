#!/usr/bin/env bash
#
# E8 후속 3 — 연속 색인이 끝난 DB에서 창을 다시 열면 얼마인가 (DB-IMPROVEMENT-PLAN B1)
#
# [왜]
#   밤 4의 단서는 "같은 19만 청크인데 세 시간 뒤 같은 창이 3배"였다 -- 연속 색인 **중**이 아니라
#   색인이 끝난 DB에 **다시 쓸 때** 느렸을 수 있다. e8-longrun-cause.sh(연속 색인)가 끝까지
#   평평하면 그 가설만 남는다. 코퍼스가 살아 있는 지금이 아니면 또 세 시간이다.
#
# [무엇을]
#   본판이 끝나기를 기다렸다가(완료 줄) 본판이 예약한 정지를 취소하고,
#     A. 구멍 HOLE 원본을 파고 다시 메운다 -- 원본당 ms를 본판 기준선(63ms)과 비교
#     B. HNSW를 REINDEX한 뒤 같은 구멍을 다시 파고 메운다 -- A와 비교
#   A가 느리고 B가 빠르면 인덱스 상태(밤 4의 후보), 둘 다 느리면 인덱스 밖의 DB 상태,
#   둘 다 본판과 같으면 "끝난 직후 다시 열기"로는 재현이 안 된다 -- 남는 것은 유휴 시간이다.
#
#   두 창 모두 e8-longrun-cause.sh를 HOLE_SOURCES 모드로 부른다. rate.csv 형식·완주 판정·
#   스냅샷이 본판과 같아 나란히 읽을 수 있다. 개입은 끈다(DEGRADE_FACTOR=999).
#
# [실행] 본판이 도는 동안 미리 걸어 둔다.
#   setsid nohup ./scripts/e8-reopen.sh <본판 run.log 경로> > ~/reopen.log 2>&1 < /dev/null &
#
set -uo pipefail
cd "$(dirname "$0")/.."

MAIN_LOG=${1:?본판 run.log 경로}
HOLE=${HOLE:-12000}
WATCHDOG_MIN=${WATCHDOG_MIN:-120}
HNSW_INDEX=${HNSW_INDEX:-idx_chunk_embedding_hnsw}
OUT=measure/e8reopen-$(date +%Y%m%dT%H%M%S)
mkdir -p "$OUT"
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"; }
psql_c() { docker exec -i dockin-db psql -U root -d dockindb -X -q "$@"; }

say "본판 완료를 기다린다: $MAIN_LOG"
until grep -q '완료\. 사건 표' "$MAIN_LOG" 2>/dev/null; do sleep 30; done
sudo shutdown -c >/dev/null 2>&1 || true
sudo shutdown -h "+$WATCHDOG_MIN" "e8reopen watchdog" >/dev/null 2>&1
say "본판 완료 감지 — 예약된 정지를 취소하고 ${WATCHDOG_MIN}분 watchdog을 걸었다"
while pgrep -f e8-longrun-cause.sh >/dev/null; do sleep 5; done
say "본판 프로세스 종료 확인. 코퍼스 $(psql_c -tA -c 'SELECT count(*) FROM document_chunks;') 청크"

run_window() {   # $1 = 꼬리표
    say "── 창 $1: 구멍 $HOLE 원본 → 재색인"
    HOLE_SOURCES=$HOLE DEGRADE_FACTOR=999 BASELINE_SKIP=2 BASELINE_N=5 STALL_MIN=8 \
    MAX_HOURS=0.75 SHUTDOWN_WHEN_DONE=0 OUT_ROOT="$OUT/$1" \
        ./scripts/e8-longrun-cause.sh 2>&1 | grep -v 'is obsolete' > "$OUT/$1.log"
    say "── 창 $1 끝: $(grep -E '기준선|완주|!!' "$OUT/$1/e8cause-"*/run.log | cut -c1-120 | tr '\n' ' / ')"
}

run_window A

say "REINDEX $HNSW_INDEX (앱은 창 A가 띄운 채로 두면 안 된다 — 내린다)"
docker stop -t 20 dockin-app-1 >/dev/null 2>&1
t0=$(date +%s)
psql_c -c "SET maintenance_work_mem='256MB'; SET max_parallel_maintenance_workers=0; REINDEX (VERBOSE) INDEX $HNSW_INDEX;" > "$OUT/reindex.log" 2>&1
say "REINDEX $(( $(date +%s) - t0 ))초 — $(tail -1 "$OUT/reindex.log")"
psql_c -tA -c "SELECT pg_size_pretty(pg_relation_size('$HNSW_INDEX'));" | sed 's/^/REINDEX 뒤 인덱스 크기: /' | tee -a "$OUT/run.log"

run_window B

say "전부 끝. 10분 뒤 정지한다 (EBS는 남는다)"
sudo shutdown -c >/dev/null 2>&1 || true
sudo shutdown -h +10 "e8reopen done" >/dev/null 2>&1
echo "REOPEN DONE" >> "$OUT/run.log"
