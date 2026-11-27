#!/usr/bin/env bash
#
# 재순위 서버(TEI CPU, BAAI/bge-reranker-v2-m3)를 운영과 같은 CPU 상한(2개)으로 띄워 최대 메모리와 지연을 잰다.
# compose.rerank.yaml의 메모리 상한 3G에서 로컬 컨테이너가 OOMKilled로 죽어(2026-11-28) 실제 값을 알아야 한다.
# m7i.xlarge(4 vCPU, 16GB), AL2023. 산출물 ~/rerank-cpu/ — 로컬 fetch 뒤 terminate (AGENTS.md)
#
#   nohup bash rerank-cpu-remote.sh > rerank-cpu.log 2>&1 &     끝나면 ~/RERANK_CPU_DONE
set -uo pipefail
IMAGE=${IMAGE:-ghcr.io/huggingface/text-embeddings-inference:cpu-latest}
OUT=$HOME/rerank-cpu
mkdir -p "$OUT"
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }
finish() { echo "$1" > "$HOME/RERANK_CPU_DONE"; say "끝 (exit $1)"; exit "$1"; }
sudo shutdown -h +90 || true

say "환경"
nproc; free -g | head -2; lscpu | grep 'Model name'
sudo dnf install -y -q docker >/dev/null && sudo systemctl enable --now docker

peak_of() { # 컨테이너 cgroup v2 memory.peak (바이트)
  local id; id=$(sudo docker inspect -f '{{.Id}}' "$1")
  sudo cat "/sys/fs/cgroup/system.slice/docker-$id.scope/memory.peak" 2>/dev/null || echo "?"
}

run() { # $1 이름, $2 메모리 상한(빈 값이면 없음), 나머지 TEI 인자
  local name=$1 mem=$2; shift 2
  sudo docker rm -f "$name" >/dev/null 2>&1
  sudo docker run -d --name "$name" --cpus 2 ${mem:+--memory $mem} -p 127.0.0.1:8082:80 -v reranker_cache:/data \
      "$IMAGE" --model-id=BAAI/bge-reranker-v2-m3 --auto-truncate "$@" >/dev/null
  local t0=$SECONDS
  for i in $(seq 1 180); do
    curl -sf localhost:8082/health >/dev/null && { echo "$name: healthy, ${SECONDS}-${t0}s 걸림 $((SECONDS - t0))s"; return 0; }
    [ "$(sudo docker inspect -f '{{.State.Status}}' "$name")" = exited ] && {
      echo "$name: 죽음 OOMKilled=$(sudo docker inspect -f '{{.State.OOMKilled}}' "$name")"; return 1; }
    sleep 5
  done
  echo "$name: 시간 초과"; return 1
}

say "1. 상한 없이 — 모델 받기·적재·예열의 최대 메모리"
run rr-free "" | tee -a "$OUT/memory.txt"
echo "rr-free 기동 뒤 memory.peak: $(peak_of rr-free) bytes" | tee -a "$OUT/memory.txt"

say "2. 지연 — 질문 10개 × 3회, 후보 20개 (운영 rag.search.rerank.candidates)"
python3 rerank_cpu_latency.py http://localhost:8082 3 | tee "$OUT/latency.txt"
echo "rr-free 측정 뒤 memory.peak: $(peak_of rr-free) bytes" | tee -a "$OUT/memory.txt"
sudo docker stats --no-stream --format 'rr-free 지금 {{.MemUsage}}' rr-free | tee -a "$OUT/memory.txt"
sudo docker rm -f rr-free >/dev/null

say "3. 상한을 걸고 뜨는가 — 3G(지금 설정) / 4G / 5G / 6G"
for m in 3g 4g 5g 6g; do
  run "rr-$m" "$m" | tee -a "$OUT/memory.txt"
  echo "rr-$m memory.peak: $(peak_of rr-$m) bytes" | tee -a "$OUT/memory.txt"
  sudo docker rm -f "rr-$m" >/dev/null
done

say "4. 배치 상한을 줄이면 — --max-batch-tokens 4096, 상한 없이"
run rr-b4096 "" --max-batch-tokens 4096 | tee -a "$OUT/memory.txt"
echo "rr-b4096 memory.peak: $(peak_of rr-b4096) bytes" | tee -a "$OUT/memory.txt"
python3 rerank_cpu_latency.py http://localhost:8082 1 | tee "$OUT/latency-b4096.txt"
sudo docker rm -f rr-b4096 >/dev/null

sudo docker inspect -f '{{.Id}}' "$IMAGE" > "$OUT/image.txt" 2>/dev/null; sudo docker images --digests | grep text-embeddings >> "$OUT/image.txt"
finish 0
