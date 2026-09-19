#!/usr/bin/env bash
# 로컬에서 한 단계를 돌린다 (measure/k6-load). 서버는 attendance 초기화 + 샘플러, 부하기는 k6.
#   ./run-stage.sh <label> SCENARIO=rate RATE=8 DURATION=2m
#   ./run-stage.sh <label> SCENARIO=vus TARGET_VUS=1000 RAMP=1m HOLD=2m
# 결과: measure/k6-load/<RUN>/<label>/{summary.json,k6.log,sampler.csv,idle.json}
set -euo pipefail
SERVER=${SERVER:?}; GEN=${GEN:?}; SERVER_PRIV=${SERVER_PRIV:?}
KEY=${KEY:-$HOME/.ssh/shadowfit-measure.pem}
SSH="ssh -n -i $KEY -o ConnectTimeout=15 -o LogLevel=ERROR"
RUN=${RUN:-$(date -u +%Y%m%dT%H%M%S)}
LABEL=$1; shift
ENVS="$*"
OUT=$(dirname "$0")/$RUN/$LABEL; mkdir -p "$OUT"
echo "== [$LABEL] $ENVS  → $OUT"

# 회복 대기: 앞 단계의 잔여(풀 대기 30초 타임아웃·60초 요청)가 다 빠질 때까지. r16의 통제군 p95가 24초였던 것이
# 앞 단계 r08의 잔여였다 — 이걸 안 기다리면 다음 단계가 앞 단계를 잰다. 최대 4분.
for i in $(seq 1 48); do
  p=$($SSH ec2-user@$SERVER "DOCKin-spring/measure/k6-load/pending.sh" 2>/dev/null)
  [[ "$p" == "0.0" || "$p" == "0" ]] && break
  echo "   회복 대기 $((i*5))s (pending=${p:-?})"; sleep 5
done

# 통제군: 부하 없는 상태의 사이클 지연 (E3 "매 조건 앞뒤로 부하 없는 p95" — 인스턴스 드리프트 감지)
$SSH ec2-user@$GEN "cd ~ && BASE_URL=http://$SERVER_PRIV SCENARIO=rate RATE=1 DURATION=10s OFFSET=39000 OUT=out/idle-$LABEL.json k6 run -q journey.js >/dev/null 2>&1; cat out/idle-$LABEL.json" > "$OUT/idle.json"
python - "$OUT/idle.json" <<'PY' 2>/dev/null || true
import json,sys; d=json.load(open(sys.argv[1])); print("   idle: p95_all=%sms journey_p95=%sms fail=%s%%"%(d['p95_all'],d['journey_p95'],d['fail_pct']))
PY

$SSH ec2-user@$SERVER "cd DOCKin-spring && docker compose -f compose.yaml -f compose.gc.yaml exec -T DOCKin-DB psql -U root -d dockindb -qtAX < measure/k6-load/reset-attendance.sql | tail -1 | xargs echo '   attendance 초기화, 남은 건수:'; [[ -f /tmp/sampler.pid ]] && kill \$(cat /tmp/sampler.pid) 2>/dev/null; setsid nohup measure/k6-load/sampler.sh /tmp/sampler-$LABEL.csv >/tmp/sampler.err 2>&1 < /dev/null & echo \$! > /tmp/sampler.pid; sleep 1; echo '   sampler 시작 pid' \$(cat /tmp/sampler.pid)"

$SSH ec2-user@$GEN "cd ~ && env BASE_URL=http://$SERVER_PRIV OUT=out/$LABEL.json $ENVS k6 run journey.js" > "$OUT/k6.log" 2>&1 || true
$SSH ec2-user@$GEN "cat out/$LABEL.json" > "$OUT/summary.json"
$SSH ec2-user@$SERVER "kill \$(cat /tmp/sampler.pid) 2>/dev/null; rm -f /tmp/sampler.pid; sleep 1; cat /tmp/sampler-$LABEL.csv" > "$OUT/sampler.csv"
$SSH ec2-user@$SERVER "cd DOCKin-spring && docker compose -f compose.yaml -f compose.gc.yaml logs --since 10m dockin-app 2>/dev/null | grep -E 'WARN|ERROR' | grep -v 'RAG\|indexing' | tail -30" > "$OUT/app-warn.log" || true

tail -n 9 "$OUT/k6.log"
python - "$OUT/sampler.csv" <<'PY' 2>/dev/null || true
import csv,sys
rows=list(csv.DictReader(open(sys.argv[1])))
def mx(k):
    v=[float(r[k]) for r in rows if r.get(k) not in (None,'','None')]
    return max(v) if v else None
print("   sampler max: app_cpu=%s%% db_cpu=%s%% nginx_cpu=%s%% hik_active=%s hik_pending=%s tomcat_busy=%s heap=%sMB pg_active=%s pg_waiting=%s load1=%s (%d samples)"%(
  mx('app_cpu'),mx('db_cpu'),mx('nginx_cpu'),mx('hik_active'),mx('hik_pending'),mx('tomcat_busy'),mx('heap_used_mb'),mx('pg_active'),mx('pg_waiting'),mx('load1'),len(rows)))
PY
