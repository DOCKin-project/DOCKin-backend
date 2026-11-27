#!/usr/bin/env bash
#
# 밤 23 두 번째 — remote.sh(운영과 같은 cpus 2.0·memory 3G)에서 TEI가 워밍업 중 cgroup OOM으로 죽었다
# (anon-rss 3.13GB, dmesg). 배치 토큰 상한(--max-batch-tokens, 기본 16384)을 낮춰 3G 안에 뜨는지 보고,
# 뜨는 설정으로 지연을 잰다. 비교로 메모리 상한을 6G로 풀고 기본 배치 토큰에서 워밍업 최대 메모리와 일부 지연을 잰다.
set -uo pipefail
TEI_IMAGE=ghcr.io/huggingface/text-embeddings-inference:cpu-latest
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }

up() { # $1 메모리 $2 max-batch-tokens(빈 값=기본) → 0이면 떴다
  sudo docker rm -f rerank >/dev/null 2>&1
  local extra=(); [[ -n "$2" ]] && extra=(--max-batch-tokens "$2")
  sudo docker run -d --name rerank --cpus 2.0 --memory "$1" -p 127.0.0.1:8082:80 "$TEI_IMAGE" \
      --model-id=BAAI/bge-reranker-v2-m3 --auto-truncate "${extra[@]}" >/dev/null
  ( while sudo docker inspect rerank >/dev/null 2>&1; do sudo docker stats --no-stream --format '{{.MemUsage}}' rerank; sleep 2; done ) > "mem-$1-${2:-default}.log" 2>&1 &
  for i in $(seq 1 120); do
    curl -sf http://localhost:8082/health >/dev/null && return 0
    [[ "$(sudo docker inspect rerank --format '{{.State.Status}}')" == exited ]] && break
    sleep 5
  done
  echo "oom=$(sudo docker inspect rerank --format '{{.State.OOMKilled}}')"; return 1
}

ok=""
for t in 4096 2048 1024; do
  say "3G + max-batch-tokens $t"
  if up 3g $t; then ok=$t; echo "뜸"; break; fi
done
[[ -z "$ok" ]] && { say "3G에서 못 띄움"; echo 3 > ~/NIGHT23_DONE; exit 3; }
sleep 20; echo "워밍업 뒤 메모리: $(tail -1 mem-3g-$ok.log)"
say "측정 — 3G, max-batch-tokens $ok (전체)"
python3 bench.py inputs.jsonl http://localhost:8082 > latency-3g-$ok.tsv
echo "측정 중 최대 메모리 표본: $(sort -k1 -h mem-3g-$ok.log | tail -1)"

say "비교 — 6G, 기본 배치 토큰"
if up 6g ""; then
  sleep 20; echo "워밍업 뒤 메모리: $(tail -1 mem-6g-default.log)"
  head -121 inputs.jsonl > inputs-120.jsonl
  python3 bench.py inputs-120.jsonl http://localhost:8082 > latency-6g-default.tsv
fi
say "끝"; echo 0 > ~/NIGHT23_DONE
