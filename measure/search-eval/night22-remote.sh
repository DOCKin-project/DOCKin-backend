#!/usr/bin/env bash
#
# 밤 22 — 하이브리드(#210)·재순위(#213)를 E1 평가셋(120문항 × 3언어)으로. 측정 인스턴스(AL2023, m7i.2xlarge)에서 돈다.
#
# [왜 EC2인가] 품질 숫자는 하드웨어와 무관하지만 지연은 아니다. 로컬 i3는 다른 측정과 CPU를 나눠 쓰고 있었고,
#   재순위 모델(bge-reranker-v2-m3, 568M)의 지연을 깨끗한 8코어에서 재야 "느린 대신 얼마나 정확한가"를 말할 수 있다.
#
# [코퍼스] 로컬 캐시(~/.dockin-search-eval/corpus-20000, E1과 같은 것)를 scp로 올려 둔다 — 색인하지 않는다.
#   그래야 E1(#214) 숫자와 같은 데이터다. HNSW는 복원 때 새로 지어 ANN 행은 재구축 흔들림(#187)을 포함한다.
#
# [실행] 로컬에서 scp로 올리고:  nohup bash night22-remote.sh > night22.log 2>&1 &
#   끝나면 ~/NIGHT22_DONE (내용: 테스트 종료 코드). 산출물 ~/DOCKin-spring/build/search-eval/.
#
set -uo pipefail
BRANCH=${BRANCH:-measure/search-eval-hybrid-rerank}
CORPUS=${CORPUS:-20000}
TEI_IMAGE=${TEI_IMAGE:-ghcr.io/huggingface/text-embeddings-inference:cpu-latest}
RERANK_MODEL=${RERANK_MODEL:-BAAI/bge-reranker-v2-m3}
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }
finish() { echo "$1" > "$HOME/NIGHT22_DONE"; say "끝 (exit $1)"; exit "$1"; }

say "하드 watchdog — 세션이 죽어도 3시간 뒤 꺼진다(stop. terminate는 로컬에서 한다)"
sudo shutdown -h +180 || true

say "패키지"
sudo dnf install -y -q docker git java-21-amazon-corretto-devel tar >/dev/null
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
nproc; free -g | head -2

say "코퍼스 캐시"
ls -la "$HOME/.dockin-search-eval/corpus-$CORPUS" || { say "캐시가 없다 — 로컬에서 먼저 올린다"; finish 3; }

say "저장소 ($BRANCH)"
[[ -d DOCKin-spring ]] || git clone -q --depth 1 -b "$BRANCH" https://github.com/DOCKin-project/DOCKin-backend.git DOCKin-spring
git -C DOCKin-spring log --oneline -1

wait_health() { # <port> <name>
  for i in $(seq 1 180); do curl -sf "http://localhost:$1/health" >/dev/null && return 0; sleep 5; done
  say "$2가 안 떴다"; sudo docker logs --tail 30 "$2"; return 1
}

say "TEI 임베딩 + 재순위 ($TEI_IMAGE)"
sudo docker rm -f eval-embed eval-rerank >/dev/null 2>&1
sudo docker run -d --name eval-embed -p 127.0.0.1:58081:80 "$TEI_IMAGE" \
    --model-id=intfloat/multilingual-e5-small --auto-truncate >/dev/null
sudo docker run -d --name eval-rerank -p 127.0.0.1:58082:80 "$TEI_IMAGE" \
    --model-id="$RERANK_MODEL" --auto-truncate >/dev/null
sudo docker inspect eval-embed --format 'image digest: {{.Image}}'
wait_health 58081 eval-embed || finish 2
wait_health 58082 eval-rerank || finish 2

say "재순위 단독 지연 (후보 20개 × 실제 일지 길이, 5회)"
python3 - <<'EOF'
import json, time, urllib.request
doc = "CO2 용접기 3호기 송급 롤러에서 이상 진동이 발생했다. 점검 결과 체결 볼트 이완이 확인되었다. 규정 토크로 재체결하고 시운전으로 정상 동작을 확인했다. " * 2
body = json.dumps({"query": "용접기 롤러 진동 원인", "texts": [doc] * 20, "raw_scores": False, "truncate": True}).encode()
req = lambda: urllib.request.urlopen(urllib.request.Request("http://localhost:58082/rerank", body, {"Content-Type": "application/json"})).read()
req()  # 워밍업
xs = []
for _ in range(5):
    t = time.time(); req(); xs.append((time.time() - t) * 1000)
xs.sort()
print(f"rerank 20개: p50 {xs[2]:.0f} ms, 최대 {xs[-1]:.0f} ms")
EOF

say "하네스 (SEARCH_EVAL_CORPUS=$CORPUS, 재순위 포함)"
cd DOCKin-spring
sg docker -c "RUN_SEARCH_EVAL=1 SEARCH_EVAL_EMBED_URL=http://localhost:58081 SEARCH_EVAL_RERANK_URL=http://localhost:58082 \
    SEARCH_EVAL_CORPUS=$CORPUS ./gradlew test --tests '*SearchQualityEvalTest' --no-daemon -q"
code=$?
ls -la build/search-eval/ 2>/dev/null
grep -h '<testsuite' build/test-results/test/*.xml 2>/dev/null | head -2
finish $code
