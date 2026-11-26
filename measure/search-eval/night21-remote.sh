#!/usr/bin/env bash
#
# 밤 21 — 검색 품질 하네스(#170 E0) 원격 실행. 측정 인스턴스(AL2023, m7i.2xlarge)에서 돈다.
#
# [왜 EC2인가] 로컬 i3·Docker 4 vCPU에서 CPU TEI가 청크당 0.6~0.8초였다(2,000건 색인 36분).
#   품질 지표는 하드웨어와 무관하지만, 20,000건(약 3.4만 청크)을 로컬에서 색인하면 6~8시간이다.
#
# [실행] 로컬에서 scp로 올리고:  nohup bash night21-remote.sh > night21.log 2>&1 &
#   끝나면 ~/NIGHT21_DONE (내용: 테스트 종료 코드). 산출물 ~/DOCKin-spring/build/search-eval/,
#   색인 캐시 ~/.dockin-search-eval/ (다음에 로컬에서 색인 없이 다시 돌리는 데 쓴다).
#
set -uo pipefail
BRANCH=${BRANCH:-measure/search-eval-pilot}
CORPUS=${CORPUS:-20000}
TEI_IMAGE=${TEI_IMAGE:-ghcr.io/huggingface/text-embeddings-inference:cpu-latest}
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }
finish() { echo "$1" > "$HOME/NIGHT21_DONE"; say "끝 (exit $1)"; exit "$1"; }

say "하드 watchdog — 세션이 죽어도 4시간 뒤 꺼진다(stop. terminate는 로컬 fetcher가 한다)"
sudo shutdown -h +240 || true

say "패키지"
sudo dnf install -y -q docker git java-21-amazon-corretto-devel tar >/dev/null
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
nproc; free -g | head -2

say "저장소 ($BRANCH)"
[[ -d DOCKin-spring ]] || git clone -q --depth 1 -b "$BRANCH" https://github.com/DOCKin-project/DOCKin-backend.git DOCKin-spring
git -C DOCKin-spring log --oneline -1

say "TEI ($TEI_IMAGE)"
sudo docker rm -f eval-embed >/dev/null 2>&1
sudo docker run -d --name eval-embed -p 127.0.0.1:58081:80 "$TEI_IMAGE" \
    --model-id=intfloat/multilingual-e5-small --auto-truncate >/dev/null
sudo docker inspect eval-embed --format 'image digest: {{.Image}}'
for i in $(seq 1 120); do curl -sf http://localhost:58081/health >/dev/null && break; sleep 5; done
curl -sf http://localhost:58081/health >/dev/null || { say "TEI가 안 떴다"; sudo docker logs --tail 30 eval-embed; finish 2; }

say "TEI 처리량 (배치 32, 2회 평균)"
python3 - <<'EOF'
import json, time, urllib.request
txt = "passage: " + "CO2 용접기 3호기 송급 롤러에서 이상 진동이 발생했다. 점검 결과 체결 볼트 이완이 확인되었다. 규정 토크로 재체결했다. " * 2
body = json.dumps({"inputs": [txt] * 32}).encode()
t = time.time()
for _ in range(2):
    urllib.request.urlopen(urllib.request.Request("http://localhost:58081/embed", body, {"Content-Type": "application/json"})).read()
print(f"{(time.time() - t) / 2 / 32 * 1000:.0f} ms/passage")
EOF

say "하네스 (SEARCH_EVAL_CORPUS=$CORPUS)"
cd DOCKin-spring
sg docker -c "RUN_SEARCH_EVAL=1 SEARCH_EVAL_EMBED_URL=http://localhost:58081 SEARCH_EVAL_CORPUS=$CORPUS \
    ./gradlew test --tests '*SearchQualityEvalTest' --no-daemon -q"
code=$?
ls -la build/search-eval/ "$HOME/.dockin-search-eval/" 2>/dev/null
grep -h '<testsuite' build/test-results/test/*.xml 2>/dev/null | head -2
finish $code
