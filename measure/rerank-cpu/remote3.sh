#!/usr/bin/env bash
# 밤 23 세 번째 — 3G·max-batch-tokens 1024에서 후보 20개가 질문당 p50 약 15초로 나와, 전체(1,200요청)는 watchdog 안에 못 끝난다.
# 후보 20개는 46요청으로 멈추고, 질문 60개(평가셋 6개마다 1개)로 후보 10·5개, 그리고 6G·기본 배치 토큰의 후보 20·5개를 잰다.
set -uo pipefail
TEI_IMAGE=ghcr.io/huggingface/text-embeddings-inference:cpu-latest
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }
awk 'NR%6==1' inputs.jsonl > inputs-60.jsonl
run() { # $1 후보 수들, $2 출력
  python3 - "$1" inputs-60.jsonl > "$2" <<'PY'
import json, sys, time, urllib.request
ns = [int(x) for x in sys.argv[1].split(",")]
rows = [json.loads(l) for l in open(sys.argv[2], encoding="utf-8")]
def call(q, t):
    b = json.dumps({"query": q, "texts": t, "raw_scores": False, "truncate": True}).encode()
    s = time.perf_counter()
    urllib.request.urlopen(urllib.request.Request("http://localhost:8082/rerank", b, {"Content-Type": "application/json"}), timeout=300).read()
    return (time.perf_counter() - s) * 1000
for r in rows[:3]: call(r["query"], r["texts"][:5])
print("qid\tlang\ttype\tn\tconcurrency\tms")
for n in ns:
    for r in rows:
        print(f"{r['qid']}\t{r['lang']}\t{r['type']}\t{n}\t1\t{call(r['query'], r['texts'][:n]):.1f}", flush=True)
PY
}
say "3G · 1024 — 후보 10·5 (질문 60)"
run 10,5 latency-3g-1024-small.tsv
say "6G · 기본 배치 토큰"
sudo docker rm -f rerank >/dev/null 2>&1
sudo docker run -d --name rerank --cpus 2.0 --memory 6g -p 127.0.0.1:8082:80 "$TEI_IMAGE" --model-id=BAAI/bge-reranker-v2-m3 --auto-truncate >/dev/null
( while sudo docker inspect rerank >/dev/null 2>&1; do sudo docker stats --no-stream --format '{{.MemUsage}}' rerank; sleep 2; done ) > mem-6g-default.log 2>&1 &
for i in $(seq 1 120); do curl -sf http://localhost:8082/health >/dev/null && break; sleep 5; done
echo "워밍업 최대 메모리: $(sort -h mem-6g-default.log | tail -1)"
run 20,5 latency-6g-default.tsv
say "끝"; echo 0 > ~/NIGHT23_DONE
