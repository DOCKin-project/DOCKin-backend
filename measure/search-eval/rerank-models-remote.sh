#!/usr/bin/env bash
#
# 작은 다국어 재순위 모델 비교 — 품질(GPU, RetrievalModeEvalTest vector·rerank)과 CPU 지연(같은 호스트, --cpus 2). #223.
# bge-reranker-v2-m3는 CPU 2개에서 후보 20개 8.4초라 운영에서 못 켠다. 더 작은 모델이 품질을 얼마나 지키며 얼마나 빠른가.
# g4dn.xlarge(T4, Xeon 4 vCPU), Deep Learning Base OSS Nvidia Driver GPU AMI(AL2023).
#
#   nohup bash rerank-models-remote.sh > rerank-models.log 2>&1 &     끝나면 ~/MODELS_DONE
#   산출물 ~/DOCKin-spring/build/search-eval-modes/<모델>/, ~/rerank-cpu/<모델>.txt — 로컬 fetch 뒤 terminate
set -uo pipefail
BRANCH=${BRANCH:-measure/rerank-small-models}
CORPUS=${CORPUS:-20000}
MODELS=${MODELS:-"BAAI/bge-reranker-v2-m3 Alibaba-NLP/gte-multilingual-reranker-base jinaai/jina-reranker-v2-base-multilingual cross-encoder/mmarco-mMiniLMv2-L12-H384-v1"}
GPU_IMAGE=${GPU_IMAGE:-ghcr.io/huggingface/text-embeddings-inference:turing-latest}
CPU_IMAGE=${CPU_IMAGE:-ghcr.io/huggingface/text-embeddings-inference:cpu-latest}
CPU_OUT=$HOME/rerank-cpu
mkdir -p "$CPU_OUT"
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }
finish() { echo "$1" > "$HOME/MODELS_DONE"; say "끝 (exit $1)"; exit "$1"; }
tag() { echo "${1//\//_}"; }

say "하드 watchdog — 150분 뒤 꺼진다"
sudo shutdown -h +150 || true

say "환경"
nproc; free -g | head -2; lscpu | grep 'Model name'; nvidia-smi --query-gpu=name,memory.total --format=csv,noheader
sudo dnf install -y -q git java-21-amazon-corretto-devel >/dev/null
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"
[[ -d DOCKin-spring ]] || git clone -q --depth 1 -b "$BRANCH" https://github.com/DOCKin-project/DOCKin-backend.git DOCKin-spring
git -C DOCKin-spring log --oneline -1

say "TEI 임베딩 (GPU)"
sudo docker rm -f eval-embed >/dev/null 2>&1
sudo docker run -d --name eval-embed --gpus all -p 127.0.0.1:58081:80 "$GPU_IMAGE" \
    --model-id=intfloat/multilingual-e5-small --auto-truncate >/dev/null
for i in $(seq 1 120); do curl -sf localhost:58081/health >/dev/null && break; sleep 5; done

wait_up() { # $1 컨테이너
  for i in $(seq 1 120); do
    curl -sf localhost:8082/health >/dev/null && return 0
    [ "$(sudo docker inspect -f '{{.State.Status}}' "$1")" = exited ] && return 1
    sleep 5
  done
  return 1
}
peak_of() { local id; id=$(sudo docker inspect -f '{{.Id}}' "$1"); sudo cat "/sys/fs/cgroup/system.slice/docker-$id.scope/memory.peak" 2>/dev/null || echo "?"; }

code=0
for m in $MODELS; do
  t=$(tag "$m")
  say "[$m] 품질 — GPU"
  sudo docker rm -f rr >/dev/null 2>&1
  sudo docker run -d --name rr --gpus all -p 127.0.0.1:8082:80 -v rr_cache:/data "$GPU_IMAGE" \
      --model-id="$m" --auto-truncate --max-batch-tokens 4096 >/dev/null
  if ! wait_up rr; then
    echo "[$m] GPU에서 뜨지 않음 — 건너뜀"; sudo docker logs --tail 15 rr 2>&1 | tee "$CPU_OUT/$t.unsupported.txt"; code=5; continue
  fi
  curl -s localhost:8082/rerank -H 'content-type: application/json' \
      -d '{"query":"용접기 송급 롤러","texts":["CO2 용접기 송급 롤러 교체","지게차 타이어 점검"]}'; echo
  (cd DOCKin-spring && sg docker -c "RUN_MODE_EVAL=1 MODE_EVAL_MODES=vector,rerank MODE_EVAL_TAG=$t \
      SEARCH_EVAL_EMBED_URL=http://localhost:58081 RERANKER_SERVER_URL=http://localhost:8082 SEARCH_EVAL_CORPUS=$CORPUS \
      ./gradlew test --tests '*RetrievalModeEvalTest' --no-daemon -q") || code=6
  grep -A12 "^| rerank | ko | 전체\|^## 2" "DOCKin-spring/build/search-eval-modes/$t/summary.md" 2>/dev/null | head -3
  sudo docker rm -f rr >/dev/null

  say "[$m] CPU 지연 — --cpus 2, max-batch-tokens 4096"
  sudo docker run -d --name rr --cpus 2 -p 127.0.0.1:8082:80 -v rr_cache:/data "$CPU_IMAGE" \
      --model-id="$m" --auto-truncate --max-batch-tokens 4096 >/dev/null
  if wait_up rr; then
    { echo "model: $m"; lscpu | grep 'Model name'; python3 rerank_cpu_latency.py http://localhost:8082 3;
      echo "memory.peak: $(peak_of rr) bytes"; sudo docker stats --no-stream --format 'now {{.MemUsage}}' rr; } | tee "$CPU_OUT/$t.txt"
  else
    echo "[$m] CPU에서 뜨지 않음"; sudo docker logs --tail 15 rr 2>&1 | tee "$CPU_OUT/$t.cpu-failed.txt"
  fi
  sudo docker rm -f rr >/dev/null
done

sudo docker images --digests | grep text-embeddings > "$CPU_OUT/images.txt"
finish $code
