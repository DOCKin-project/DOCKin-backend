#!/usr/bin/env bash
#
# 밤 16 (#83) — 인스턴스 쪽 오케스트레이터. 본판 → 창 A·REINDEX·창 B → DONE 표식.
#
# [무엇이 밤 14와 다른가]
#   · e8-longrun-cause.sh가 매 분 DB 컨테이너 cgroup 메모리를 적고, 첫 개입이 "재시작 없이 상한만 1g"이다(MEM_RAISE).
#   · 정지는 이 스크립트가 하지 않는다. 로컬 fetcher(scripts/e8-fetch-loop.sh)가 5분마다 산출물을 내려받고,
#     DONE 표식을 보면 마지막으로 받은 뒤 terminate한다 -- 밤 14의 산출물이 정지된 인스턴스와 함께 사라졌다(#86).
#   · 그래도 하드 watchdog은 건다(정지). 로컬이 죽어도 요금이 새지 않게. EBS는 남으니 다시 켜서 받을 수 있다.
#
# [실행]  setsid nohup ./scripts/e8-night16.sh > ~/night16.log 2>&1 < /dev/null &
#
set -uo pipefail
cd "$(dirname "$0")/.."

MEM_RAISE=${MEM_RAISE:-1g}
WATCHDOG_MIN=${WATCHDOG_MIN:-480}     # 본판 최대 5.5h + 창 둘 1.5h + 여유
DONE_FILE=${DONE_FILE:-$HOME/NIGHT16_DONE}

say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
rm -f "$DONE_FILE"

sudo shutdown -c >/dev/null 2>&1 || true
sudo shutdown -h "+$WATCHDOG_MIN" "night16 watchdog" >/dev/null 2>&1 && say "watchdog: ${WATCHDOG_MIN}분 뒤 정지"

say "본판 시작 (MEM_RAISE=$MEM_RAISE)"
MEM_RAISE="$MEM_RAISE" SHUTDOWN_WHEN_DONE=0 ./scripts/e8-longrun-cause.sh 2>&1 | grep -v 'is obsolete' > "$HOME/night16-main.log"
MAIN=$(ls -td measure/e8cause-* 2>/dev/null | head -1)
say "본판 끝: $MAIN — $(grep -E '완주|!!|회복|미회복' "$MAIN/run.log" | cut -c1-110 | tr '\n' ' / ')"

# 창 A → REINDEX → 창 B. e8-reopen.sh는 본판 완료 줄을 보고 즉시 시작한다. 제 watchdog(120분)을 다시 건다.
say "창 A·B 시작"
./scripts/e8-reopen.sh "$MAIN/run.log" > "$HOME/night16-reopen.log" 2>&1
say "창 A·B 끝"

# e8-reopen.sh가 +10분 정지를 걸어 둔다. 로컬 fetcher가 그 안에 받고 terminate한다. 못 받으면 정지 → EBS에 남는다.
echo "NIGHT16 DONE $(date -u +%FT%TZ) main=$MAIN" > "$DONE_FILE"
say "DONE 표식: $DONE_FILE"
