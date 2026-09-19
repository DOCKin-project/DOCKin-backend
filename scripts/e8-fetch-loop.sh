#!/usr/bin/env bash
#
# 로컬 fetcher — 측정 인스턴스의 산출물을 돌고 있는 동안 계속 내려받고, 끝나면 마지막으로 받은 뒤 terminate한다.
#
# [왜] 밤 14의 창 A·B 산출물이 "정지해 둔" 인스턴스와 함께 사라졌다(#86). 두 번째였다.
#      산출물은 인스턴스를 끄기 전에, 그리고 도는 동안에도 받아 둔다 -- 중간에 사라져도 거기까지는 남는다.
#
# [실행]  HOST=<public ip> INSTANCE=<i-...> ./scripts/e8-fetch-loop.sh   (nohup으로 띄운다)
#   KEY=~/.ssh/shadowfit-measure.pem  DEST=measure/  EVERY=300  REMOTE_DIR=DOCKin-spring/measure
#   DONE_FILE=NIGHT16_DONE (원격 $HOME 기준)  TERMINATE=1
#
set -uo pipefail
cd "$(dirname "$0")/.."

HOST=${HOST:?public ip}
INSTANCE=${INSTANCE:?instance id}
REGION=${REGION:-ap-northeast-2}
KEY=${KEY:-$HOME/.ssh/shadowfit-measure.pem}
USER_=${USER_:-ec2-user}
REMOTE_DIR=${REMOTE_DIR:-DOCKin-spring/measure}
REMOTE_LOGS=${REMOTE_LOGS:-'night16*.log'}
DONE_FILE=${DONE_FILE:-NIGHT16_DONE}
DEST=${DEST:-measure}
EVERY=${EVERY:-300}
TERMINATE=${TERMINATE:-1}
MAX_MIN=${MAX_MIN:-540}

SSH="ssh -i $KEY -o StrictHostKeyChecking=no -o ConnectTimeout=15 -o ServerAliveInterval=30 $USER_@$HOST"
say() { printf '%s  %s\n' "$(date +%F' '%T)" "$*"; }

# 원격 measure/ 전체와 홈의 로그를 tar로 받는다. rsync가 없는 Git Bash에서도 된다.
fetch() {
    local tmp; tmp=$(mktemp -d)
    $SSH "cd \$HOME && tar czf - --ignore-failed-read $REMOTE_DIR $REMOTE_LOGS $DONE_FILE 2>/dev/null"         | tar xzf - -C "$tmp" 2>/dev/null || { rm -rf "$tmp"; return 1; }
    mkdir -p "$DEST/night16-logs"
    [[ -d "$tmp/$REMOTE_DIR" ]] && cp -r "$tmp/$REMOTE_DIR/." "$DEST/"
    cp -f "$tmp"/night16*.log "$tmp/$DONE_FILE" "$DEST/night16-logs/" 2>/dev/null
    rm -rf "$tmp"
    return 0
}

say "시작: $USER_@$HOST ($INSTANCE) → $DEST, ${EVERY}초마다"
T0=$(date +%s)
while :; do
    if fetch; then say "받음: $(ls -d "$DEST"/e8cause-* "$DEST"/e8reopen-* 2>/dev/null | xargs -n1 basename 2>/dev/null | tr '\n' ' ')"
    else say "받기 실패 (ssh?)"; fi
    if [[ -f "$DEST/night16-logs/$DONE_FILE" ]]; then
        say "DONE 표식 확인: $(cat "$DEST/night16-logs/$DONE_FILE")"
        sleep 20; fetch; say "마지막으로 한 번 더 받았다"
        if [[ "$TERMINATE" == "1" ]]; then
            aws ec2 terminate-instances --region "$REGION" --instance-ids "$INSTANCE" --query 'TerminatingInstances[0].CurrentState.Name' --output text \
                && say "terminate 요청 완료: $INSTANCE" || say "!! terminate 실패 — 손으로: aws ec2 terminate-instances --instance-ids $INSTANCE"
        fi
        say "FETCH DONE"; exit 0
    fi
    if (( ( $(date +%s) - T0 ) / 60 > MAX_MIN )); then say "!! ${MAX_MIN}분 상한 — 끝난다. 인스턴스는 그대로다: $INSTANCE"; exit 1; fi
    sleep "$EVERY"
done
