#!/usr/bin/env bash
# 붕괴 구간 진단 (measure/k6-load). 10초마다 스레드 덤프 요약·소켓 큐·Redis 클라이언트를 한 파일에.
# 사용: ./diag.sh <out.txt> <초>
OUT=${1:-/tmp/diag.txt}; SEC=${2:-90}
: > "$OUT"
for i in $(seq 1 $((SEC/10))); do
  {
  echo "===== $(date -u +%T)"
  echo "-- docker stats"; docker stats --no-stream --format '{{.Name}} {{.CPUPerc}}' | tr '\n' ' '; echo
  echo "-- tomcat exec threads (state / top frame)"
  docker exec dockin-app-1 jcmd 1 Thread.print 2>/dev/null > /tmp/td.txt; cp /tmp/td.txt "${OUT%.txt}-td-$i.txt"
  awk '/^"http-nio-8080-exec/{getline s; getline f1; getline f2; sub(/.*State: /,"",s); sub(/^\s+at /,"",f1); sub(/^\s+at /,"",f2); print s" | "f1" | "f2}' /tmp/td.txt | sed 's/(java.base@[^)]*)//' | sort | uniq -c | sort -rn | head -6
  echo "-- app sockets :8080 (state, count, recvq>0)"
  docker exec dockin-app-1 sh -c 'cat /proc/net/tcp' | awk 'NR>1{st=$4; split($5,q,":"); rq=strtonum("0x" q[2]); c[st]++; if(rq>0) r[st]++} END{for(s in c) printf "%s=%d(recvq>0:%d) ", s, c[s], r[s]+0; print ""}'
  echo "-- nginx sockets"; docker exec dockin-spring-dockin-nginx-1 sh -c 'cat /proc/net/tcp' | awk 'NR>1{split($2,l,":"); split($3,r,":"); st=$4; split($5,q,":"); rq=strtonum("0x" q[2]); k=(l[2]=="0050"?"in":"up")":"st; c[k]++; if(rq>0) rr[k]++} END{for(s in c) printf "%s=%d(rq:%d) ", s, c[s], rr[s]+0; print ""}'
  echo "-- redis"; docker exec dockin-redis redis-cli info clients | tr -d '\r' | grep -E "connected_clients|blocked" | tr '\n' ' '; docker exec dockin-redis redis-cli info stats | tr -d '\r' | grep -E "instantaneous_ops" | tr '\n' ' '; echo
  echo "-- pg"; docker exec dockin-db psql -U root -d dockindb -qtAX -c "select state, wait_event_type, count(*) from pg_stat_activity where datname='dockindb' and backend_type='client backend' group by 1,2" | tr '\n' ' '; echo
  } >> "$OUT" 2>&1
  sleep 10
done
