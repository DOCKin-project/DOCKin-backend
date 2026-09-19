#!/usr/bin/env bash
# 서버에서: hikaricp.connections.pending 한 값. 회복 대기 판정용 (run-stage.sh).
B=http://127.0.0.1:8080
T=$(curl -s -m 5 -H 'Content-Type: application/json' -d '{"userId":"admin01","password":"dockin1234"}' $B/api/member/login | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
[[ -n "$T" ]] || { echo "login-fail"; exit 0; }
curl -s -m 5 -H "Authorization: Bearer $T" "$B/actuator/metrics/hikaricp.connections.pending" | sed -n 's/.*"value":\([0-9.E+-]*\).*/\1/p' | head -1
