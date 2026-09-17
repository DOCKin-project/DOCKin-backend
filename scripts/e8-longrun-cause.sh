#!/usr/bin/env bash
#
# E8 후속 2 — 밤 2의 열화는 코퍼스 크기가 아니라면 무엇인가 (DB-IMPROVEMENT-PLAN B1)
#
# [무엇이 남았나]
#   밤 2(2026-08-13)가 빈 코퍼스에서 198,068청크까지 3시간 24분 연속으로 색인하며
#   이런 곡선을 냈다: 0~150k청크 63~65ms/원본으로 평평, 그 뒤 상승, 마지막 표본 144.9ms.
#
#   밤 3이 그 해석을 뒤집었다. **같은 19만 청크 자리에서 다시 재니 60~62.5ms였다** --
#   같은 크기, 같은 상한(2.0), 같은 shared_buffers(128MB), 같은 인스턴스인데 1.8배 빠르다.
#   19만 청크짜리 HNSW 그래프에 삽입하는 비용 자체는 평평 구간과 다르지 않다는 뜻이다.
#   밤 3의 조건들은 5분짜리였고 **조건마다 DB와 앱을 새로 띄웠다.** 그것이 유일한 차이다.
#   밤 4는 앱만 재시작하며 같은 창을 세 시간 뒤 다시 열었더니 3배였다 -- DB 쪽이 유력해졌다.
#
# [이 실험이 가르는 것]
#   코퍼스를 처음부터 **한 번에 연속으로** 만든다(밤 2와 같은 조건). 그 사이 매 분 두 줄을 남긴다:
#
#     ① 원본당 ms (앱 로그가 아니라 청크 수의 증가분으로 잰다 -- 샘플러와 같다)
#     ② DB가 직접 센 값: pg_stat_statements의 INSERT INTO document_chunks 누적 시간·호출 수,
#        pg_stat_checkpointer, pg_stat_wal, document_chunks의 dead tuple·autovacuum 횟수,
#        힙·HNSW 인덱스 크기, 컨테이너 셋(app·db·tei)의 CPU%
#
#   ②가 있으면 재시작 없이도 절반은 갈린다 -- 원본당 ms는 오르는데 INSERT 한 건의 DB 시간이
#   그대로면 원인은 DB 밖(앱·TEI)이고, INSERT 시간이 같이 오르면 DB 안이다. 체크포인트 횟수와
#   autovacuum 횟수가 그 옆에 있으므로 DB 안이면 어느 것인지도 같은 줄에서 읽는다.
#
#   그래도 재시작은 한다. 기준선(처음 안정 구간의 중앙값) 대비 DEGRADE_FACTOR배가 DEGRADE_N표본
#   연속이면 열화로 보고 **한 번에 하나씩** 지운다:
#
#     1. 앱만 재시작        → 회복하면 앱(JVM 힙/GC, 커넥션 풀, 세션 상태)
#     2. DB만 재시작        → 회복하면 DB의 휘발성 상태(공유 버퍼·WAL 백로그·진행 중 autovacuum)
#     3. HNSW 인덱스 REINDEX → 회복하면 인덱스 자체의 상태(밤 4의 후보)
#     끝까지 안 느려진다     → 이 시간·이 크기로는 재현이 안 된다. 그것도 답이다
#
#   회복 판정은 개입 뒤 OBSERVE_SKIP표본을 버리고(재기동 뒤 lastId=0부터 훑는 건너뛰기 구간)
#   다음 OBSERVE_N표본의 중앙값이 기준선×RECOVER_FACTOR 아래인가로 한다.
#
# [밤 4가 가르쳐 준 것 -- 이 스크립트가 달라진 이유]
#   · 「정체 6분 = 완주」로 읽어서 앱이 죽은 밤을 완주로 찍었다. 이제 완주는 앱 로그의
#     "인덱싱 완주" 줄로만 판정한다. "중단"이면 중단으로 적고, 청크가 안 느는데 두 줄 다 없으면
#     앱 로그 꼬리를 raw/에 남기고 앱을 다시 띄운다(횟수 상한).
#   · `up -d`는 떠 있는 컨테이너에 아무 일도 안 한다. 재시작은 stop → start다.
#   · 색인 락이 Redis로 갔다(2026-09-16, `rag:indexing:lock`, 워치독 30초). 앱을 죽이고 30초 안에
#     새 앱이 뜨면 "다른 색인이 진행 중"으로 건너뛰고 밤이 조용히 죽는다. stop 뒤 키를 지우고 start.
#   · 유휴가 측정의 10배였다. 끝나면 정지하고, 그와 별개로 시작할 때 하드 watchdog을 건다.
#
# [실행]
#   ./scripts/e8-longrun-cause.sh                                   # 시드가 들어 있고 청크 0인 DB에서
#   HOLE_SOURCES=60000 ./scripts/e8-longrun-cause.sh                # 옛 방식: 구멍을 파고 메운다
#   MAX_HOURS=0.3 SHUTDOWN_WHEN_DONE=0 ./scripts/e8-longrun-cause.sh   # 연습 주행
#
set -uo pipefail
cd "$(dirname "$0")/.."

HOLE_SOURCES=${HOLE_SOURCES:-0}            # 0이면 파지 않는다(빈 코퍼스에서 시작). >0이면 옛 방식
SAMPLE_SEC=${SAMPLE_SEC:-60}
MAX_HOURS=${MAX_HOURS:-5.5}
BASELINE_SKIP=${BASELINE_SKIP:-3}          # 기준선에서 버리는 첫 유효 표본 수(워밍업)
BASELINE_N=${BASELINE_N:-15}               # 기준선 중앙값을 낼 유효 표본 수
DEGRADE_FACTOR=${DEGRADE_FACTOR:-1.4}      # 기준선의 이 배수 이상이
DEGRADE_N=${DEGRADE_N:-3}                  # 이만큼 연속이면 열화
OBSERVE_SKIP=${OBSERVE_SKIP:-3}            # 개입 뒤 버리는 유효 표본(건너뛰기 구간·재기동)
OBSERVE_N=${OBSERVE_N:-8}                  # 회복 판정에 쓰는 유효 표본 수
RECOVER_FACTOR=${RECOVER_FACTOR:-1.2}      # 중앙값이 기준선×이것 아래면 회복
STALL_MIN=${STALL_MIN:-10}                 # 앱이 살아 있는데 이만큼 청크가 안 늘면 이상
MAX_CRASH_RESTARTS=${MAX_CRASH_RESTARTS:-3}
TEI_CPUS=${TEI_CPUS:-2.0}
COMPOSE_FILES=${COMPOSE_FILES:-"-f compose.yaml -f compose.gc.yaml"}
VACUUM_OPTS=${VACUUM_OPTS:-"ANALYZE, INDEX_CLEANUP OFF"}
OUT_ROOT=${OUT_ROOT:-measure}
HNSW_INDEX=${HNSW_INDEX:-idx_chunk_embedding_hnsw}
LOCK_KEY=${LOCK_KEY:-rag:indexing:lock}
# 끝나면 인스턴스를 정지한다(EBS는 남는다). 밤 4가 "유휴가 측정의 10배"였다.
# 로컬·연습 주행에서는 SHUTDOWN_WHEN_DONE=0. EC2 메타데이터가 없는 곳에서는 스스로 끈다.
SHUTDOWN_WHEN_DONE=${SHUTDOWN_WHEN_DONE:-1}
SHUTDOWN_GRACE_SEC=${SHUTDOWN_GRACE_SEC:-600}   # 산출물을 내려받을 시간
WATCHDOG_MIN=${WATCHDOG_MIN:-}             # 비우면 MAX_HOURS×60+60. 스크립트가 어떻게 죽어도 이 시각엔 꺼진다

SVC_APP=dockin-app
SVC_DB=DOCKin-DB
SVC_TEI=dockin-embedding
SVC_REDIS=dockin-redis
DB_USER=${DB_USER:-root}
DB_NAME=${DB_NAME:-dockindb}

RUN_ID=$(date +%Y%m%dT%H%M%S)
OUT="$OUT_ROOT/e8cause-$RUN_ID"
RAW="$OUT/raw"
MARKS="$OUT/marks.csv"       # 사건의 시각. 곡선을 구간으로 자르는 근거
RATE="$OUT/rate.csv"         # 매 분: 원본당 ms + DB가 센 값들 (이 실험의 본체)
mkdir -p "$RAW"

dc()  { docker compose $COMPOSE_FILES "$@"; }
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"; }
psql_q() { dc exec -T "$SVC_DB" psql -U "$DB_USER" -d "$DB_NAME" -qtAX -c "$1" 2>/dev/null | tr -d '\r'; }
chunks() { psql_q "SELECT count(*) FROM document_chunks;"; }
on_ec2() { curl -sf --max-time 2 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token >/dev/null 2>&1; }
median() { sort -n | awk '{a[NR]=$1} END{if(NR==0){print ""; exit} if(NR%2){print a[(NR+1)/2]} else {printf "%.1f\n",(a[NR/2]+a[NR/2+1])/2}}'; }

app_id()      { dc ps -qa "$SVC_APP" 2>/dev/null | head -1; }
app_running() { [[ "$(docker inspect -f '{{.State.Running}}' "$(app_id)" 2>/dev/null)" == "true" ]]; }
# 이번 기동 이후의 앱 로그에서 색인의 끝을 찾는다. 완주·중단·건너뜀은 서로 다른 줄이다(IndexingService).
app_index_end() {
    docker logs --since "$APP_STARTED" "$(app_id)" 2>&1 | grep -oE '인덱싱 완주|인덱싱 중단 종료|기동 직후 색인 (완주|중단|실패)|다른 색인이 이미 진행 중' | tail -1
}

# ── 사건 표 + 스냅샷 ─────────────────────────────────────────────────────────
db_snapshot() {   # $1 = 꼬리표. 체크포인트·WAL·pg_stat_statements 상위·autovacuum 진행을 한 파일에
    local tag=$1
    local f="$RAW/$(date +%H%M%S)-$tag-dbstats.txt"
    {
        echo "# $tag  $(date -u +%FT%TZ)"
        dc exec -T "$SVC_DB" psql -U "$DB_USER" -d "$DB_NAME" -X -q <<'SQL'
\echo -- pg_stat_checkpointer
SELECT * FROM pg_stat_checkpointer;
\echo -- pg_stat_wal
SELECT * FROM pg_stat_wal;
\echo -- pg_stat_bgwriter
SELECT * FROM pg_stat_bgwriter;
\echo -- document_chunks
SELECT n_live_tup, n_dead_tup, n_tup_ins, n_mod_since_analyze, n_ins_since_vacuum,
       autovacuum_count, last_autovacuum, autoanalyze_count, last_autoanalyze,
       pg_size_pretty(pg_relation_size('document_chunks')) heap,
       pg_size_pretty(pg_relation_size('idx_chunk_embedding_hnsw')) hnsw
FROM pg_stat_user_tables WHERE relname = 'document_chunks';
\echo -- pg_stat_progress_vacuum
SELECT * FROM pg_stat_progress_vacuum;
\echo -- pg_stat_statements top 8 by total_exec_time
SELECT calls, round(total_exec_time::numeric) total_ms, round(mean_exec_time::numeric,3) mean_ms,
       rows, shared_blks_hit, shared_blks_read, shared_blks_dirtied, shared_blks_written,
       left(regexp_replace(query, '\s+', ' ', 'g'), 90) q
FROM pg_stat_statements ORDER BY total_exec_time DESC LIMIT 8;
\echo -- pg_stat_activity
SELECT pid, state, wait_event_type, wait_event, backend_type, left(query,60) q
FROM pg_stat_activity WHERE backend_type <> 'client backend' OR state <> 'idle';
\echo -- settings
SELECT name, setting, unit FROM pg_settings
WHERE name IN ('shared_buffers','checkpoint_timeout','max_wal_size','checkpoint_completion_target',
               'autovacuum_vacuum_insert_threshold','autovacuum_vacuum_insert_scale_factor',
               'autovacuum_vacuum_cost_delay','autovacuum_naptime','maintenance_work_mem','autovacuum_work_mem');
SQL
    } > "$f" 2>&1
    OUT_DIR="$RAW" TAG="$tag" ./scripts/db/bloat-snapshot.sh >/dev/null 2>&1 || true
}
app_logs_save() { docker logs --tail 400 "$(app_id)" > "$RAW/$(date +%H%M%S)-$1-app.log" 2>&1 || true; }
gc_log_save()   { docker cp "$(app_id):/tmp/gc.log" "$RAW/$(date +%H%M%S)-$1-gc.log" 2>/dev/null || true; }

mark() {   # $1 = 사건, $2 = 스냅샷 꼬리표(비우면 스냅샷 없음)
    printf '%s,%s,%s,%s\n' "$(date +%s)" "$(date -u +%FT%TZ)" "$1" "$(chunks)" >> "$MARKS"
    say "◆ $1"
    [[ -n "${2:-}" ]] && db_snapshot "$2"
}

# ── 앱 재시작: stop → 락 키 삭제 → start ───────────────────────────────────
# restart 한 번으로 하면 Redisson 워치독(30초)이 죽은 JVM의 락을 아직 쥐고 있을 수 있고,
# 새 앱은 "다른 색인이 진행 중"으로 건너뛴다. 그러면 밤이 조용히 끝난다.
app_start() {
    dc exec -T "$SVC_REDIS" redis-cli DEL "$LOCK_KEY" >/dev/null 2>&1 || true
    APP_STARTED=$(date -u +%FT%TZ)
    dc up -d "$SVC_APP" >/dev/null 2>&1
}
app_stop() { dc stop -t 20 "$SVC_APP" >/dev/null 2>&1; }

echo "t_epoch,t_iso,event,chunks" > "$MARKS"
echo "t_epoch,t_iso,elapsed_s,phase,chunks,sources,d_chunks,d_sources,ms_per_source,app,ins_calls,ins_ms,d_ins_calls,d_ins_ms,ins_ms_per_call,all_calls,all_ms,ckpt_timed,ckpt_req,ckpt_buffers,wal_mb,n_dead,n_ins_since_vac,autovac_count,last_autovac,vac_running,heap_mb,hnsw_mb,backends,lock_waits,cpu_app,cpu_db,cpu_tei,mem_app" > "$RATE"

# ── 전제 확인 ────────────────────────────────────────────────────────────────
for s in "$SVC_DB" "$SVC_TEI" "$SVC_REDIS"; do
    [[ "$(docker inspect -f '{{.State.Running}}' "$(dc ps -q "$s" 2>/dev/null)" 2>/dev/null)" == "true" ]] \
        || { say "!! '$s' 가 실행 중이 아니다. up -d 부터 하라"; exit 1; }
done
docker update --cpus "$TEI_CPUS" "$(dc ps -q "$SVC_TEI")" >/dev/null || { say "!! TEI 상한 고정 실패"; exit 1; }

psql_q "CREATE EXTENSION IF NOT EXISTS pg_stat_statements; CREATE EXTENSION IF NOT EXISTS pgstattuple;" >/dev/null
psql_q "SELECT 1 FROM pg_stat_statements LIMIT 1;" >/dev/null || { say "!! pg_stat_statements가 없다 (shared_preload_libraries?)"; exit 1; }
[[ -n "$(psql_q "SELECT 1 FROM pg_class WHERE relname='$HNSW_INDEX';")" ]] || { say "!! 인덱스 $HNSW_INDEX 가 없다"; exit 1; }

# 묵은 백엔드 정리. 밤 4 ①은 E1이 남긴 앱이 아직 색인 중인데 새 앱이 또 시작해 죽었다[미검증].
# 앱을 내리고, 남은 JDBC 세션을 끊고, 락 키를 지운 채로 시작한다.
if app_running; then say "앱이 떠 있다 — 내린다 (묵은 색인 정리)"; app_stop; fi
psql_q "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE backend_type='client backend' AND pid <> pg_backend_pid() AND usename='$DB_USER' AND application_name <> 'psql';" >/dev/null
dc exec -T "$SVC_REDIS" redis-cli DEL "$LOCK_KEY" >/dev/null 2>&1 || true
psql_q "SELECT pg_stat_statements_reset();" >/dev/null
psql_q "SELECT pg_stat_reset_shared('checkpointer'); SELECT pg_stat_reset_shared('wal'); SELECT pg_stat_reset_shared('bgwriter');" >/dev/null

# 하드 watchdog. 스크립트가 어떻게 죽어도 이 시각엔 인스턴스가 꺼진다. finish가 취소하고 제 방식으로 끈다.
WD=${WATCHDOG_MIN:-$(awk -v h="$MAX_HOURS" 'BEGIN{print int(h*60)+60}')}
if [[ "$SHUTDOWN_WHEN_DONE" == "1" ]] && on_ec2; then
    sudo shutdown -h "+$WD" "e8cause watchdog" >/dev/null 2>&1 && say "watchdog: ${WD}분 뒤 강제 정지 예약"
fi

CORPUS_START=$(chunks)
SOURCES_TOTAL=$(psql_q "SELECT count(*) FROM work_logs;")
say "시작 코퍼스 $CORPUS_START 청크 / 원본 $SOURCES_TOTAL / TEI 상한 $TEI_CPUS 고정"

{
    echo "# E8 후속 2(장시간 연속 실행) 환경 — $RUN_ID"
    echo
    echo "| | |"
    echo "|---|---|"
    echo "| 인스턴스 | $(curl -sf --max-time 2 -H "X-aws-ec2-metadata-token: $(curl -sf --max-time 2 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' http://169.254.169.254/latest/api/token)" http://169.254.169.254/latest/meta-data/instance-type 2>/dev/null || echo 'n/a') |"
    echo "| vCPU / 메모리 | $(nproc) / $(free -g | awk '/^Mem:/{print $2"GB"}') |"
    echo "| CPU 지문 | $( { t0=$(date +%s%N); awk 'BEGIN{x=0; for(i=0;i<20000000;i++) x+=i; if(x<0) print x}' >/dev/null 2>&1; t1=$(date +%s%N); echo $(( (t1-t0)/1000000 )); } ) ms |"
    echo "| 커밋 | $(git rev-parse --short HEAD 2>/dev/null || echo n/a) |"
    echo "| PostgreSQL | $(psql_q 'SHOW server_version;') |"
    echo "| 시작 방식 | $([[ "$HOLE_SOURCES" -gt 0 ]] && echo "구멍 $HOLE_SOURCES 원본" || echo "빈 코퍼스에서 연속 색인") |"
    echo "| 원본 / 시작 청크 | $SOURCES_TOTAL / $CORPUS_START |"
    echo "| TEI 상한 | $TEI_CPUS 고정 |"
    echo "| 열화 판정 | 기준선(유효 표본 $((BASELINE_SKIP+1))~$((BASELINE_SKIP+BASELINE_N)) 중앙값)×$DEGRADE_FACTOR 이상 $DEGRADE_N표본 연속 |"
    echo "| 회복 판정 | 개입 뒤 유효 표본 $OBSERVE_SKIP개 버리고 $OBSERVE_N개 중앙값 < 기준선×$RECOVER_FACTOR |"
    echo "| 개입 순서 | 앱 재시작 → DB 재시작 → REINDEX $HNSW_INDEX |"
} > "$OUT/env.md"

# ── (선택) 구멍 파기 ─────────────────────────────────────────────────────────
if (( HOLE_SOURCES > 0 )); then
    say "구멍 파기 — 가장 오래된 $HOLE_SOURCES 원본의 청크를 지운다"
    psql_q "DELETE FROM document_chunks WHERE (source_type, source_id) IN (
              SELECT source_type, source_id FROM document_chunks
              WHERE source_type = 'WORK_LOG' GROUP BY 1, 2 ORDER BY 2 LIMIT $HOLE_SOURCES);" >/dev/null
    psql_q "VACUUM ($VACUUM_OPTS) document_chunks;" >/dev/null
    mark "구멍 완료" hole
    say "구멍: $CORPUS_START → $(chunks) 청크"
fi
db_snapshot start

# ── 샘플러(기존 E8 형식도 나란히) ───────────────────────────────────────────
SAMPLE_SEC="$SAMPLE_SEC" OUT_ROOT="$OUT" ./scripts/e8-index-sampler.sh > "$OUT/sampler.log" 2>&1 &
SAMPLER_PID=$!

finish() {
    trap - EXIT
    if kill -0 "$SAMPLER_PID" 2>/dev/null; then kill -TERM "$SAMPLER_PID" 2>/dev/null || true; wait "$SAMPLER_PID" 2>/dev/null || true; fi
    app_logs_save final; gc_log_save final
    docker logs "$(dc ps -qa "$SVC_DB")" 2>&1 | grep -iE 'checkpoint|automatic vacuum|automatic analyze' > "$RAW/db-checkpoint-autovacuum.log" || true
    db_snapshot final
    say "완료. 사건 표=$MARKS  본체=$RATE"
    { column -s, -t "$MARKS" 2>/dev/null || cat "$MARKS"; } | tee -a "$OUT/run.log"
    if [[ "$SHUTDOWN_WHEN_DONE" == "1" ]]; then
        if on_ec2; then
            sudo shutdown -c >/dev/null 2>&1 || true
            say "${SHUTDOWN_GRACE_SEC}초 뒤 인스턴스를 정지한다 (EBS는 남는다). 산출물 $OUT"
            sudo shutdown -h "+$(( (SHUTDOWN_GRACE_SEC + 59) / 60 ))" "e8cause done" >/dev/null 2>&1
        else
            say "EC2가 아니라 정지하지 않는다"
        fi
    fi
}
trap finish EXIT

# ── 연속 색인 ────────────────────────────────────────────────────────────────
app_start
mark "앱 기동 — 연속 색인 시작(1회차)"

T_START=$(date +%s)
DEADLINE=$(awk -v s="$T_START" -v h="$MAX_HOURS" 'BEGIN{print int(s + h*3600)}')
PHASE=baseline          # baseline → watch → observe-1/2/3 → settled
STAGE=0                 # 마지막으로 한 개입 (0 없음, 1 앱, 2 DB, 3 REINDEX)
BASELINE=""
declare -a BASE_SAMPLES=() OBS_SAMPLES=()
DEGRADE_RUN=0; STALL=0; CRASHES=0; VALID_SKIP=0
PREV_C=$(chunks); PREV_S=$(psql_q "SELECT count(DISTINCT (source_type, source_id)) FROM document_chunks;")
PREV_INS_CALLS=0; PREV_INS_MS=0; PREV_T=$(date +%s)

intervene() {   # $1 = 1|2|3
    STAGE=$1
    app_logs_save "before-stage$1"; gc_log_save "before-stage$1"
    case "$1" in
        1)  mark "개입 1: 앱 재시작 직전" before-app-restart
            app_stop; app_start
            mark "개입 1: 앱 재시작 완료 — 2회차" ;;
        2)  mark "개입 2: DB 재시작 직전" before-db-restart
            app_stop
            dc restart "$SVC_DB" >/dev/null 2>&1
            for _ in $(seq 1 60); do psql_q "SELECT 1;" >/dev/null 2>&1 && break; sleep 2; done
            app_start
            mark "개입 2: DB 재시작 완료 — 3회차" ;;
        3)  mark "개입 3: REINDEX 직전" before-reindex
            app_stop
            local t0=$(date +%s)
            dc exec -T "$SVC_DB" psql -U "$DB_USER" -d "$DB_NAME" -X -q \
                -c "SET maintenance_work_mem='256MB'; SET max_parallel_maintenance_workers=0; REINDEX (VERBOSE) INDEX $HNSW_INDEX;" \
                > "$RAW/reindex.log" 2>&1
            say "REINDEX $(( $(date +%s) - t0 ))초"
            app_start
            mark "개입 3: REINDEX 완료($(( $(date +%s) - t0 ))s) — 4회차" after-reindex ;;
    esac
    PHASE="observe-$1"; OBS_SAMPLES=(); VALID_SKIP=0; STALL=0
}

while :; do
    sleep "$SAMPLE_SEC"
    NOW=$(date +%s); ELAPSED=$(( NOW - T_START ))

    ROW=$(psql_q "SELECT
        (SELECT count(*) FROM document_chunks),
        (SELECT count(DISTINCT (source_type, source_id)) FROM document_chunks),
        (SELECT coalesce(sum(calls),0) FROM pg_stat_statements WHERE query ILIKE 'insert into document_chunks%'),
        (SELECT coalesce(round(sum(total_exec_time)::numeric,1),0) FROM pg_stat_statements WHERE query ILIKE 'insert into document_chunks%'),
        (SELECT coalesce(sum(calls),0) FROM pg_stat_statements),
        (SELECT coalesce(round(sum(total_exec_time)::numeric),0) FROM pg_stat_statements),
        (SELECT num_timed FROM pg_stat_checkpointer),
        (SELECT num_requested FROM pg_stat_checkpointer),
        (SELECT buffers_written FROM pg_stat_checkpointer),
        (SELECT round(wal_bytes/1048576.0,1) FROM pg_stat_wal),
        (SELECT n_dead_tup FROM pg_stat_user_tables WHERE relname='document_chunks'),
        (SELECT n_ins_since_vacuum FROM pg_stat_user_tables WHERE relname='document_chunks'),
        (SELECT autovacuum_count FROM pg_stat_user_tables WHERE relname='document_chunks'),
        (SELECT coalesce(to_char(last_autovacuum AT TIME ZONE 'UTC','HH24:MI:SS'),'') FROM pg_stat_user_tables WHERE relname='document_chunks'),
        (SELECT count(*) FROM pg_stat_progress_vacuum),
        round(pg_relation_size('document_chunks')/1048576.0),
        round(pg_relation_size('$HNSW_INDEX')/1048576.0),
        (SELECT count(*) FROM pg_stat_activity WHERE backend_type='client backend'),
        (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock');")
    if [[ -z "$ROW" ]]; then
        printf '%s,%s,%s,%s,,,,,,db-unreachable\n' "$NOW" "$(date -u +%FT%TZ)" "$ELAPSED" "$PHASE" >> "$RATE"
        say "DB 응답 없음"
        continue
    fi
    IFS='|' read -r CH SR INS_CALLS INS_MS ALL_CALLS ALL_MS CK_T CK_R CK_B WAL_MB N_DEAD N_INS_VAC AV_CNT LAST_AV VAC_RUN HEAP_MB HNSW_MB BACKENDS LOCKW <<< "$ROW"
    STATS=$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null)
    CPU_APP=$(awk '/dockin-app/{print $2}' <<< "$STATS"); CPU_DB=$(awk '/dockin-db/{print $2}' <<< "$STATS")
    CPU_TEI=$(awk '/dockin-embedding/{print $2}' <<< "$STATS"); MEM_APP=$(awk '/dockin-app/{print $3}' <<< "$STATS")

    GAP=$(( NOW - PREV_T )); DC=$(( CH - PREV_C )); DS=$(( SR - PREV_S ))
    D_INS_CALLS=$(( INS_CALLS - PREV_INS_CALLS )); D_INS_MS=$(awk -v a="$INS_MS" -v b="$PREV_INS_MS" 'BEGIN{printf "%.1f", a-b}')
    MSS=""; (( DS > 0 )) && MSS=$(awk -v e="$GAP" -v n="$DS" 'BEGIN{printf "%.1f", 1000*e/n}')
    INS_PER=""; (( D_INS_CALLS > 0 )) && INS_PER=$(awk -v m="$D_INS_MS" -v n="$D_INS_CALLS" 'BEGIN{printf "%.3f", m/n}')
    APP=$(app_running && echo up || echo down)

    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
        "$NOW" "$(date -u +%FT%TZ)" "$ELAPSED" "$PHASE" "$CH" "$SR" "$DC" "$DS" "$MSS" "$APP" \
        "$INS_CALLS" "$INS_MS" "$D_INS_CALLS" "$D_INS_MS" "$INS_PER" "$ALL_CALLS" "$ALL_MS" \
        "$CK_T" "$CK_R" "$CK_B" "$WAL_MB" "$N_DEAD" "$N_INS_VAC" "$AV_CNT" "$LAST_AV" "$VAC_RUN" "$HEAP_MB" "$HNSW_MB" \
        "$BACKENDS" "$LOCKW" "$CPU_APP" "$CPU_DB" "$CPU_TEI" "$MEM_APP" >> "$RATE"
    say "[$PHASE] 청크 $CH (+$DC) 원본당 ${MSS:-—}ms | INSERT ${INS_PER:-—}ms/건 | ckpt $CK_T/$CK_R wal ${WAL_MB}MB | dead $N_DEAD vac $AV_CNT${VAC_RUN:+ run=$VAC_RUN} | hnsw ${HNSW_MB}MB | cpu app $CPU_APP db $CPU_DB tei $CPU_TEI | base ${BASELINE:-—}"
    PREV_C=$CH; PREV_S=$SR; PREV_T=$NOW; PREV_INS_CALLS=$INS_CALLS; PREV_INS_MS=$INS_MS

    # ── 끝·이상 판정: 앱 로그가 말하게 한다 ─────────────────────────────────
    END=$(app_index_end)
    if [[ "$END" == *완주* ]]; then
        mark "색인 완주 (앱 로그) — 남은 원본 없음" done; break
    fi
    if [[ "$END" == *"진행 중"* ]]; then
        mark "!! 앱이 '다른 색인 진행 중'으로 건너뛰었다 — 락 키 지우고 재기동" skipped
        app_logs_save "skipped$CRASHES"; app_stop; app_start; CRASHES=$((CRASHES+1)); STALL=0; continue
    fi
    if [[ -n "$END" ]] || ! app_running; then
        CRASHES=$((CRASHES+1))
        mark "!! 앱 색인 중단(${END:-컨테이너 down}) — 재기동 $CRASHES/$MAX_CRASH_RESTARTS" "crash$CRASHES"
        app_logs_save "crash$CRASHES"; gc_log_save "crash$CRASHES"
        (( CRASHES > MAX_CRASH_RESTARTS )) && { mark "!! 재기동 상한 — 끝낸다"; break; }
        app_stop; app_start; STALL=0; OBS_SAMPLES=(); VALID_SKIP=0; continue
    fi
    if (( DC > 0 )); then STALL=0; else STALL=$((STALL+1)); fi
    if (( STALL >= STALL_MIN )); then
        CRASHES=$((CRASHES+1))
        mark "!! 정체 ${STALL_MIN}분 — 앱은 살아 있는데 청크가 안 는다. 재기동 $CRASHES/$MAX_CRASH_RESTARTS" "stall$CRASHES"
        app_logs_save "stall$CRASHES"; gc_log_save "stall$CRASHES"
        (( CRASHES > MAX_CRASH_RESTARTS )) && { mark "!! 재기동 상한 — 끝낸다"; break; }
        app_stop; app_start; STALL=0; OBS_SAMPLES=(); VALID_SKIP=0; continue
    fi
    if (( NOW >= DEADLINE )); then
        mark "!! ${MAX_HOURS}시간 상한 — 원본이 남은 채로 끝낸다" deadline; break
    fi

    [[ -n "$MSS" ]] || continue     # 유효 표본(원본이 는 표본)만 아래로

    # ── 기준선 → 감시 → 개입/관찰 ──────────────────────────────────────────
    case "$PHASE" in
        baseline)
            if (( VALID_SKIP < BASELINE_SKIP )); then VALID_SKIP=$((VALID_SKIP+1)); continue; fi
            BASE_SAMPLES+=("$MSS")
            if (( ${#BASE_SAMPLES[@]} >= BASELINE_N )); then
                BASELINE=$(printf '%s\n' "${BASE_SAMPLES[@]}" | median)
                mark "기준선 확정 ${BASELINE}ms/원본 (유효 ${BASELINE_N}표본 중앙값)"
                PHASE=watch
            fi ;;
        watch)
            if awk -v m="$MSS" -v b="$BASELINE" -v f="$DEGRADE_FACTOR" 'BEGIN{exit !(m >= b*f)}'; then
                DEGRADE_RUN=$((DEGRADE_RUN+1))
            else
                DEGRADE_RUN=0
            fi
            if (( DEGRADE_RUN >= DEGRADE_N )); then
                mark "열화 감지 — ${DEGRADE_N}표본 연속 ≥ ${BASELINE}×${DEGRADE_FACTOR} (마지막 ${MSS}ms)" degraded
                DEGRADE_RUN=0
                intervene $(( STAGE + 1 ))
            fi ;;
        observe-*)
            if (( VALID_SKIP < OBSERVE_SKIP )); then VALID_SKIP=$((VALID_SKIP+1)); continue; fi
            OBS_SAMPLES+=("$MSS")
            if (( ${#OBS_SAMPLES[@]} >= OBSERVE_N )); then
                MED=$(printf '%s\n' "${OBS_SAMPLES[@]}" | median)
                case "$STAGE" in 1) WHAT="앱(JVM·풀·세션)";; 2) WHAT="DB 휘발 상태(버퍼·WAL 백로그·진행 중 autovacuum)";; 3) WHAT="HNSW 인덱스 상태";; esac
                if awk -v m="$MED" -v b="$BASELINE" -v f="$RECOVER_FACTOR" 'BEGIN{exit !(m < b*f)}'; then
                    mark "회복 — 개입 $STAGE 뒤 중앙값 ${MED}ms (기준선 $BASELINE) → 원인은 $WHAT" "recovered-stage$STAGE"
                    PHASE=settled
                else
                    mark "미회복 — 개입 $STAGE 뒤 중앙값 ${MED}ms (기준선 $BASELINE) → $WHAT 은 아니다" "not-recovered-stage$STAGE"
                    if (( STAGE >= 3 )); then
                        mark "셋 다 아님 — 앱·DB 재시작·REINDEX로 안 돌아온다. rate.csv의 INSERT ms/건과 TEI cpu를 본다"
                        PHASE=settled
                    else
                        intervene $(( STAGE + 1 ))
                    fi
                fi
            fi ;;
        settled) ;;   # 끝까지 기록만 한다. 다시 느려지면 rate.csv에 남는다
    esac
done

say "최종 코퍼스 $(chunks) 청크 (시작 $CORPUS_START) / 개입 $STAGE단계까지 / 재기동 $CRASHES회"
