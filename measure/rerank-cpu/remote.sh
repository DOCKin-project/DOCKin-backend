#!/usr/bin/env bash
#
# 밤 23 — 재순위(bge-reranker-v2-m3) CPU 지연. c7i.xlarge(4 vCPU) 한 대, 운영 compose.rerank.yaml과 같은 상한(cpus 2.0, memory 3G).
#
# [왜] PR #220은 GPU(T4)에서 질문당 +0.2~0.35초를 쟀다. 운영은 CPU(i3-6100)이고, RerankClient 타임아웃은 5초다
#   (external-api.reranker.response-timeout-ms). 넘으면 운영은 재순위 없이 답한다 — 켜도 효과가 조용히 사라진다.
# [왜 AWS] 로컬 노트북은 다른 컨테이너가 CPU를 거의 다 쓰고 있어 지연이 간섭과 섞인다.
# [실행] inputs.jsonl·bench.py와 함께 scp로 올리고: nohup bash remote.sh > night23.log 2>&1 &   끝나면 ~/NIGHT23_DONE
set -uo pipefail
TEI_IMAGE=${TEI_IMAGE:-ghcr.io/huggingface/text-embeddings-inference:cpu-latest}
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }
finish() { echo "$1" > "$HOME/NIGHT23_DONE"; say "끝 (exit $1)"; exit "$1"; }

say "하드 watchdog — 2시간 뒤 꺼진다(stop. terminate는 로컬이 한다)"
sudo shutdown -h +120 || true

say "환경"
nproc; free -g | head -2; lscpu | grep -E "Model name|Thread|Core|MHz" | head -5
sudo dnf install -y -q docker >/dev/null
sudo systemctl enable --now docker

say "TEI 재순위 ($TEI_IMAGE) — compose.rerank.yaml과 같은 상한"
sudo docker rm -f rerank >/dev/null 2>&1
sudo docker run -d --name rerank --cpus 2.0 --memory 3g -p 127.0.0.1:8082:80 "$TEI_IMAGE" \
    --model-id=BAAI/bge-reranker-v2-m3 --auto-truncate >/dev/null
for i in $(seq 1 180); do curl -sf http://localhost:8082/health >/dev/null && break; sleep 5; done
curl -sf http://localhost:8082/health >/dev/null || { say "TEI가 안 떴다"; sudo docker logs --tail 40 rerank; finish 2; }
sudo docker inspect rerank --format 'image {{.Image}}'
sudo docker logs rerank 2>&1 | grep -iE "max_batch|threads|backend|onnx|candle" | head -8

( while true; do echo "$(date +%T) $(sudo docker stats --no-stream --format '{{.CPUPerc}} {{.MemUsage}}' rerank)"; sleep 10; done ) > "$HOME/stats.log" 2>&1 &

say "측정"
python3 bench.py inputs.jsonl http://localhost:8082 > latency.tsv
code=$?
wc -l latency.tsv
finish $code
