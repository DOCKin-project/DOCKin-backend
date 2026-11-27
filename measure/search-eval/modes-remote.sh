#!/usr/bin/env bash
#
# 검색 방식 비교(RetrievalModeEvalTest — 벡터/하이브리드/재순위) 원격 실행. #210, #213, #203.
# g4dn.xlarge(T4), Deep Learning Base OSS Nvidia Driver GPU AMI(AL2023). 재순위 모델(568M)이 CPU로는 몇 시간이라 GPU.
#
#   TEI 임베딩  :58081  intfloat/multilingual-e5-small
#   TEI 재순위  :8082   BAAI/bge-reranker-v2-m3 (compose.rerank.yaml과 같은 모델)
#
# [실행] scp로 올리고:  nohup bash modes-remote.sh > modes.log 2>&1 &
#   끝나면 ~/MODES_DONE. 산출물 ~/DOCKin-spring/build/search-eval-modes/ — 로컬 fetch 뒤 terminate (AGENTS.md)
set -uo pipefail
BRANCH=${BRANCH:-measure/retrieval-modes}
CORPUS=${CORPUS:-20000}
TEI_IMAGE=${TEI_IMAGE:-ghcr.io/huggingface/text-embeddings-inference:turing-latest}
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }
finish() { echo "$1" > "$HOME/MODES_DONE"; say "끝 (exit $1)"; exit "$1"; }

say "하드 watchdog — 2시간 뒤 꺼진다(stop. terminate는 로컬이 한다)"
sudo shutdown -h +120 || true

say "환경"
nproc; free -g | head -2; nvidia-smi --query-gpu=name,memory.total --format=csv,noheader
sudo dnf install -y -q git java-21-amazon-corretto-devel >/dev/null
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

say "저장소 ($BRANCH)"
[[ -d DOCKin-spring ]] || git clone -q --depth 1 -b "$BRANCH" https://github.com/DOCKin-project/DOCKin-backend.git DOCKin-spring
git -C DOCKin-spring log --oneline -1

say "TEI 임베딩·재순위"
sudo docker rm -f eval-embed eval-rerank >/dev/null 2>&1
sudo docker run -d --name eval-embed --gpus all -p 127.0.0.1:58081:80 "$TEI_IMAGE" \
    --model-id=intfloat/multilingual-e5-small --auto-truncate >/dev/null
sudo docker run -d --name eval-rerank --gpus all -p 127.0.0.1:8082:80 "$TEI_IMAGE" \
    --model-id=BAAI/bge-reranker-v2-m3 --auto-truncate >/dev/null
for i in $(seq 1 120); do curl -sf localhost:58081/health >/dev/null && curl -sf localhost:8082/health >/dev/null && break; sleep 5; done
curl -sf localhost:58081/health >/dev/null && curl -sf localhost:8082/health >/dev/null || { sudo docker logs --tail 20 eval-rerank; finish 2; }
sudo docker inspect eval-embed eval-rerank --format '{{.Name}} {{.Config.Image}} {{.Image}}'
curl -s localhost:8082/rerank -H 'content-type: application/json' \
  -d '{"query":"용접기 송급 롤러","texts":["CO2 용접기 송급 롤러 교체","지게차 타이어 점검"]}'; echo

( while true; do echo "$(date +%T) $(nvidia-smi --query-gpu=utilization.gpu,memory.used --format=csv,noheader)"; sleep 10; done ) > "$HOME/gpu.log" 2>&1 &

say "측정 (SEARCH_EVAL_CORPUS=$CORPUS)"
cd DOCKin-spring
sg docker -c "RUN_MODE_EVAL=1 SEARCH_EVAL_EMBED_URL=http://localhost:58081 RERANKER_SERVER_URL=http://localhost:8082 \
    SEARCH_EVAL_CORPUS=$CORPUS ./gradlew test --tests '*RetrievalModeEvalTest' --no-daemon -q"
code=$?
cp "$HOME/gpu.log" build/search-eval-modes/ 2>/dev/null
grep -h '<testsuite' build/test-results/test/*.xml 2>/dev/null | head -2
finish $code
