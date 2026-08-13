#!/usr/bin/env bash
#
# 밤 1 무인 실행 (docs/AWS-MEASUREMENT-PLAN.md 8-3-1)
#
#   빈 청크 코퍼스 → 상한 2.0으로 색인(E8 1구간) → 위치 P에서 E1 스윕 →
#   이긴 상한으로 완주(E8 2구간) → 샘플러 정지 → 인스턴스 정지.
#
#   사람이 자는 동안 돌라고 만든 것이라, 손으로 칠 때는 괜찮았던 두 가지를 스스로 해야 한다:
#   무엇을 "이긴 상한"으로 볼지 정하는 것과, 색인이 끝났다는 것을 판정하는 것이다.
#   밤 1(2026-08-13)에서 **둘 다 틀렸다** -- 전 조건 동률을 2.0의 승리로 읽었고, 유니크 충돌로
#   죽은 실행을 완주로 읽고 13%에서 인스턴스를 껐다. 두 자리의 주석이 그 사고를 적어두고 있다.
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
SEG1_CPUS=${SEG1_CPUS:-2.0}            # E8 1구간을 도는 TEI 상한. compose.yaml의 값과 같게 둔다
TIE_PCT=${TIE_PCT:-2}                  # 최고값의 이 %  안이면 동률로 본다 (아래 '이긴 상한' 참고)
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
END_REASON=""   # 무엇을 보고 색인이 끝났다고 판정했는지. finish()가 STATUS에 옮긴다

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
        # end_reason을 함께 적는다. "완주 로그"와 "정체 판정"은 둘 다 rc=0으로 끝나지만
        # 아침에 읽을 때 무게가 다르다 -- 정체는 앱이 조용히 죽은 경우도 포함한다.
        { echo "OK"
          echo "chunks=$(chunks 2>/dev/null || echo '?')"
          echo "end_reason=${END_REASON:-?}"
        } > "$STATUS"
        say "밤 1 정상 종료 (${END_REASON:-?})"
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
docker update --cpus="$SEG1_CPUS" "$(dc ps -qa "$SVC_TEI" | head -1)" >/dev/null
say "TEI 상한 $SEG1_CPUS 고정 (E8 1구간)"

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
# 행, 원본당 ms가 빈 행)은 뺀다. 고르지 못하면 1구간 상한으로 되돌아간다 -- 밤을 멈추는 것보다
# 느리게라도 완주하는 편이 낫다.
#
# 최소값 하나를 집는 것으로는 부족하다. 밤 1(2026-08-13)에서 여섯 조건이 전부 75.0ms/원본으로
# **같은 값**이 나왔고, sort는 그중 첫 줄인 2.0을 승자로 내놨다. "상한을 올려도 처리량이
# 안 변한다"가 그 측정의 결론인데 출력은 "2.0이 이겼다"로 보였다 -- 동률을 동률이라고
# 말하지 못하면 아침에 CSV를 다시 펴 보기 전까지 결론이 뒤집혀 있다.
#
# 그래서 최고값의 +TIE_PCT% 안에 드는 조건을 모두 동률로 보고, 그중 **가장 낮은 상한**을 고른다.
# 처리량이 같다면 CPU를 덜 쓰는 쪽이 이긴 것이 맞다. 동률 여부와 폭은 winner-why에 남긴다.
# awk의 n을 BEGIN에서 0으로 못박는다. 안 그러면 첫 행의 첨자가 빈 문자열이 되어 ms[""]에
# 들어가고, 루프가 도는 ms[0]은 값이 없는 유령 행(0ms)이 된다 -- 그 유령이 항상 동률대에
# 들어가므로 동률 개수가 하나 부풀고, 승자도 첫 조건이 아닌 엉뚱한 줄에서 나온다.
read -r WINNER BEST WORST NBAND NVALID <<< "$(awk -F, -v tie="$TIE_PCT" '
    BEGIN { n = 0 }
    NR > 1 && $9 != "" && $16 == "" {
        ms[n] = $9 + 0; cpu[n] = $2 + 0; n++
        if (best  == "" || $9 + 0 < best)  best  = $9 + 0
        if (worst == "" || $9 + 0 > worst) worst = $9 + 0
    }
    END {
        if (n == 0) exit 0
        band = best * (1 + tie / 100)
        for (i = 0; i < n; i++)
            if (ms[i] <= band) { c++; if (c == 1 || cpu[i] < w) w = cpu[i] }
        printf "%.1f %.1f %.1f %d %d\n", w, best, worst, c, n
    }' "$SWEEP_CSV")"

if [[ -z "$WINNER" ]]; then
    WINNER="$SEG1_CPUS"
    WHY="유효한 행이 없다 — 1구간과 같은 $SEG1_CPUS 로 완주한다"
    say "!! 이긴 상한을 못 골랐다: $WHY"
elif (( NBAND == NVALID )); then
    WHY="전 조건 동률 (${BEST}~${WORST}ms/원본, +${TIE_PCT}% 이내) — 가장 낮은 상한을 고른다. 상한은 이 워크로드의 제약이 아니다"
    say "이긴 상한: TEI $WINNER — $WHY"
elif (( NBAND > 1 )); then
    WHY="${NVALID}조건 중 ${NBAND}개가 동률대 (최고 ${BEST}ms/원본, +${TIE_PCT}%) — 그중 가장 낮은 상한"
    say "이긴 상한: TEI $WINNER — $WHY"
else
    WHY="단독 최고 ${BEST}ms/원본 (최저 ${WORST}ms)"
    say "이긴 상한: TEI $WINNER — $WHY"
fi

# 1구간과 같은 상한이면 E8의 두 구간은 조건이 같다. 곡선은 이어서 그릴 수 있지만
# "상한을 바꿔 완주했다"고 읽으면 안 된다.
[[ "$WINNER" == "$SEG1_CPUS" ]] && say "※ 1구간과 같은 상한이다 — E8 2구간은 조건이 바뀌지 않는다"

echo "$WINNER" > "$OUT/winner-cpus"
printf '%s\n' "$WHY" > "$OUT/winner-why"

# ── 5. 완주 -----------------------------------------------------------------
docker update --cpus="$WINNER" "$(dc ps -qa "$SVC_TEI" | head -1)" >/dev/null
SINCE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
dc start "$SVC_APP" >/dev/null
say "이긴 상한으로 색인 재개 (E8 2구간)"

# 끝났다는 판정은 세 가지로 본다. 앱 로그의 "인덱싱 완주"가 1차 신호, "인덱싱 중단"이 보이면
# 그 자리에서 실패로 끝내는 것이 2차, 청크가 STALL_MIN 동안 안 느는 것이 3차다.
# 로그만 보면 앱이 조용히 죽었을 때 영원히 기다리고, 정체만 보면 재시작 직후의 건너뛰기 구간을
# 종료로 오인한다.
#
# 2차가 이번에 추가됐다. 밤 1(2026-08-13)에는 "인덱싱 종료" 하나만 봤는데, 앱이 예외를 잡고도
# 같은 줄을 찍었다. 03:00 정각 cron이 기동 직후 색인과 겹쳐 uk_chunk_source 유니크 충돌로 죽은
# 실행이 그 줄을 찍었고, 스크립트는 60초 만에 완주로 읽고 인스턴스를 껐다 -- 그때 다른 스레드는
# 아직 색인 중이었고, 코퍼스는 165,016원본 중 21,800원본(13%)에서 멈췄다. STATUS에는 OK가 남았다.
#
# 중단을 실패로 보는 이유: 앱은 다음 주기에 이어서 진행할 수 있지만, 측정은 그럴 수 없다.
# 코퍼스가 어디까지 찼는지 모르는 채로 이어 그린 E8 곡선은 읽을 수 없다.
LAST=$(chunks); STALL=0
DEADLINE=$(( $(date +%s) + MAX_INDEX_HOURS*3600 ))
while :; do
    sleep 60
    APP_LOG=$(docker logs --since "$SINCE" dockin-app-1 2>&1 || true)

    if grep -q "인덱싱 중단" <<< "$APP_LOG"; then
        FAILED="색인이 중단됐다 — $(grep "인덱싱 중단" <<< "$APP_LOG" | tail -1 | cut -c1-300)"
        exit 1
    fi
    if grep -q "인덱싱 완주" <<< "$APP_LOG"; then
        END_REASON="완주 로그"
        say "앱이 인덱싱 완주를 알렸다"; break
    fi

    C=$(chunks)
    if (( C > LAST )); then LAST=$C; STALL=0; else STALL=$((STALL+1)); fi
    (( STALL >= STALL_MIN )) && { END_REASON="정체 ${STALL_MIN}분 (완주 로그 없음)"; say "청크가 ${STALL_MIN}분간 늘지 않았다 — 끝난 것으로 본다"; break; }
    (( $(date +%s) > DEADLINE )) && { FAILED="완주 전에 ${MAX_INDEX_HOURS}시간을 넘겼다"; exit 1; }
done

say "최종 코퍼스: $(chunks) 청크 / 원본 $(psql_q "SELECT count(DISTINCT (source_type, source_id)) FROM document_chunks;")"
cp "$SWEEP_CSV" "$OUT/" 2>/dev/null || true
