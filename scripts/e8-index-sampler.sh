#!/usr/bin/env bash
#
# E8 — 코퍼스 크기 ↔ 색인 처리량 샘플러 (docs/AWS-MEASUREMENT-PLAN.md 3절 E8)
#
#   긴 색인이 도는 동안 옆에서 일정 간격으로 코퍼스 크기와 증가분을 기록한다.
#   6-11이 설명하지 못한 2.4배의 유력 후보가 "코퍼스가 클수록 HNSW 삽입이 비싸다"이고,
#   그것을 가르려면 빈 코퍼스부터의 구간별 처리량이 필요하다.
#
# [실행] 색인을 시작하기 직전에 백그라운드로 띄운다.
#   ./scripts/e8-index-sampler.sh &
#   ...색인이 끝나면...
#   kill %1        # 또는 Ctrl-C. 요약을 찍고 종료한다.
#
# [이 스크립트가 재는 것은 처리량이지 시간이 아니다]
#   한 구간의 원본당 ms = 구간 길이 / 그 구간에 새로 색인된 원본 수.
#   그래서 색인이 멈춰 있던 구간(앱이 내려가 있거나 건너뛰기 구간)은 값이 무한대가 되는데,
#   버리지 않고 **표시해서 남긴다.** 지우면 나중에 "왜 이 시각이 비었나"를 알 수 없다.
#
# [E1 스윕이 중간에 끼어드는 것을 전제로 만들었다]
#   밤 1은 색인 도중에 TEI 상한을 바꾸고 앱을 재시작한다(E1). 그러면 E8의 곡선이
#   그 지점에서 꺾이는데, 그것은 코퍼스 크기 때문이 아니라 조건이 바뀐 것이다.
#   그래서 매 표본마다 **TEI 상한과 앱 상태를 함께 적는다** -- 나중에 두 구간을
#   가르는 근거가 데이터 안에 있게 된다. 계획 4절의 "E8은 P를 경계로 두 구간으로 나뉜다"가
#   문서에만 있으면 자고 일어나서 어디가 경계였는지 못 찾는다.
#
set -euo pipefail

SAMPLE_SEC=${SAMPLE_SEC:-60}
COMPOSE_FILES=${COMPOSE_FILES:-"-f compose.yaml -f compose.gc.yaml"}
OUT_ROOT=${OUT_ROOT:-measure}
S3_RESULT_URI=${S3_RESULT_URI:-}

SVC_DB=DOCKin-DB
SVC_APP=dockin-app
SVC_TEI=dockin-embedding
DB_USER=${DB_USER:-root}
DB_NAME=${DB_NAME:-dockindb}

RUN_ID=$(date +%Y%m%dT%H%M%S)
OUT="$OUT_ROOT/e8-$RUN_ID"
CSV="$OUT/e8-samples.csv"

dc()  { docker compose $COMPOSE_FILES "$@"; }
say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
psql_q() {
    dc exec -T "$SVC_DB" psql -U "$DB_USER" -d "$DB_NAME" -qtAX -c "$1" 2>/dev/null | tr -d '\r'
}

# TEI에 실제로 걸린 상한. E1이 바꾸면 이 값이 따라 변하고, 그것이 곧 구간의 경계다.
tei_cpus() {
    local id q p
    id=$(dc ps -q "$SVC_TEI" 2>/dev/null | head -1) || true
    [[ -n "$id" ]] || { echo "?"; return; }
    for d in "/sys/fs/cgroup/system.slice/docker-${id}.scope" "/sys/fs/cgroup/docker/${id}"; do
        if [[ -r "$d/cpu.max" ]]; then
            read -r q p < "$d/cpu.max"
            [[ "$q" == "max" ]] && { echo "unlimited"; return; }
            awk -v q="$q" -v p="$p" 'BEGIN{printf "%.2f", q/p}'; return
        fi
    done
    # cgroup을 못 읽는 환경(Docker Desktop)에서는 docker inspect로 떨어진다.
    docker inspect -f '{{if .HostConfig.NanoCpus}}{{.HostConfig.NanoCpus}}{{else}}0{{end}}' "$id" \
        | awk '{ if ($1==0) print "unlimited"; else printf "%.2f", $1/1000000000 }'
}

app_running() {
    local id st
    id=$(dc ps -q "$SVC_APP" 2>/dev/null | head -1) || true
    [[ -n "$id" ]] || { echo "no"; return; }
    st=$(docker inspect -f '{{.State.Running}}' "$id" 2>/dev/null || echo false)
    [[ "$st" == "true" ]] && echo "yes" || echo "no"
}

mkdir -p "$OUT"
echo "seq,t_epoch,t_iso,elapsed_s,chunks_total,sources_total,d_chunks,d_sources,ms_per_chunk,ms_per_source,tei_cpus,app_running,note" > "$CSV"

finish() {
    echo
    say "샘플러 종료 — $CSV"
    if [[ $(wc -l < "$CSV") -gt 1 ]]; then
        echo
        echo "## E8 구간별 처리량 — $RUN_ID"
        echo
        echo "| 코퍼스(청크) | 구간 원본 | 원본당 ms | TEI | 앱 |"
        echo "|---|---|---|---|---|"
        awk -F, 'NR>1 && $8>0 {printf "| %s | %s | %s | %s | %s |\n", $5,$8,$10,$11,$12}' "$CSV"
        echo
        echo "구간이 비어 있는(원본 0) 표본은 건너뛰기 구간이거나 앱이 내려가 있던 때다. CSV에는 남아 있다."
    fi
    if [[ -n "$S3_RESULT_URI" ]] && command -v aws >/dev/null; then
        local dest="${S3_RESULT_URI%/}/e8-$RUN_ID/"
        say "S3 업로드 → $dest"
        aws s3 cp "$OUT" "$dest" --recursive --only-show-errors \
            && say "업로드 완료" || say "!! 업로드 실패 — 결과는 $OUT 에 있다"
    fi
    exit 0
}
trap finish INT TERM

say "E8 샘플러 시작 — ${SAMPLE_SEC}초 간격, 출력 $CSV"
say "색인을 멈춘 뒤 Ctrl-C(또는 kill)로 종료하면 요약을 찍는다."

SEQ=0
T_START=$(date +%s)
PREV_C=""; PREV_S=""; PREV_T=""

while :; do
    NOW=$(date +%s)
    ROW=$(psql_q "SELECT count(*), count(DISTINCT (source_type, source_id)) FROM document_chunks;")
    if [[ -z "$ROW" ]]; then
        # DB에 못 물어본 표본은 비워두지 않고 이유를 적는다. 조용한 결측이 가장 읽기 어렵다.
        SEQ=$((SEQ+1))
        printf '%s,%s,%s,%s,,,,,,,%s,%s,db-unreachable\n' \
            "$SEQ" "$NOW" "$(date -Is)" "$((NOW-T_START))" "$(tei_cpus)" "$(app_running)" >> "$CSV"
        sleep "$SAMPLE_SEC"; continue
    fi
    IFS='|' read -r CH SR <<< "$ROW"
    SEQ=$((SEQ+1))

    DC=""; DS=""; MSC=""; MSS=""
    if [[ -n "$PREV_C" ]]; then
        DC=$((CH - PREV_C)); DS=$((SR - PREV_S))
        GAP=$((NOW - PREV_T))
        (( DC > 0 )) && MSC=$(awk -v e="$GAP" -v n="$DC" 'BEGIN{printf "%.1f", 1000*e/n}')
        (( DS > 0 )) && MSS=$(awk -v e="$GAP" -v n="$DS" 'BEGIN{printf "%.1f", 1000*e/n}')
    fi

    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,\n' \
        "$SEQ" "$NOW" "$(date -Is)" "$((NOW-T_START))" \
        "$CH" "$SR" "$DC" "$DS" "$MSC" "$MSS" "$(tei_cpus)" "$(app_running)" >> "$CSV"

    [[ -n "$MSS" ]] && say "청크 $CH / 구간 원본 +$DS → 원본당 ${MSS}ms (TEI $(tei_cpus))"

    PREV_C=$CH; PREV_S=$SR; PREV_T=$NOW
    sleep "$SAMPLE_SEC"
done
