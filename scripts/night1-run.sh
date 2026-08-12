#!/usr/bin/env bash
#
# 밤 1 무인 실행 (docs/AWS-MEASUREMENT-PLAN.md 8-3-1)
#
#   빈 청크 코퍼스 → 상한 2.0으로 색인(E8 1구간) → 위치 P에서 E1 스윕 →
#   이긴 상한으로 완주(E8 2구간) → 샘플러 정지 → 인스턴스 정지.
#
#   사람이 자는 동안 돌라고 만든 것이라, 손으로 칠 때는 괜찮았던 두 가지를 스스로 해야 한다:
#   무엇을 "이긴 상한"으로 볼지 정하는 것과, 색인이 끝났다는 것을 판정하는 것이다.
#
# [실행]
#   cd ~/DOCKin-spring && setsid nohup ./scripts/night1-run.sh > ~/night1.log 2>&1 < /dev/null &
#
# [끝나면 인스턴스가 멈춘다] 성공이든 실패든 멈춘다. 정지는 EBS를 지우지 않으므로
# 코퍼스와 결과는 남고, 아침에 켜서 이어보면 된다. 실패했는데 켜둔 채 자면 요금만 는다.
#
set -euo pipefail
cd "$(dirname "$0")/.."

P_CHUNKS=${P_CHUNKS:-25000}            # 스윕을 걸 위치. 6-11이 잰 자리와 같게 둔다
STALL_MIN=${STALL_MIN:-10}             # 이만큼 청크가 안 늘면 색인이 끝난 것으로 본다
MAX_INDEX_HOURS=${MAX_INDEX_HOURS:-8}
SAMPLE_SEC=${SAMPLE_SEC:-60}
SHUTDOWN_WHEN_DONE=${SHUTDOWN_WHEN_DONE:-1}
COMPOSE_FILES=${COMPOSE_FILES:-"-f compose.yaml -f compose.gc.yaml"}

SVC_APP=dockin-app
SVC_DB=DOCKin-DB
SVC_TEI=dockin-embedding
DB_USER=${DB_USER:-root}
DB_NAME=${DB_NAME:-dockindb}

RUN_ID=$(date +%Y%m%dT%H%M%S)
OUT="measure/night1-$RUN_ID"
STATUS="$OUT/STATUS"

mkdir -p "$OUT"
dc()  { docker compose $COMPOSE_FILES "$@"; }
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
psql_q() { dc exec -T "$SVC_DB" psql -U "$DB_USER" -d "$DB_NAME" -qtAX -c "$1" | tr -d '\r'; }
chunks() { psql_q "SELECT count(*) FROM document_chunks;"; }

SAMPLER_PID=""
FAILED=""

# 어떻게 끝나든 여기를 지난다. 샘플러를 TERM으로 끊어야 그쪽이 구간별 표를 찍고 죽는다 --
# KILL로 죽이면 밤새 모은 것이 요약되지 않은 채 CSV로만 남는다.
finish() {
    local rc=$?
    [[ -n "$FAILED" ]] && rc=1
    if [[ -n "$SAMPLER_PID" ]] && kill -0 "$SAMPLER_PID" 2>/dev/null; then
        say "샘플러 정지(TERM) — 요약을 찍게 둔다"
        kill -TERM "$SAMPLER_PID" 2>/dev/null || true
        for _ in $(seq 1 15); do kill -0 "$SAMPLER_PID" 2>/dev/null || break; sleep 1; done
    fi
    if (( rc == 0 )); then
        { echo "OK"; echo "chunks=$(chunks 2>/dev/null || echo '?')"; } > "$STATUS"
        say "밤 1 정상 종료"
    else
        { echo "FAILED"; echo "reason=${FAILED:-exit $rc}"; } > "$STATUS"
        say "!! 밤 1 실패: ${FAILED:-exit $rc}"
    fi
    say "산출물: $OUT · measure/e1-* · measure/e8-*"
    if [[ "$SHUTDOWN_WHEN_DONE" == "1" ]]; then
        say "60초 뒤 인스턴스를 정지한다 (EBS는 남는다)"
        sleep 60
        sudo shutdown -h now
    fi
    exit "$rc"
}
trap finish EXIT

# ── 1. 출발선 ---------------------------------------------------------------
say "밤 1 시작 — run=$RUN_ID"
WL=$(psql_q "SELECT count(*) FROM work_logs;")
(( WL > 1000 )) || { FAILED="work_logs가 $WL 행뿐이다. 코퍼스를 먼저 만든다"; exit 1; }
say "work_logs $WL 행"

# E8이 보려는 것은 빈 코퍼스 쪽 끝이다. 연습 주행이나 앞선 시도가 남긴 청크가 있으면
# 곡선의 왼쪽이 이미 없는 상태로 시작하게 된다.
BEFORE=$(chunks)
if (( BEFORE > 0 )); then
    say "청크 $BEFORE 개가 남아 있다 — 비우고 시작한다"
    psql_q "TRUNCATE document_chunks;" >/dev/null
    psql_q "VACUUM (ANALYZE) document_chunks;" >/dev/null
fi
(( $(chunks) == 0 )) || { FAILED="청크를 0으로 만들지 못했다"; exit 1; }
say "청크 0 — 출발선 확인"

dc stop "$SVC_APP" >/dev/null 2>&1 || true
docker update --cpus=2.0 "$(dc ps -qa "$SVC_TEI" | head -1)" >/dev/null
say "TEI 상한 2.0 고정 (E8 1구간)"

# ── 2. 샘플러 먼저 ----------------------------------------------------------
# 색인보다 늦게 띄우면 곡선의 왼쪽 끝이 없다.
SAMPLE_SEC="$SAMPLE_SEC" ./scripts/e8-index-sampler.sh > "$OUT/sampler.log" 2>&1 &
SAMPLER_PID=$!
sleep 3
kill -0 "$SAMPLER_PID" 2>/dev/null || { FAILED="샘플러가 즉시 죽었다"; exit 1; }
say "샘플러 시작 (pid $SAMPLER_PID)"

# ── 3. 색인 시작 → 위치 P까지 -----------------------------------------------
dc start "$SVC_APP" >/dev/null
say "색인 시작 — 위치 P($P_CHUNKS 청크)까지 기다린다"

DEADLINE=$(( $(date +%s) + MAX_INDEX_HOURS*3600 ))
while :; do
    C=$(chunks)
    (( C >= P_CHUNKS )) && break
    (( $(date +%s) > DEADLINE )) && { FAILED="P에 도달하기 전에 ${MAX_INDEX_HOURS}시간을 넘겼다"; exit 1; }
    sleep 60
done
say "P 도달: $(chunks) 청크"

# ── 4. E1 스윕 ---------------------------------------------------------------
# 스윕은 앱을 내렸다 올리며 자기 슬라이스를 되돌린다. 끝나면 앱은 내려가 있다.
say "E1 스윕 시작"
if ! ./scripts/e1-tei-cpu-sweep.sh > "$OUT/sweep.log" 2>&1; then
    FAILED="E1 스윕이 실패했다 ($OUT/sweep.log)"; exit 1
fi
SWEEP_CSV=$(ls -td measure/e1-*/ | head -1)e1-sweep.csv
[[ -f "$SWEEP_CSV" ]] || { FAILED="스윕 CSV를 찾지 못했다"; exit 1; }
say "스윕 완료 — $SWEEP_CSV"

# 이긴 상한을 고른다. 원본당 ms가 가장 작은 조건이며, 측정이 성립하지 않은 행(note가 있는
# 행, 원본당 ms가 빈 행)은 뺀다. 고르지 못하면 2.0으로 되돌아간다 -- 밤을 멈추는 것보다
# 느리게라도 완주하는 편이 낫다.
WINNER=$(awk -F, 'NR>1 && $9 != "" && $16 == "" {print $9, $2}' "$SWEEP_CSV" | sort -n | head -1 | awk '{print $2}')
[[ -n "$WINNER" ]] || { WINNER=2.0; say "!! 이긴 상한을 못 골랐다 — 2.0으로 완주한다"; }
say "이긴 상한: TEI $WINNER"
echo "$WINNER" > "$OUT/winner-cpus"

# ── 5. 완주 -----------------------------------------------------------------
docker update --cpus="$WINNER" "$(dc ps -qa "$SVC_TEI" | head -1)" >/dev/null
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
dc start "$SVC_APP" >/dev/null
say "이긴 상한으로 색인 재개 (E8 2구간)"

# 끝났다는 판정은 두 가지로 본다. 앱 로그의 "인덱싱 종료"가 1차 신호이고,
# 청크가 STALL_MIN 동안 안 느는 것이 2차다. 로그만 보면 앱이 조용히 죽었을 때 영원히 기다리고,
# 정체만 보면 재시작 직후의 건너뛰기 구간을 종료로 오인한다.
LAST=$(chunks); STALL=0
DEADLINE=$(( $(date +%s) + MAX_INDEX_HOURS*3600 ))
while :; do
    sleep 60
    if docker logs --since "$SINCE" dockin-app-1 2>&1 | grep -q "인덱싱 종료"; then
        say "앱이 인덱싱 종료를 알렸다"; break
    fi
    C=$(chunks)
    if (( C > LAST )); then LAST=$C; STALL=0; else STALL=$((STALL+1)); fi
    (( STALL >= STALL_MIN )) && { say "청크가 ${STALL_MIN}분간 늘지 않았다 — 끝난 것으로 본다"; break; }
    (( $(date +%s) > DEADLINE )) && { FAILED="완주 전에 ${MAX_INDEX_HOURS}시간을 넘겼다"; exit 1; }
done

say "최종 코퍼스: $(chunks) 청크 / 원본 $(psql_q "SELECT count(DISTINCT (source_type, source_id)) FROM document_chunks;")"
cp "$SWEEP_CSV" "$OUT/" 2>/dev/null || true
