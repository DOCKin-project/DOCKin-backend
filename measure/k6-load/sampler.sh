#!/usr/bin/env bash
# 서버 쪽 5초 샘플러 (measure/k6-load). k6 가 도는 동안 각 층의 대기·포화를 같은 시각축에 남긴다.
#   docker stats   : 컨테이너별 CPU% (상한 대비 포화 여부 — app 1.0, db 1.5, nginx 0.25)
#   actuator       : hikari active/pending, tomcat busy/current, jvm heap used   (ADMIN 토큰 필요)
#   pg_stat_activity: active / idle in transaction / lock wait
#   loadavg
# 사용: ./sampler.sh <out.csv>   (백그라운드로 띄우고 끝나면 kill)
set -u
OUT=${1:-sampler.csv}
COMPOSE="docker compose -f compose.yaml -f compose.gc.yaml"
BASE=http://127.0.0.1:8080
TOKEN=$(curl -s -m 3 -H 'Content-Type: application/json' -d '{"userId":"admin01","password":"dockin1234"}' $BASE/api/member/login | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[[ -n "$TOKEN" ]] || { echo "admin 로그인 실패"; exit 1; }
metric() { curl -s -m 3 -H "Authorization: Bearer $TOKEN" "$BASE/actuator/metrics/$1" | sed -n 's/.*"value":\([0-9.E+-]*\).*/\1/p' | head -1; }
echo "ts,load1,app_cpu,db_cpu,nginx_cpu,redis_cpu,app_mem,hik_active,hik_pending,hik_idle,tomcat_busy,tomcat_cur,heap_used_mb,pg_active,pg_idle_tx,pg_waiting,pg_total" > "$OUT"
while true; do
  ts=$(date -u +%H:%M:%S); load=$(cut -d' ' -f1 /proc/loadavg)
  st=$(docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null)
  cpu() { echo "$st" | awk -v n="$1" 'index($1,n){gsub("%","",$2);print $2; exit}'; }
  appmem=$(echo "$st" | awk '$1=="dockin-app-1"{print $3}')
  ha=$(metric hikaricp.connections.active); hp=$(metric hikaricp.connections.pending); hi=$(metric hikaricp.connections.idle)
  tb=$(metric tomcat.threads.busy); tc=$(metric tomcat.threads.current)
  heap=$(curl -s -m 3 -H "Authorization: Bearer $TOKEN" "$BASE/actuator/metrics/jvm.memory.used?tag=area:heap" | sed -n 's/.*"value":\([0-9.E+-]*\).*/\1/p' | head -1)
  heapmb=$(awk -v v="${heap:-0}" 'BEGIN{printf "%.0f", v/1048576}')
  pg=$($COMPOSE exec -T DOCKin-DB psql -U root -d dockindb -qtAX -c "SELECT count(*) FILTER (WHERE state='active'), count(*) FILTER (WHERE state='idle in transaction'), count(*) FILTER (WHERE wait_event_type='Lock'), count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND datname='dockindb'" 2>/dev/null | tr -d '\r' | tr '|' ',')
  echo "$ts,$load,$(cpu dockin-app),$(cpu dockin-db),$(cpu nginx),$(cpu dockin-redis),$appmem,$ha,$hp,$hi,$tb,$tc,$heapmb,$pg" >> "$OUT"
  sleep 5
done
