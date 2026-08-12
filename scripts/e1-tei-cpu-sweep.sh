#!/usr/bin/env bash
#
# E1 — TEI CPU 상한 스윕 (docs/AWS-MEASUREMENT-PLAN.md 3절)
#
#   같은 코퍼스 위치에서 TEI 상한만 바꿔 가며 색인 처리량을 잰다.
#   6-11이 상한 있음/없음 두 점을 잰 것의 연장이며, 이번에는 2.0~6.0을 1.0 간격으로 훑는다.
#
# [실행]
#   ./scripts/e1-tei-cpu-sweep.sh
#   WINDOW_SEC=60 CONDITIONS="2.0 3.0" ./scripts/e1-tei-cpu-sweep.sh    # 연습 주행
#
# [이 스크립트가 지키려는 것 셋]
#
#  1. 코퍼스 위치를 고정한다.
#     색인 처리량은 색인이 "진행 중일 때만" 잴 수 있는데, 조건마다 코퍼스가 커지면
#     E8(코퍼스 크기 축)이 E1(상한 축)에 섞여 둘 다 못 읽는 것이 된다.
#     그래서 조건마다 위치 P에서 잰 뒤 **그 슬라이스만 지워 P로 되돌린다.**
#
#     되돌리는 것에는 두 번째 이득이 있다 -- 모든 조건이 **같은 문서를 다시 색인한다.**
#     TEI 시간은 청크 길이에 크게 좌우되므로(149자 46ms vs 317자 153ms),
#     조건마다 다른 문서를 색인하면 내용 차이가 상한 효과로 둔갑한다.
#
#  2. 조건이 실제로 걸렸는지를 조건 자체가 증명하게 한다.
#     docker update의 성공 여부를 믿지 않고 **cgroup의 cpu.max를 직접 읽어** 확인한다.
#     이 저장소가 여러 번 잡아온 "설정은 있는데 안 먹는다"가 여기서도 가능하다.
#
#  3. 드리프트를 검출한다.
#     CONDITIONS의 마지막 2.0은 오타가 아니라 통제군이다. 첫 2.0과 값이 다르면
#     스윕 도중에 무언가가 변한 것이고, 그러면 가운데 줄들의 배수를 읽을 수 없다.
#
# [왜 compose.gc.yaml을 겹쳐 쓰는가]
#   그 파일이 seed 프로파일과 rag.indexing.on-startup=true를 켜므로 **앱을 띄우는 것이
#   곧 색인 트리거**가 된다(기본 cron은 매일 03:00이라 쓸 수 없다).
#   6-11도 GC 로깅을 켠 채로 쟀으므로 절대값 비교 가능성도 함께 유지된다.
#
set -euo pipefail

# ─────────────────────────────────────────────────────────── 설정
CONDITIONS=${CONDITIONS:-"2.0 3.0 4.0 5.0 6.0 2.0"}   # 마지막 2.0은 드리프트 통제군
WINDOW_SEC=${WINDOW_SEC:-300}                          # 6-11의 305s와 같은 자리
WARMUP_CHUNKS=${WARMUP_CHUNKS:-50}                     # 이만큼 늘어난 뒤에 창을 연다
EMBED_WAIT_MAX=${EMBED_WAIT_MAX:-2400}                 # 건너뛰기 구간 대기 상한(초)
HEALTH_WAIT_MAX=${HEALTH_WAIT_MAX:-300}
COMPOSE_FILES=${COMPOSE_FILES:-"-f compose.yaml -f compose.gc.yaml"}
OUT_ROOT=${OUT_ROOT:-measure}
RESTORE_CPUS=${RESTORE_CPUS:-2.0}                      # 끝나고 되돌릴 값(compose.yaml의 값)
SHUTDOWN_WHEN_DONE=${SHUTDOWN_WHEN_DONE:-0}
S3_RESULT_URI=${S3_RESULT_URI:-}                       # 예: s3://버킷/dockin-measure/ (비우면 로컬만)

SVC_APP=dockin-app
SVC_TEI=dockin-embedding
SVC_DB=DOCKin-DB          # 서비스 키는 대문자다. 컨테이너 이름(dockin-db)과 다르다
SVC_REDIS=dockin-redis
SVC_NGINX=dockin-nginx
ALL_SVCS=("$SVC_NGINX" "$SVC_APP" "$SVC_REDIS" "$SVC_DB" "$SVC_TEI")

DB_USER=${DB_USER:-root}
DB_NAME=${DB_NAME:-dockindb}

RUN_ID=$(date +%Y%m%dT%H%M%S)
OUT="$OUT_ROOT/e1-$RUN_ID"
CSV="$OUT/e1-sweep.csv"
LOG="$OUT/run.log"

# ─────────────────────────────────────────────────────────── 유틸
dc()  { docker compose $COMPOSE_FILES "$@"; }
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$LOG"; }
die() { printf '\n[중단] %s\n' "$*" | tee -a "$LOG" >&2; exit 1; }

psql_q() {
    dc exec -T "$SVC_DB" psql -U "$DB_USER" -d "$DB_NAME" -qtAX -c "$1" | tr -d '\r'
}

# -qa 로 물어야 한다. -q 는 실행 중인 것만 돌려주는데, 이 스크립트는 조건마다 앱을
# 내렸다 올리므로 "멈춰 있지만 존재하는" 앱의 ID가 계속 필요하다. 부트스트랩도 앱을
# 내려둔 채 끝나므로 -q 였다면 첫 줄에서 "떠 있지 않다"로 죽는다.
cid() { dc ps -qa "$1" 2>/dev/null | head -1; }

# EC2 인스턴스 타입. IMDSv2 → 실패하면 n/a. 측정 조건에 적어야 하는 값이라 조용히 비우지 않는다.
instance_type() {
    local t
    t=$(curl -sf --max-time 2 -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' \
        http://169.254.169.254/latest/api/token 2>/dev/null) || { echo "n/a"; return; }
    curl -sf --max-time 2 -H "X-aws-ec2-metadata-token: $t" \
        http://169.254.169.254/latest/meta-data/instance-type 2>/dev/null || echo "n/a"
}

# 컨테이너의 cgroup 디렉터리. 드라이버(systemd/cgroupfs)와 버전(v1/v2)이 환경마다 달라
# 넷을 모두 시도한다. 컨테이너 안에서 읽지 않는 이유는 TEI 이미지에 셸이 없을 수 있어서다.
cgroup_dir() {
    local c="$1" p
    for p in "/sys/fs/cgroup/system.slice/docker-${c}.scope" \
             "/sys/fs/cgroup/docker/${c}" \
             "/sys/fs/cgroup/cpu/docker/${c}" \
             "/sys/fs/cgroup/cpu,cpuacct/docker/${c}"; do
        [[ -r "$p/cpu.stat" ]] && { echo "$p"; return 0; }
    done
    return 1
}

# "nr_periods nr_throttled". 상한이 없으면 nr_periods가 0이고, 그것도 정보다.
#
# cgroup 디렉터리를 못 찾은 경우(CG[]가 비어 있음) 0을 돌려주고, 그러면 비율이 n/a가 된다.
# Docker Desktop이 그 경우다 -- 컨테이너가 별도 VM에서 돌아 호스트의 /sys/fs/cgroup에
# 보이지 않는다. 즉 **연습 주행에서는 통제군을 못 본다.** 본 측정은 EC2(리눅스 네이티브)라
# 정상적으로 읽히며, 그 차이를 ALLOW_NO_CGROUP이 명시적으로 갈라 준다.
read_throttle() {
    local d="$1"
    [[ -n "$d" && -r "$d/cpu.stat" ]] || { echo "0 0"; return; }
    awk '/^nr_periods/{p=$2} /^nr_throttled/{t=$2} END{printf "%d %d", p+0, t+0}' "$d/cpu.stat"
}

# 실제로 걸린 상한. cgroup v2는 cpu.max("QUOTA PERIOD" 또는 "max PERIOD"),
# v1은 cpu.cfs_quota_us / cpu.cfs_period_us다. 값을 cpus 단위로 환산해 돌려준다.
read_applied_cpus() {
    local d="$1" q p
    if [[ -r "$d/cpu.max" ]]; then
        read -r q p < "$d/cpu.max"
        [[ "$q" == "max" ]] && { echo "unlimited"; return; }
    elif [[ -r "$d/cpu.cfs_quota_us" ]]; then
        q=$(cat "$d/cpu.cfs_quota_us"); p=$(cat "$d/cpu.cfs_period_us")
        [[ "$q" == "-1" ]] && { echo "unlimited"; return; }
    else
        echo "unknown"; return
    fi
    awk -v q="$q" -v p="$p" 'BEGIN{printf "%.2f", q/p}'
}

# 결과를 S3로 올린다. 실패하면 0이 아닌 값을 돌려주고, 호출자는 그때 인스턴스를 끄지 않는다.
#
# [왜 옵션인가] 측정 산출물을 어느 버킷에 둘지는 이 스크립트가 정할 문제가 아니다.
# 앱이 쓰는 서비스 버킷(S3_BUCKET_NAME)에 섞으면 운영 데이터와 측정 부산물이 한곳에 쌓인다.
#
# [자격증명] 앱의 AWS_ACCESS_KEY를 재사용하지 말고 인스턴스 역할(IAM role)로 준다.
# 밤새 도는 EC2에 장기 키를 올려두는 것과 역할을 붙이는 것은 위험이 다르다.
upload_results() {
    [[ -n "$S3_RESULT_URI" ]] || return 0
    command -v aws >/dev/null || { say "!! aws CLI가 없어 업로드하지 못했다"; return 1; }

    local dest="${S3_RESULT_URI%/}/e1-$RUN_ID/"
    say "S3 업로드 → $dest"
    aws s3 cp "$OUT" "$dest" --recursive --only-show-errors || {
        say "!! S3 업로드 실패"; return 1; }

    # 올렸다고 적지 않고 되읽어 확인한다. 이 저장소가 알림을 일부러 깨뜨려 확인한 것과 같은 자리다.
    local n
    n=$(aws s3 ls "$dest" --recursive | wc -l)
    (( n > 0 )) || { say "!! 업로드 후 조회에서 0개 — 올라가지 않았다"; return 1; }
    say "업로드 확인: $n개 객체"
}

set_tei_cpus() {
    local c="$1" id="$2"
    if ! docker update --cpus="$c" "$id" >/dev/null 2>&1; then
        say "  docker update --cpus 실패 → cpu-quota로 재시도"
        local q; q=$(awk -v c="$c" 'BEGIN{printf "%d", (c<=0 ? -1 : c*100000)}')
        docker update --cpu-period=100000 --cpu-quota="$q" "$id" >/dev/null \
            || die "TEI 상한을 $c 로 바꾸지 못했다"
    fi
}

wait_healthy() {
    local svc="$1" id waited=0 st
    id=$(cid "$svc")
    [[ -n "$id" ]] || die "$svc 컨테이너를 찾지 못했다"
    while (( waited < HEALTH_WAIT_MAX )); do
        st=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$id")
        [[ "$st" == "healthy" || "$st" == "none" ]] && return 0
        sleep 5; waited=$((waited+5))
    done
    die "$svc 가 ${HEALTH_WAIT_MAX}초 안에 healthy가 되지 않았다"
}

# ─────────────────────────────────────────────────────────── 사전 점검
mkdir -p "$OUT/raw"
: > "$LOG"
say "E1 스윕 시작 — run=$RUN_ID"

command -v docker >/dev/null || die "docker가 없다"
[[ -f compose.yaml ]] || die "저장소 루트에서 실행해야 한다"

for s in "${ALL_SVCS[@]}"; do
    [[ -n "$(cid "$s")" ]] \
        || die "서비스 '$s' 의 컨테이너가 아예 없다. 먼저 docker compose $COMPOSE_FILES up -d"
done

ALLOW_NO_CGROUP=${ALLOW_NO_CGROUP:-0}
declare -A CID CG
for s in "${ALL_SVCS[@]}"; do
    CID[$s]=$(cid "$s")
    if ! CG[$s]=$(cgroup_dir "${CID[$s]}"); then
        CG[$s]=""
        # 통제군 ①(nr_throttled)이 없으면 "조건이 실제로 걸렸는가"를 데이터가 증명하지 못한다.
        # 본 측정에서는 중단 사유이고, 연습 주행에서만 명시적으로 넘긴다.
        [[ "$ALLOW_NO_CGROUP" == "1" ]] \
            || die "'$s' 의 cgroup 디렉터리를 찾지 못했다. 통제군이 사라지므로 본 측정은 여기서 멈춘다.
       흐름만 확인하려면 ALLOW_NO_CGROUP=1 로 다시 실행한다 (스로틀 열은 전부 n/a가 된다)."
        say "!! '$s' cgroup 없음 — 스로틀 열이 n/a가 된다 (ALLOW_NO_CGROUP=1)"
    fi
done

# 앱은 멈춰 있어도 된다(이 스크립트가 올린다). 다만 DB와 임베딩 서버는 떠 있어야 한다 --
# 없으면 창을 열어놓고 아무것도 안 늘어나는 것을 임베딩이 느린 것으로 읽게 된다.
for s in "$SVC_DB" "$SVC_TEI"; do
    [[ "$(docker inspect -f '{{.State.Running}}' "${CID[$s]}" 2>/dev/null)" == "true" ]] \
        || die "'$s' 가 실행 중이 아니다. docker compose $COMPOSE_FILES up -d $SVC_DB $SVC_TEI"
done

HOST_CPUS=$(nproc)
OTHERS=$(awk 'BEGIN{s=0}{s+=$1}END{printf "%.2f", s}' <<< "$(
    for s in "$SVC_NGINX" "$SVC_APP" "$SVC_REDIS" "$SVC_DB"; do
        docker inspect -f '{{.HostConfig.NanoCpus}}' "${CID[$s]}" \
            | awk '{printf "%.2f\n", $1/1000000000}'
    done)")
say "호스트 $HOST_CPUS vCPU / TEI 외 컨테이너 상한 합 $OTHERS"
say "→ 포화 경계: TEI $(awk -v h="$HOST_CPUS" -v o="$OTHERS" 'BEGIN{printf "%.1f", h-o}') 이상은 스케줄러가 실효 상한을 정한다 (계획 2-1)"

# ─────────────────────────────────────────────────────────── 환경 기록
# 숫자보다 이 파일이 먼저다. 조건을 안 적은 측정은 나중에 읽을 수 없다.
{
    echo "# E1 측정 환경 — $RUN_ID"
    echo
    echo "| | |"
    echo "|---|---|"
    echo "| 인스턴스 | $(instance_type) |"
    echo "| vCPU / 메모리 | $HOST_CPUS / $(free -g | awk '/^Mem:/{print $2"GB"}') |"
    echo "| 커널 | $(uname -r) |"
    echo "| docker | $(docker --version | sed 's/,.*//') |"
    echo "| compose 파일 | \`$COMPOSE_FILES\` |"
    echo "| TEI 외 상한 합 | $OTHERS cpus |"
    echo "| shared_buffers | $(psql_q 'SHOW shared_buffers;') |"
    echo "| work_mem | $(psql_q 'SHOW work_mem;') |"
    echo "| maintenance_work_mem | $(psql_q 'SHOW maintenance_work_mem;') |"
    echo "| 창 길이 | ${WINDOW_SEC}s (워밍업 ${WARMUP_CHUNKS}청크 이후) |"
    echo "| 조건 | $CONDITIONS |"
    echo "| 시작 시점 코퍼스 | $(psql_q 'SELECT count(*) FROM document_chunks;') 청크 |"
} > "$OUT/env.md"
say "환경 기록 → $OUT/env.md"

echo "seq,cpus_requested,cpus_applied,oversubscribed,window_s,chunks,sources,ms_per_chunk,ms_per_source,tei_throttle_pct,app_throttle_pct,db_throttle_pct,redis_throttle_pct,nginx_throttle_pct,corpus_at_start,note" > "$CSV"

# ─────────────────────────────────────────────────────────── 스윕
SEQ=0
for C in $CONDITIONS; do
    SEQ=$((SEQ+1))
    say "───── 조건 $SEQ: TEI cpus=$C"

    # 1) 앱을 내린다. 진행 중이던 트랜잭션은 롤백되고 커밋된 페이지만 남는다(해시 기반 멱등).
    dc stop "$SVC_APP" >/dev/null 2>&1 || true

    # 2) 앱이 멈춘 뒤에 P를 읽는다. 이 순서가 아니면 읽는 사이에 행이 더 들어와
    #    지우지 못하는 잔여가 남는다.
    T0=$(psql_q "SELECT coalesce(max(created_at), TIMESTAMP '-infinity')::text FROM document_chunks;")
    CORPUS_AT_START=$(psql_q "SELECT count(*) FROM document_chunks;")
    say "  P = $CORPUS_AT_START 청크 (마커 $T0)"

    # 3) 상한을 바꾸고 cgroup에서 실제 값을 확인한다.
    set_tei_cpus "$C" "${CID[$SVC_TEI]}"
    APPLIED=$(read_applied_cpus "${CG[$SVC_TEI]}")
    say "  적용된 상한(cgroup): $APPLIED"
    if [[ "$APPLIED" != "unlimited" && "$APPLIED" != "unknown" ]]; then
        awk -v a="$APPLIED" -v c="$C" 'BEGIN{exit (a-c<0.01 && c-a<0.01) ? 0 : 1}' \
            || die "요청 $C 인데 cgroup에는 $APPLIED 가 걸렸다"
    fi
    OVER=$(awk -v c="$C" -v o="$OTHERS" -v h="$HOST_CPUS" 'BEGIN{print (c+o>h) ? "yes" : "no"}')
    [[ "$OVER" == "yes" ]] && say "  ※ 합이 호스트를 넘는다 — 이 줄은 '상한 효과'가 아니라 '호스트 포화'로 읽는다"

    # 4) 앱을 올린다 = 색인 시작(seed 프로파일의 SeedIndexingRunner).
    dc start "$SVC_APP" >/dev/null
    wait_healthy "$SVC_APP"

    # 5) 건너뛰기 구간을 기다린다.
    #    indexAll은 lastId=0부터 훑으며 해시가 같은 문서를 건너뛴다. 그 구간에는
    #    청크가 늘지 않으므로, "늘기 시작했다"가 곧 임베딩이 흐르기 시작했다는 뜻이다.
    #    이 대기 시간은 모든 조건에서 같으므로 비교를 왜곡하지 않는다.
    say "  건너뛰기 구간 대기 중 (임베딩 시작을 기다린다)"
    waited=0
    while :; do
        NOW=$(psql_q "SELECT count(*) FROM document_chunks WHERE created_at > '$T0';")
        (( NOW >= WARMUP_CHUNKS )) && break
        (( waited >= EMBED_WAIT_MAX )) && {
            say "  !! ${EMBED_WAIT_MAX}초 안에 임베딩이 시작되지 않았다 — 이 조건은 버린다"
            printf '%s,%s,%s,%s,0,0,0,,,,,,,,%s,embedding-never-started\n' \
                "$SEQ" "$C" "$APPLIED" "$OVER" "$CORPUS_AT_START" >> "$CSV"
            break
        }
        sleep 10; waited=$((waited+10))
    done
    (( waited >= EMBED_WAIT_MAX )) && { dc stop "$SVC_APP" >/dev/null 2>&1 || true; continue; }
    say "  임베딩 시작 확인 (${waited}초 소요) — 창을 연다"

    # 6) 창 열기: 카운터 스냅샷
    declare -A TP0 TT0
    for s in "${ALL_SVCS[@]}"; do
        read -r _p _t <<< "$(read_throttle "${CG[$s]}")"
        TP0[$s]="$_p"; TT0[$s]="$_t"
    done
    read -r C0 S0 <<< "$(psql_q "SELECT count(*), count(DISTINCT (source_type, source_id)) FROM document_chunks WHERE created_at > '$T0';" | tr '|' ' ')"
    W_START=$(date +%s)

    sleep "$WINDOW_SEC"

    # 7) 창 닫기
    W_END=$(date +%s)
    read -r C1 S1 <<< "$(psql_q "SELECT count(*), count(DISTINCT (source_type, source_id)) FROM document_chunks WHERE created_at > '$T0';" | tr '|' ' ')"
    declare -A TP1 TT1
    for s in "${ALL_SVCS[@]}"; do
        read -r _p _t <<< "$(read_throttle "${CG[$s]}")"
        TP1[$s]="$_p"; TT1[$s]="$_t"
    done

    ELAPSED=$((W_END - W_START))
    DC=$((C1 - C0)); DS=$((S1 - S0))

    thr_pct() {  # 이 창 동안 상한에 막혀 서 있던 주기의 비율
        local s="$1" dp dt
        dp=$(( ${TP1[$s]} - ${TP0[$s]} )); dt=$(( ${TT1[$s]} - ${TT0[$s]} ))
        (( dp == 0 )) && { echo "n/a"; return; }
        awk -v t="$dt" -v p="$dp" 'BEGIN{printf "%.1f", 100*t/p}'
    }

    if (( DC > 0 )); then
        MSC=$(awk -v e="$ELAPSED" -v n="$DC" 'BEGIN{printf "%.1f", 1000*e/n}')
    else MSC=""; fi
    if (( DS > 0 )); then
        MSS=$(awk -v e="$ELAPSED" -v n="$DS" 'BEGIN{printf "%.1f", 1000*e/n}')
    else MSS=""; fi

    say "  창 ${ELAPSED}s — 청크 +$DC / 원본 +$DS → 청크당 ${MSC:-?}ms / 원본당 ${MSS:-?}ms"
    say "  스로틀: TEI $(thr_pct "$SVC_TEI")% / app $(thr_pct "$SVC_APP")% / db $(thr_pct "$SVC_DB")%"

    echo "$SEQ,$C,$APPLIED,$OVER,$ELAPSED,$DC,$DS,$MSC,$MSS,$(thr_pct "$SVC_TEI"),$(thr_pct "$SVC_APP"),$(thr_pct "$SVC_DB"),$(thr_pct "$SVC_REDIS"),$(thr_pct "$SVC_NGINX"),$CORPUS_AT_START," >> "$CSV"

    for s in "${ALL_SVCS[@]}"; do
        printf 'periods %s -> %s\nthrottled %s -> %s\n' \
            "${TP0[$s]}" "${TP1[$s]}" "${TT0[$s]}" "${TT1[$s]}" > "$OUT/raw/seq${SEQ}-${s}.txt"
    done

    # 8) 되돌리기 — 이번 조건이 만든 슬라이스만 지우고 P로 복귀
    dc stop "$SVC_APP" >/dev/null 2>&1 || true
    DEL=$(psql_q "WITH d AS (DELETE FROM document_chunks WHERE created_at > '$T0' RETURNING 1) SELECT count(*) FROM d;")
    # 조건마다 같은 조건에서 출발하도록 죽은 튜플을 정리한다. 안 하면 뒤 조건일수록
    # 테이블이 부풀어 있어 상한 효과와 구분되지 않는다.
    psql_q "VACUUM (ANALYZE) document_chunks;" >/dev/null
    AFTER=$(psql_q "SELECT count(*) FROM document_chunks;")
    say "  슬라이스 $DEL행 삭제 → 코퍼스 $AFTER 청크"
    [[ "$AFTER" == "$CORPUS_AT_START" ]] \
        || say "  !! 되돌리기 불일치: $CORPUS_AT_START → $AFTER (다음 조건의 출발점이 달라졌다)"
done

# ─────────────────────────────────────────────────────────── 복원 / 요약
set_tei_cpus "$RESTORE_CPUS" "${CID[$SVC_TEI]}"
say "TEI 상한을 $RESTORE_CPUS 로 복원 (cgroup: $(read_applied_cpus "${CG[$SVC_TEI]}"))"

{
    echo
    echo "## E1 결과 — $RUN_ID"
    echo
    echo "| # | 요청 | 적용 | 초과 | 청크 | 원본 | 청크당 ms | 원본당 ms | TEI 막힘 | app | db |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|"
    awk -F, 'NR>1 {printf "| %s | %s | %s | %s | %s | %s | %s | %s | %s%% | %s%% | %s%% |\n", \
        $1,$2,$3,$4,$6,$7,$8,$9,$10,$11,$12}' "$CSV"
    echo
    echo "통제군 — 첫 2.0과 마지막 2.0의 원본당 ms가 다르면 스윕이 드리프트한 것이다."
} | tee -a "$LOG"

say "완료. CSV=$CSV  환경=$OUT/env.md"

UPLOAD_OK=1
upload_results || UPLOAD_OK=0

if [[ "$SHUTDOWN_WHEN_DONE" == "1" ]]; then
    if [[ -n "$S3_RESULT_URI" && "$UPLOAD_OK" == "0" ]]; then
        # 정지는 EBS를 지우지 않으므로 결과가 사라지지는 않는다. 그래도 끄지 않는 이유는
        # "업로드했다고 믿고 인스턴스를 종료하는" 다음 수순이 위험하기 때문이다.
        say "!! 업로드가 실패해 정지하지 않는다. 결과는 $OUT 에 있다"
        exit 1
    fi
    say "SHUTDOWN_WHEN_DONE=1 — 60초 뒤 인스턴스를 정지한다"
    sleep 60; sudo shutdown -h now
fi
