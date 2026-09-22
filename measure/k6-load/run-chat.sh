#!/usr/bin/env bash
# 채팅 팬아웃 한 단계 (measure/k6-load/chat.js). run-stage.sh의 채팅판 — attendance 초기화·통제군 대신
# 유지 구간 중간에 서버 소켓 수를 한 번 찍는다.
#   ./run-chat.sh <label> SESSIONS=1000 SEND_EVERY=3.3 RAMP=1m HOLD=2m
# 결과: measure/k6-load/<RUN>/<label>/{summary.json,k6.log,sampler.csv,sockets.txt,app-warn.log}
set -euo pipefail
SERVER=${SERVER:?}; GEN=${GEN:?}; SERVER_PRIV=${SERVER_PRIV:?}
KEY=${KEY:-$HOME/.ssh/shadowfit-measure.pem}
SSH="ssh -n -i $KEY -o ConnectTimeout=15 -o LogLevel=ERROR"
RUN=${RUN:-$(date -u +%Y%m%dT%H%M%S)}
LABEL=$1; shift
ENVS="$*"
OUT=$(dirname "$0")/$RUN/$LABEL; mkdir -p "$OUT"
echo "== [$LABEL] $ENVS  → $OUT"

for i in $(seq 1 48); do
  p=$($SSH ec2-user@$SERVER "DOCKin-spring/measure/k6-load/pending.sh" 2>/dev/null)
  [[ "$p" == "0.0" || "$p" == "0" ]] && break
  echo "   회복 대기 $((i*5))s (pending=${p:-?})"; sleep 5
done

$SSH ec2-user@$SERVER "cd DOCKin-spring && [[ -f /tmp/sampler.pid ]] && kill \$(cat /tmp/sampler.pid) 2>/dev/null; setsid nohup measure/k6-load/sampler.sh /tmp/sampler-$LABEL.csv >/tmp/sampler.err 2>&1 < /dev/null & echo \$! > /tmp/sampler.pid; sleep 1; echo '   sampler 시작 pid' \$(cat /tmp/sampler.pid)"

# 유지 구간 한가운데(램프 + 유지/2)에 서버 쪽 소켓·스레드를 한 번 찍는다.
ramp=$(echo "$ENVS" | grep -oE 'RAMP=[0-9]+[ms]' | sed 's/RAMP=//'); hold=$(echo "$ENVS" | grep -oE 'HOLD=[0-9]+[ms]' | sed 's/HOLD=//')
tosec() { local v=${1:-1m}; case $v in *m) echo $(( ${v%m} * 60 ));; *s) echo ${v%s};; esac; }
mid=$(( $(tosec "$ramp") + $(tosec "$hold") / 2 ))
( sleep $mid; $SSH ec2-user@$SERVER "docker exec dockin-app-1 sh -c 'sed -n 2p /proc/net/sockstat; ls /proc/1/task | wc -l'; docker exec dockin-spring-dockin-nginx-1 sh -c 'sed -n 2p /proc/net/sockstat'" ) > "$OUT/sockets.txt" 2>&1 &

$SSH ec2-user@$GEN "cd ~ && env BASE_URL=http://$SERVER_PRIV OUT=out/$LABEL.json $ENVS k6 run -q chat.js" > "$OUT/k6.log" 2>&1 || true
wait
$SSH ec2-user@$GEN "cat out/$LABEL.json" > "$OUT/summary.json"
$SSH ec2-user@$SERVER "kill \$(cat /tmp/sampler.pid) 2>/dev/null; rm -f /tmp/sampler.pid; sleep 1; cat /tmp/sampler-$LABEL.csv" > "$OUT/sampler.csv"
$SSH ec2-user@$SERVER "cd DOCKin-spring && docker compose -f compose.yaml -f compose.gc.yaml logs --since 8m dockin-app 2>/dev/null | grep -A5 -E 'WARN|ERROR' | grep -v 'RAG\|indexing\|구독 요청\|인증 성공' | tail -80" > "$OUT/app-warn.log" || true

grep -a "^== chat" "$OUT/k6.log" || tail -5 "$OUT/k6.log"
cat "$OUT/sockets.txt"
python - "$OUT/sampler.csv" <<'PY' 2>/dev/null || true
import csv,sys
rows=list(csv.DictReader(open(sys.argv[1])))
def mx(k):
    v=[float(r[k]) for r in rows if r.get(k) not in (None,'','None')]
    return max(v) if v else None
print("   sampler max: app_cpu=%s%% db_cpu=%s%% nginx_cpu=%s%% hik_active=%s hik_pending=%s heap=%sMB app_mem=%s pg_active=%s load1=%s (%d samples)"%(
  mx('app_cpu'),mx('db_cpu'),mx('nginx_cpu'),mx('hik_active'),mx('hik_pending'),mx('heap_used_mb'),max((r['app_mem'] for r in rows),default=None),mx('pg_active'),mx('load1'),len(rows)))
PY
