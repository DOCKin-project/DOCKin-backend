#!/usr/bin/env bash
#
# E8 후속 — 15만 청크의 무릎이 shared_buffers 때문인가
#
#   밤 2(2026-08-13)가 빈 코퍼스에서 198,068청크까지 한 번에 색인하면서 이런 곡선을 냈다:
#
#     0~150k청크   63~65ms/원본   (완전히 평평)
#     150k~175k    69.3ms
#     175k~200k    93.4ms         (마지막 표본 144.9ms = 평평 구간의 2.4배)
#
#   점진적으로 무거워지는 모양이 아니라 **어딘가를 넘으면서 성질이 바뀌는 모양**이다.
#   유력한 후보가 shared_buffers 128MB다 -- 198,068청크의 벡터 원본만 384차원 × 4바이트로
#   약 304MB이고, 꺾이는 15만 청크가 벡터 약 230MB 부근이다.
#
#   **정황이지 증거가 아니다.** 크기 계산이 맞는 것과 그것이 원인인 것은 다르다.
#   가르는 방법은 하나뿐이다: 버퍼를 키우고 같은 자리에서 다시 재서 **빨라지는지** 본다.
#
# [왜 재색인이 필요 없는가]
#   밤 2의 코퍼스가 EBS에 198,068청크로 남아 있다. 여기서 **구멍을 파고**(가장 오래된
#   원본 몇천 건의 청크를 지우고) 그 구멍을 다시 메우게 하면, **약 19만 청크짜리 HNSW
#   그래프에 삽입하는 처리량**을 조건마다 몇 분 만에 잴 수 있다. 곡선을 처음부터 다시
#   그리는 데 3시간이 필요했지만, 곡선의 오른쪽 끝 한 점을 다시 찍는 데는 5분이면 된다.
#
# [조건이 넷인 이유 — 두 가지를 한 번에 바꾸게 되기 때문이다]
#   DB 컨테이너의 메모리 상한이 512M이라 shared_buffers를 1GB로 올리려면 **컨테이너 상한도
#   같이 올려야 한다.** 그러면 바뀐 것이 둘이 되어 어느 쪽이 효과를 냈는지 못 가른다 --
#   PostgreSQL은 shared_buffers 밖의 페이지를 OS 페이지 캐시에서 읽고, 그 캐시도 cgroup
#   메모리 안에서 잡히기 때문이다. 그래서 C를 넣는다.
#
#     A  shared_buffers 128MB / 컨테이너 512M   ← 기준선 (지금 그대로)
#     B  shared_buffers 1GB   / 컨테이너 2G     ← 둘 다 올림
#     C  shared_buffers 128MB / 컨테이너 2G     ← 메모리만 올림 (버퍼는 그대로)
#     D  shared_buffers 128MB / 컨테이너 512M   ← A 반복, 드리프트 통제군
#
#   읽는 법:
#     B만 빨라지면      → 원인은 shared_buffers다. 무릎은 오른쪽으로 움직인다
#     B와 C가 같이 빨라지면 → 원인은 버퍼가 아니라 **캐시에 쓸 메모리 총량**이다.
#                            처방이 postgresql.conf가 아니라 컨테이너 상한이 된다
#     셋 다 같으면      → 메모리 축이 아니다. 정황이 틀렸고 다른 후보를 찾아야 한다
#     D가 A와 다르면    → 스윕 도중 무언가 변한 것이고, 가운데 줄들을 읽을 수 없다
#
# [실행]
#   ./scripts/e8-shared-buffers.sh
#   WINDOW_SEC=60 CONDITIONS="A:128MB:512m B:1GB:2g" ./scripts/e8-shared-buffers.sh   # 연습 주행
#
set -euo pipefail
cd "$(dirname "$0")/.."

# ─────────────────────────────────────────────────────────── 설정
# 형식: 라벨:shared_buffers:컨테이너메모리
CONDITIONS=${CONDITIONS:-"A:128MB:512m B:1GB:2g C:128MB:2g D:128MB:512m"}
WINDOW_SEC=${WINDOW_SEC:-300}
WARMUP_CHUNKS=${WARMUP_CHUNKS:-50}
HOLE_SOURCES=${HOLE_SOURCES:-6000}     # 구멍 크기. 창 하나가 먹는 것보다 넉넉해야 한다
DIG_HOLE=${DIG_HOLE:-1}                # 0이면 이미 파인 구멍을 그대로 쓴다(중단 후 재개)

# 되돌릴 때의 VACUUM 옵션.
#
# [왜 INDEX_CLEANUP OFF가 기본인가] 2026-08-13 밤 3의 첫 시도가 여기서 죽었다 --
# `VACUUM (ANALYZE)` 하나가 **40분을 넘겼다.** 19만 행에 HNSW 인덱스가 붙어 있고
# maintenance_work_mem이 64MB, 컨테이너가 512M이라 인덱스를 통째로 훑는 비용이 그렇게 된다.
# 조건마다 이것을 붙이면 여덟 조건에 5시간이 VACUUM에만 간다.
#
# INDEX_CLEANUP OFF는 힙의 죽은 튜플만 정리하고 인덱스 청소를 건너뛴다. 대신 **죽은
# 인덱스 항목이 조건을 넘어 쌓이므로**, 뒤 조건일수록 그래프에 쓰레기가 많은 상태에서
# 삽입하게 된다. 그것이 상한 효과로 둔갑할 수 있다.
#
# **그래서 이 선택은 드리프트 통제군(D = A 반복) 위에서만 성립한다.** D가 A와 다르면
# 가운데 조건들을 읽지 않는다 -- 그 경우 이 스크립트는 "쟀지만 못 읽는다"를 남기며,
# 그것이 조용히 틀린 숫자보다 낫다.
VACUUM_OPTS=${VACUUM_OPTS:-"ANALYZE, INDEX_CLEANUP OFF"}
EMBED_WAIT_MAX=${EMBED_WAIT_MAX:-1200}
HEALTH_WAIT_MAX=${HEALTH_WAIT_MAX:-300}
COMPOSE_FILES=${COMPOSE_FILES:-"-f compose.yaml -f compose.gc.yaml"}
OUT_ROOT=${OUT_ROOT:-measure}
REFILL_WHEN_DONE=${REFILL_WHEN_DONE:-1}  # 끝나고 구멍을 다시 메운다(다음 실험이 완전한 코퍼스를 본다)
TEI_CPUS=${TEI_CPUS:-2.0}                # E1의 답. 이 실험 내내 고정한다

SVC_APP=dockin-app
SVC_DB=DOCKin-DB
SVC_TEI=dockin-embedding
DB_USER=${DB_USER:-root}
DB_NAME=${DB_NAME:-dockindb}

RUN_ID=$(date +%Y%m%dT%H%M%S)
OUT="$OUT_ROOT/e8buf-$RUN_ID"
CSV="$OUT/e8-shared-buffers.csv"
mkdir -p "$OUT/raw"

dc()  { docker compose $COMPOSE_FILES "$@"; }
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
die() { say "!! $*"; exit 1; }
psql_q() { dc exec -T "$SVC_DB" psql -U "$DB_USER" -d "$DB_NAME" -qtAX -c "$1" | tr -d '\r'; }
chunks() { psql_q "SELECT count(*) FROM document_chunks;"; }

# ─────────────────────────────────────────────────────────── 전제 확인
# E1이 배운 것을 그대로 가져온다 -- 멈춘 컨테이너에는 cgroup이 없고, 그것을 "cgroup을 못
# 찾는다"로 진단하면 처방이 정반대가 된다. 여기서는 DB와 TEI가 떠 있어야 시작할 수 있다.
for s in "$SVC_DB" "$SVC_TEI"; do
    [[ "$(docker inspect -f '{{.State.Running}}' "$(dc ps -q "$s" 2>/dev/null)" 2>/dev/null)" == "true" ]] \
        || die "'$s' 가 실행 중이 아니다. docker compose $COMPOSE_FILES up -d $SVC_DB $SVC_TEI"
done

DB_CID=$(dc ps -q "$SVC_DB")

# 컨테이너의 메모리 상한을 cgroup에서 직접 읽는다. docker update의 성공 여부를 믿지 않는다 --
# E1에서 "설정은 있는데 안 먹는다"를 잡아낸 것이 정확히 이 통제군이었다.
read_applied_mem() {
    local d
    for d in "/sys/fs/cgroup/system.slice/docker-${DB_CID}.scope" \
             "/sys/fs/cgroup/docker/${DB_CID}" \
             "/sys/fs/cgroup/memory/docker/${DB_CID}"; do
        [[ -r "$d/memory.max" ]] && { cat "$d/memory.max"; return 0; }
        [[ -r "$d/memory.limit_in_bytes" ]] && { cat "$d/memory.limit_in_bytes"; return 0; }
    done
    echo "unknown"
}

# 사람이 읽는 단위를 바이트로. 비교는 바이트끼리 한다.
to_bytes() {
    awk -v v="$1" 'BEGIN{
        n = v + 0
        if (v ~ /[gG]/) n *= 1024*1024*1024
        else if (v ~ /[mM]/) n *= 1024*1024
        printf "%.0f", n
    }'
}

TEI_CID=$(dc ps -q "$SVC_TEI")
docker update --cpus "$TEI_CPUS" "$TEI_CID" >/dev/null \
    || die "TEI 상한을 $TEI_CPUS 로 고정하지 못했다"
say "TEI 상한 $TEI_CPUS 고정 — 이 실험은 DB 축만 움직인다"

CORPUS_FULL=$(chunks)
say "현재 코퍼스: $CORPUS_FULL 청크"

# ─────────────────────────────────────────────────────────── 환경 기록
{
    echo "# E8 후속(shared_buffers) 측정 환경 — $RUN_ID"
    echo
    echo "| | |"
    echo "|---|---|"
    echo "| 인스턴스 | $(curl -s --max-time 2 http://169.254.169.254/latest/meta-data/instance-type 2>/dev/null || echo '알 수 없음') |"
    echo "| vCPU / 메모리 | $(nproc) / $(free -g | awk '/^Mem:/{print $2"GB"}') |"
    echo "| 커널 | $(uname -r) |"
    echo "| 창 길이 | ${WINDOW_SEC}s (워밍업 ${WARMUP_CHUNKS}청크 이후) |"
    echo "| 조건 | $CONDITIONS |"
    echo "| TEI 상한 | $TEI_CPUS (고정) |"
    echo "| 구멍 크기 | $HOLE_SOURCES 원본 |"
    echo "| 시작 시점 코퍼스 | $CORPUS_FULL 청크 |"
    echo "| CPU 지문 | $( { t0=$(date +%s%N); awk 'BEGIN{x=0; for(i=0;i<20000000;i++) x+=i; if(x<0) print x}' >/dev/null 2>&1; t1=$(date +%s%N); echo $(( (t1-t0)/1000000 )); } ) ms |"
} > "$OUT/env.md"
say "환경 기록 → $OUT/env.md"

echo "seq,label,shared_buffers_req,shared_buffers_applied,mem_req,mem_applied_bytes,window_s,corpus_at_open,chunks,sources,ms_per_chunk,ms_per_source,note" > "$CSV"

# ─────────────────────────────────────────────────────────── 구멍 파기
# 가장 오래된 원본 쪽에 판다. indexAll()이 lastId=0부터 훑으므로 **첫 페이지에서 바로
# 일거리를 만나고**, 건너뛰기 구간을 기다리는 시간이 조건마다 몇 분씩 사라진다.
# 구멍은 한 번만 파고, 조건이 끝날 때마다 그 조건이 메운 만큼만 되지운다.
if [[ "$DIG_HOLE" == "1" ]]; then
    say "구멍 파기 — 가장 오래된 $HOLE_SOURCES 원본의 청크를 지운다"
    psql_q "DELETE FROM document_chunks WHERE (source_type, source_id) IN (
              SELECT source_type, source_id FROM document_chunks
              WHERE source_type = 'WORK_LOG'
              GROUP BY 1, 2 ORDER BY 2 LIMIT $HOLE_SOURCES);" >/dev/null
    psql_q "VACUUM ($VACUUM_OPTS) document_chunks;" >/dev/null
    CORPUS_HOLED=$(chunks)
    say "구멍 완료: $CORPUS_FULL → $CORPUS_HOLED 청크 (-$((CORPUS_FULL - CORPUS_HOLED)))"
else
    CORPUS_HOLED=$CORPUS_FULL
    say "구멍 파기 건너뜀(DIG_HOLE=0) — 이미 파인 구멍을 그대로 쓴다: $CORPUS_HOLED 청크"
fi

# ─────────────────────────────────────────────────────────── 조건 반복
SEQ=0
for COND in $CONDITIONS; do
    SEQ=$((SEQ + 1))
    LABEL="${COND%%:*}"; REST="${COND#*:}"
    SB="${REST%%:*}"; MEM="${REST#*:}"
    say "── [$SEQ] $LABEL — shared_buffers=$SB / 컨테이너 $MEM ───────────────"

    dc stop "$SVC_APP" >/dev/null 2>&1 || true

    # 1) 컨테이너 메모리 상한. 컨테이너를 다시 만들지 않고 살아 있는 것의 상한만 바꾼다
    #    (E1이 --cpus로 한 것과 같은 이유 -- recreate하면 조건마다 초기화가 끼어든다).
    #    --memory-swap을 같은 값으로 주지 않으면 스왑이 상한보다 작다며 거부당한다.
    docker update --memory "$MEM" --memory-swap "$MEM" "$DB_CID" >/dev/null \
        || die "컨테이너 메모리 상한을 $MEM 로 바꾸지 못했다"

    # 2) shared_buffers. ALTER SYSTEM은 postgresql.auto.conf에 쓰고 재시작해야 먹는다.
    #    compose의 command에 -c shared_buffers가 없기 때문에 이 경로가 유효하다
    #    (커맨드라인 인자가 있으면 auto.conf를 이긴다 -- 아래 검증이 그것까지 잡는다).
    psql_q "ALTER SYSTEM SET shared_buffers = '$SB';" >/dev/null
    dc restart "$SVC_DB" >/dev/null
    for _ in $(seq 1 60); do
        psql_q "SELECT 1;" >/dev/null 2>&1 && break
        sleep 2
    done

    # 3) 통제군 ① — 조건이 실제로 걸렸는지를 조건 자체가 증명한다.
    SB_APPLIED=$(psql_q "SHOW shared_buffers;")
    MEM_APPLIED=$(read_applied_mem)
    say "  적용 확인: shared_buffers=$SB_APPLIED / memory.max=$MEM_APPLIED"
    [[ "$(to_bytes "$SB_APPLIED")" == "$(to_bytes "$SB")" ]] \
        || die "shared_buffers가 안 걸렸다: 요청 $SB / 실제 $SB_APPLIED"
    [[ "$MEM_APPLIED" == "unknown" || "$MEM_APPLIED" == "$(to_bytes "$MEM")" ]] \
        || die "컨테이너 메모리가 안 걸렸다: 요청 $MEM($(to_bytes "$MEM")) / 실제 $MEM_APPLIED"
    printf 'shared_buffers %s\nmemory.max %s\n' "$SB_APPLIED" "$MEM_APPLIED" > "$OUT/raw/seq${SEQ}-${LABEL}-applied.txt"

    # 4) 앱을 올린다 = 색인 시작. 구멍이 있으므로 첫 페이지에서 바로 일이 잡힌다.
    T0=$(psql_q "SELECT now();")
    dc up -d "$SVC_APP" >/dev/null 2>&1
    BASE=$(chunks); waited=0
    while :; do
        sleep 10; waited=$((waited + 10))
        NOW=$(chunks)
        (( NOW - BASE >= WARMUP_CHUNKS )) && break
        (( waited >= EMBED_WAIT_MAX )) && {
            say "  !! ${EMBED_WAIT_MAX}초 안에 임베딩이 시작되지 않았다 — 이 조건은 버린다"
            echo "$SEQ,$LABEL,$SB,$SB_APPLIED,$MEM,$MEM_APPLIED,0,,0,0,,,embedding-never-started" >> "$CSV"
            break
        }
    done
    (( waited >= EMBED_WAIT_MAX )) && continue

    # 5) 창을 연다. 여는 시점의 코퍼스를 함께 적는다 -- 이 실험은 "19만 청크 부근"이
    #    조건이므로, 그 자리가 조건마다 얼마나 같은지가 숫자와 같은 값어치를 갖는다.
    CORPUS_AT_OPEN=$(chunks)
    read -r C0 S0 <<< "$(psql_q "SELECT count(*), count(DISTINCT (source_type, source_id))
                                 FROM document_chunks WHERE created_at > '$T0';" | tr '|' ' ')"
    W_START=$(date +%s)
    sleep "$WINDOW_SEC"
    W_END=$(date +%s)
    read -r C1 S1 <<< "$(psql_q "SELECT count(*), count(DISTINCT (source_type, source_id))
                                 FROM document_chunks WHERE created_at > '$T0';" | tr '|' ' ')"

    # 통제군 ①-b — 창을 닫을 때 조건을 한 번 더 읽는다.
    # 앱을 올리는 `up -d`가 depends_on 때문에 DB를 다시 만들면 docker update로 준 상한이
    # 조용히 원복된다. 그러면 "조건을 걸고 쟀다"는 문장이 창 안에서 거짓이 되는데,
    # 창을 열 때 한 번만 확인해서는 그것을 못 잡는다.
    SB_CLOSE=$(psql_q "SHOW shared_buffers;")
    MEM_CLOSE=$(read_applied_mem)
    DRIFT=""
    [[ "$SB_CLOSE" == "$SB_APPLIED" && "$MEM_CLOSE" == "$MEM_APPLIED" ]] \
        || DRIFT="condition-drifted-mid-window($SB_APPLIED->$SB_CLOSE,$MEM_APPLIED->$MEM_CLOSE)"
    [[ -n "$DRIFT" ]] && say "  !! 창 안에서 조건이 바뀌었다: $DRIFT"
    printf 'open  shared_buffers=%s memory.max=%s\nclose shared_buffers=%s memory.max=%s\n' \
        "$SB_APPLIED" "$MEM_APPLIED" "$SB_CLOSE" "$MEM_CLOSE" > "$OUT/raw/seq${SEQ}-${LABEL}-applied.txt"

    ELAPSED=$((W_END - W_START))
    DC_=$((C1 - C0)); DS=$((S1 - S0))
    if (( DC_ > 0 )); then MSC=$(awk -v e="$ELAPSED" -v n="$DC_" 'BEGIN{printf "%.1f", 1000*e/n}'); else MSC=""; fi
    if (( DS  > 0 )); then MSS=$(awk -v e="$ELAPSED" -v n="$DS"  'BEGIN{printf "%.1f", 1000*e/n}'); else MSS=""; fi

    say "  창 ${ELAPSED}s @ ${CORPUS_AT_OPEN}청크 — 청크 +$DC_ / 원본 +$DS → 원본당 ${MSS:-?}ms"
    echo "$SEQ,$LABEL,$SB,$SB_APPLIED,$MEM,$MEM_APPLIED,$ELAPSED,$CORPUS_AT_OPEN,$DC_,$DS,$MSC,$MSS,$DRIFT" >> "$CSV"

    # 6) 되돌리기 — 이번 조건이 메운 만큼만 지워 구멍을 원래 크기로 되돌린다.
    #    지우지 않으면 뒤 조건일수록 구멍이 작아져 코퍼스 위치가 조건마다 달라진다.
    dc stop "$SVC_APP" >/dev/null 2>&1 || true
    psql_q "DELETE FROM document_chunks WHERE created_at > '$T0';" >/dev/null
    psql_q "VACUUM ($VACUUM_OPTS) document_chunks;" >/dev/null
    say "  되돌림: $(chunks) 청크"
done

# ─────────────────────────────────────────────────────────── 정리
# 기준 상태로 되돌린다. 이 실험이 남긴 설정이 다음 실험의 조건이 되면 안 된다.
say "기준 상태로 복귀 — shared_buffers 기본값 / 컨테이너 512m"
psql_q "ALTER SYSTEM RESET shared_buffers;" >/dev/null
docker update --memory 512m --memory-swap 512m "$DB_CID" >/dev/null || true
dc restart "$SVC_DB" >/dev/null
for _ in $(seq 1 60); do psql_q "SELECT 1;" >/dev/null 2>&1 && break; sleep 2; done
say "복귀 확인: shared_buffers=$(psql_q 'SHOW shared_buffers;') / memory.max=$(read_applied_mem)"

if [[ "$REFILL_WHEN_DONE" == "1" ]]; then
    say "구멍 메우기 — 다음 실험이 완전한 코퍼스(198k)를 보게 한다"
    dc up -d "$SVC_APP" >/dev/null 2>&1
    stall=0; last=$(chunks)
    while (( stall < 6 )); do
        sleep 30
        now=$(chunks)
        if (( now > last )); then stall=0; else stall=$((stall + 1)); fi
        last=$now
    done
    say "메우기 종료: $(chunks) 청크 (시작 시점 $CORPUS_FULL)"
fi

say "완료. CSV=$CSV  환경=$OUT/env.md"
column -s, -t "$CSV"
