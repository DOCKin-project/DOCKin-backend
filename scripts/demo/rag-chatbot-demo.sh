#!/usr/bin/env bash
#
# RAG 챗봇 시연 — 베트남어로 물어 한국어 작업일지에서 근거를 찾고, 권한이 없으면 같은 질문에 0건 (#95)
#
# [무엇을 보여주나]
#   1. admin01(관리자)이 베트남어로 "용접 와이어가 자꾸 멈추는 문제"를 묻는다
#      -> 한국어 작업일지 #1(CO2 용접기 와이어 송급 불량)이 근거로 나온다. 교차언어 검색.
#   2. worker02(응웬반, vi)가 같은 질문을 한다
#      -> 작업일지는 OWNER 가시성이라 남의 일지(worker01)는 선필터에서 걸러진다. 근거 5건이
#         본인 일지(7~11)·그 번역·PUBLIC 안전교육으로만 채워지고 #1은 후보에 없다.
#         후필터였다면 "k보다 적게 온다"로 존재가 새지만, 선필터는 애초에 후보에 안 들어간다.
#   실행 결과(2026-09-19): docs/demo/rag-chatbot-demo-2026-09-19.txt
#   3. worker01(김철수, 일지 주인)이 같은 질문을 하면 다시 나온다.
#
# [전제]
#   - 앱이 seed 프로파일 + 기동 색인으로 떠 있다. FastAPI는 없어도 된다(스텁):
#       docker compose up -d                       # DB·Redis·TEI
#       AI_CHATBOT_STUB=true RAG_INDEXING_ON_STARTUP=true SPRING_PROFILES_ACTIVE=seed ./gradlew bootRun
#   - 팀원 FastAPI가 있으면 AI_CHATBOT_STUB를 빼고 AI_SERVER_URL만 주면 답변 문장까지 나온다.
#     retrieval 필드는 두 경우 모두 같다 -- 시연의 요점은 답변이 아니라 그 필드다.
#
# [실행]  BASE=http://localhost:8080 ./scripts/demo/rag-chatbot-demo.sh   (Windows Git Bash면 PYTHONIOENCODING=utf-8 앞에)
#
set -uo pipefail

BASE=${BASE:-http://localhost:8080}
PASSWORD=${PASSWORD:-dockin1234}     # 시드 계정 넷 공통(R__seed_sample.sql)
QUESTION=${QUESTION:-'Dây hàn CO2 bị kẹt, dừng liên tục thì xử lý thế nào?'}   # "CO2 용접 와이어가 자꾸 걸려 멈추면 어떻게 하나"

# JSON은 python으로 다룬다 -- jq가 없는 머신(Windows Git Bash)이 있어서.
PY=$(command -v python || command -v python3) || { echo "python이 필요합니다"; exit 1; }

login() {  # $1=userId -> accessToken
  curl -sS -X POST "$BASE/api/member/login" -H 'Content-Type: application/json'     -d "{\"userId\":\"$1\",\"password\":\"$PASSWORD\"}"   | "$PY" -c 'import sys,json; print(json.load(sys.stdin).get("accessToken",""))'
}

ask() {    # $1=userId $2=label
  local token; token=$(login "$1")
  if [ -z "$token" ]; then echo "!! $1 로그인 실패"; return 1; fi
  echo "=== $2 ($1) ==="
  echo "Q: $QUESTION"
  "$PY" -c 'import sys,json; print(json.dumps({"messages":[{"role":"user","content":sys.argv[1]}],"lang":"vi","traceId":"demo-"+sys.argv[2]}, ensure_ascii=False))' "$QUESTION" "$1"   | curl -sS -X POST "$BASE/api/ai/chatbot" -H 'Content-Type: application/json; charset=utf-8'       -H "Authorization: Bearer $token" --data-binary @-   | "$PY" -c '
import sys, json
r = json.load(sys.stdin)
ret = r.get("retrieval") or {}
print("retrieval_mode:", ret.get("mode"))
print("sources:", ["%s #%s (score %.3f)" % (s["sourceType"], s["sourceId"], s["score"]) for s in ret.get("sources", [])])
print("reply:")
print((r.get("result") or {}).get("reply") or r)
'
  echo
}

ask admin01  "관리자 - 전체 문서 대상"
ask worker02 "응웬반(vi) - 남의 작업일지는 선필터에서 걸러진다"
ask worker01 "김철수 - 자기 작업일지라 다시 나온다"
