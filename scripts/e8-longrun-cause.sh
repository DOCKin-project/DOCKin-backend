#!/usr/bin/env bash
#
# E8 후속 2 — 밤 2의 열화는 코퍼스 크기가 아니라면 무엇인가
#
# [무엇이 남았나]
#   밤 2(2026-08-13)가 빈 코퍼스에서 198,068청크까지 3시간 24분 연속으로 색인하며
#   이런 곡선을 냈다: 0~150k청크 63~65ms/원본으로 평평, 그 뒤 상승, 마지막 표본 144.9ms.
#
#   밤 3이 그 해석을 뒤집었다. **같은 19만 청크 자리에서 다시 재니 60~62.5ms였다** --
#   같은 크기, 같은 상한(2.0), 같은 shared_buffers(128MB), 같은 인스턴스인데 1.8배 빠르다.
#   19만 청크짜리 HNSW 그래프에 삽입하는 비용 자체는 평평 구간과 다르지 않다는 뜻이다.
#
#   즉 밤 2의 x축(코퍼스 크기)과 진짜 원인이 그 밤에는 함께 움직여서 구분되지 않았다.
#   밤 3의 조건들은 5분짜리였고 **조건마다 DB와 앱을 새로 띄웠다.** 그것이 유일한 차이다.
#
# [이 실험이 가르는 것]
#   구멍을 크게 파고(기본 6만 원본) **한 번에 연속으로** 메우게 한다. 코퍼스는 12.6만에서
#   19.8만으로 자라며 밤 2가 열화를 보인 구간을 그대로 지난다.
#   중간에 **앱만 재시작**하고, 그 앞뒤로 처리량이 어떻게 되는지 본다.
#
#     느려지다가 앱 재시작으로 회복한다  → 원인은 앱 쪽이다(JVM 힙/GC, 커넥션 풀, 세션 상태)
#     느려지는데 재시작해도 그대로다      → 원인은 DB 쪽이다(체크포인트·WAL·autovacuum 누적)
#     끝까지 안 느려진다                  → 40분으로는 재현이 안 된다.
#                                          밤 2의 3시간이 조건이었다는 뜻이고, 그것도 답이다
#
#   **세 갈래가 모두 결론이다.** 이 실험에는 "실패"가 없고 "재현 안 됨"만 있다.
#
# [왜 앱만 재시작하는가]
#   DB를 같이 재시작하면 두 후보가 한 번에 사라져 어느 쪽이었는지 모른다. 한 번에 하나씩
#   지운다. DB 쪽이 남으면 그때 DB만 재시작하는 두 번째 실험을 하면 된다 -- 이 스크립트가
#   남기는 CSV가 그 실험이 필요한지까지 알려준다.
#
# [실행]
#   ./scripts/e8-longrun-cause.sh
#   HOLE_SOURCES=3000 RESTART_AFTER_MIN=3 ./scripts/e8-longrun-cause.sh   # 연습 주행
#
set -uo pipefail
cd "$(dirname "$0")/.."

HOLE_SOURCES=${HOLE_SOURCES:-60000}        # 연속 색인 길이를 정한다. 6만 원본 ≈ 60~100분
RESTART_AFTER_MIN=${RESTART_AFTER_MIN:-40} # 이만큼 연속으로 돌린 뒤 앱만 재시작한다

# 앱 재시작으로 안 돌아오면 그 다음에 DB를 재시작한다.
#
# [왜 두 단계인가] 처음에는 앱 재시작 하나만 두었다. 그런데 밤 4의 E1이 돌면서 근거가
# 하나 더 나왔다 -- 밤 3은 같은 19만 청크에서 62.5ms였는데 세 시간 뒤 같은 조건이
# 187.5ms다(3배). 두 밤의 차이가 **밤 3은 조건마다 DB를 재시작했다**는 것이고,
# 밤 4의 E1은 앱만 재시작한다. 즉 DB 쪽이 유력한 용의자가 됐다.
#
# 순서를 앱 → DB로 두는 이유는 그 반대로 하면 못 가르기 때문이다. DB를 먼저 재시작하면
# 앱의 커넥션도 함께 끊겨 두 후보가 한 번에 사라진다. 앱부터 지우면 남는 것이 DB다.
DB_RESTART_AFTER_MIN=${DB_RESTART_AFTER_MIN:-60}
STALL_MIN=${STALL_MIN:-6}                  # 이만큼 청크가 안 늘면 구멍이 메워진 것으로 본다
MAX_HOURS=${MAX_HOURS:-3}
SAMPLE_SEC=${SAMPLE_SEC:-60}
TEI_CPUS=${TEI_CPUS:-2.0}
COMPOSE_FILES=${COMPOSE_FILES:-"-f compose.yaml -f compose.gc.yaml"}
VACUUM_OPTS=${VACUUM_OPTS:-"ANALYZE, INDEX_CLEANUP OFF"}   # 밤 3 참고: 전체 VACUUM은 40분을 넘긴다
OUT_ROOT=${OUT_ROOT:-measure}
# 끝나면 인스턴스를 정지한다(EBS는 남는다). night1-run.sh와 같은 스위치. 밤 4가 "유휴가 측정의
# 10배"였다(AWS-MEASUREMENT-RESULTS 밤 4) — 무인으로 걸어 두고 잊으면 그 유휴가 이 실험 비용이 된다.
# 로컬·연습 주행에서는 SHUTDOWN_WHEN_DONE=0. 인스턴스 밖(EC2 메타데이터가 없는 곳)에서는 스스로 끈다.
SHUTDOWN_WHEN_DONE=${SHUTDOWN_WHEN_DONE:-1}

SVC_APP=dockin-app
SVC_DB=DOCKin-DB
SVC_TEI=dockin-embedding
DB_USER=${DB_USER:-root}
DB_NAME=${DB_NAME:-dockindb}

RUN_ID=$(date +%Y%m%dT%H%M%S)
OUT="$OUT_ROOT/e8cause-$RUN_ID"
MARKS="$OUT/marks.csv"       # 사건의 시각. 곡선을 구간으로 자르는 근거가 된다
mkdir -p "$OUT"

dc()  { docker compose $COMPOSE_FILES "$@"; }
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"; }
psql_q() { dc exec -T "$SVC_DB" psql -U "$DB_USER" -d "$DB_NAME" -qtAX -c "$1" | tr -d '\r'; }
chunks() { psql_q "SELECT count(*) FROM document_chunks;"; }
mark()  { printf '%s,%s,%s,%s\n' "$(date +%s)" "$(date -u +%FT%TZ)" "$1" "$(chunks)" >> "$MARKS"; say "◆ $1"; }

echo "t_epoch,t_iso,event,chunks" > "$MARKS"

for s in "$SVC_DB" "$SVC_TEI"; do
    [[ "$(docker inspect -f '{{.State.Running}}' "$(dc ps -q "$s" 2>/dev/null)" 2>/dev/null)" == "true" ]] \
        || { say "!! '$s' 가 실행 중이 아니다. up -d 부터 하라"; exit 1; }
done

docker update --cpus "$TEI_CPUS" "$(dc ps -q "$SVC_TEI")" >/dev/null \
    || { say "!! TEI 상한 고정 실패"; exit 1; }

CORPUS_FULL=$(chunks)
say "시작 코퍼스 $CORPUS_FULL 청크 / TEI 상한 $TEI_CPUS 고정"

{
    echo "# E8 후속 2(장시간 연속 실행) 환경 — $RUN_ID"
    echo
    echo "| | |"
    echo "|---|---|"
    echo "| 인스턴스 | $(curl -sf --max-time 2 -H "X-aws-ec2-metadata-token: $(curl -sf --max-time 2 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)" http://169.254.169.254/latest/meta-data/instance-type 2>/dev/null || echo 'n/a') |"
    echo "| vCPU / 메모리 | $(nproc) / $(free -g | awk '/^Mem:/{print $2\"GB\"}') |"
    echo "| CPU 지문 | $( { t0=$(date +%s%N); awk 'BEGIN{x=0; for(i=0;i<20000000;i++) x+=i; if(x<0) print x}' >/dev/null 2>&1; t1=$(date +%s%N); echo $(( (t1-t0)/1000000 )); } ) ms |"
    echo "| 구멍 | $HOLE_SOURCES 원본 (가장 오래된 쪽) |"
    echo "| 앱 재시작 | 연속 색인 ${RESTART_AFTER_MIN}분 뒤 1회 |"
    echo "| TEI 상한 | $TEI_CPUS 고정 |"
    echo "| 시작 코퍼스 | $CORPUS_FULL 청크 |"
} > "$OUT/env.md"

# ── 구멍 파기 ────────────────────────────────────────────────────────────────
# 가장 오래된 쪽에 판다. indexAll이 lastId=0부터 훑으므로 첫 페이지에서 바로 일을 만난다.
# 코퍼스가 지나는 구간(12.6만 → 19.8만)은 어느 쪽에 파든 같다.
say "구멍 파기 — 가장 오래된 $HOLE_SOURCES 원본의 청크를 지운다 (몇 분 걸린다)"
psql_q "DELETE FROM document_chunks WHERE (source_type, source_id) IN (
          SELECT source_type, source_id FROM document_chunks
          WHERE source_type = 'WORK_LOG'
          GROUP BY 1, 2 ORDER BY 2 LIMIT $HOLE_SOURCES);" >/dev/null
psql_q "VACUUM ($VACUUM_OPTS) document_chunks;" >/dev/null
mark "구멍 완료"
say "구멍: $CORPUS_FULL → $(chunks) 청크"

# ── 샘플러 ──────────────────────────────────────────────────────────────────
SAMPLE_SEC="$SAMPLE_SEC" OUT_ROOT="$OUT_ROOT" ./scripts/e8-index-sampler.sh > "$OUT/sampler.log" 2>&1 &
SAMPLER_PID=$!
say "샘플러 시작 (pid $SAMPLER_PID)"

finish() {
    if kill -0 "$SAMPLER_PID" 2>/dev/null; then
        say "샘플러 정지(TERM) — 요약을 찍게 둔다"
        kill -TERM "$SAMPLER_PID" 2>/dev/null || true
        wait "$SAMPLER_PID" 2>/dev/null || true
    fi
    say "완료. 사건 표=$MARKS  표본=$(ls -d ${OUT_ROOT}/e8-* 2>/dev/null | tail -1)/e8-samples.csv"
    [[ -f "$MARKS" ]] && column -s, -t "$MARKS"
    if [[ "$SHUTDOWN_WHEN_DONE" == "1" ]]; then
        if curl -sf --max-time 2 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60'                 http://169.254.169.254/latest/api/token >/dev/null 2>&1; then
            say "60초 뒤 인스턴스를 정지한다 (EBS는 남는다). 산출물은 $OUT 에 있다 — 아침에 먼저 내려받을 것"
            sleep 60
            sudo shutdown -h now
        else
            say "EC2가 아니라 정지하지 않는다 (SHUTDOWN_WHEN_DONE=1이지만 메타데이터 서비스가 없다)"
        fi
    fi
}
trap finish EXIT

# ── 연속 색인 ────────────────────────────────────────────────────────────────
dc up -d "$SVC_APP" >/dev/null 2>&1
mark "앱 기동(1회차) — 연속 색인 시작"

T_START=$(date +%s)
RESTARTED=0
STALL=0
LAST=$(chunks)
DEADLINE=$(( T_START + MAX_HOURS*3600 ))

while :; do
    sleep 60
    NOW=$(chunks)
    ELAPSED_MIN=$(( ( $(date +%s) - T_START ) / 60 ))

    if (( NOW > LAST )); then STALL=0; else STALL=$((STALL + 1)); fi
    LAST=$NOW

    # 앱만 재시작한다. DB는 건드리지 않는다 -- 후보를 하나씩 지우는 것이 이 실험의 전부다.
    if (( RESTARTED == 0 )) && (( ELAPSED_MIN >= RESTART_AFTER_MIN )); then
        mark "앱 재시작 직전"
        dc restart "$SVC_APP" >/dev/null 2>&1
        RESTARTED=1
        mark "앱 재시작 완료 — 여기부터 2회차"
        say "  (재시작 뒤에는 indexAll이 lastId=0부터 다시 훑으므로 건너뛰기 구간이 한 번 지나간다)"
        STALL=0
        continue
    fi

    # DB 재시작. 앱을 지우고도 안 돌아왔을 때만 의미가 있으므로 앱 재시작 뒤에 온다.
    # 앱을 먼저 내렸다가 DB를 올리고 다시 띄운다 -- 앱이 붙어 있는 채로 DB를 내리면
    # 색인이 예외로 죽어 그 밤의 남은 구간을 못 쓴다(밤 1이 그렇게 끝났다).
    if (( RESTARTED == 1 )) && (( ELAPSED_MIN >= DB_RESTART_AFTER_MIN )); then
        mark "DB 재시작 직전"
        dc stop "$SVC_APP" >/dev/null 2>&1
        dc restart "$SVC_DB" >/dev/null 2>&1
        for _ in $(seq 1 60); do psql_q "SELECT 1;" >/dev/null 2>&1 && break; sleep 2; done
        dc up -d "$SVC_APP" >/dev/null 2>&1
        RESTARTED=2
        mark "DB 재시작 완료 — 여기부터 3회차"
        STALL=0
        continue
    fi

    if (( STALL >= STALL_MIN )); then
        mark "구멍이 메워졌다(정체 ${STALL_MIN}분)"
        break
    fi
    if (( $(date +%s) >= DEADLINE )); then
        mark "!! ${MAX_HOURS}시간 상한 — 구멍이 남은 채로 끝낸다"
        break
    fi
done

say "최종 코퍼스 $(chunks) 청크 (시작 $CORPUS_FULL)"
