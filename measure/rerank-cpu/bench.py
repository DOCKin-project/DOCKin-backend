"""TEI /rerank 지연 — 질문 하나에 후보 N개를 한 요청으로(운영 RerankClient와 같은 꼴: raw_scores=false, truncate=true).

후보 수 20(운영 기본) / 10 / 5, 그리고 동시 2요청(질문 두 개가 겹칠 때)을 잰다. 결과는 TSV 한 줄에 요청 하나.
사용: python3 bench.py inputs.jsonl http://localhost:8082 > latency.tsv
"""
import json, sys, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

rows = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8")]
url = sys.argv[2].rstrip("/") + "/rerank"

def call(q, texts):
    body = json.dumps({"query": q, "texts": texts, "raw_scores": False, "truncate": True}).encode()
    t = time.perf_counter()
    r = urllib.request.urlopen(urllib.request.Request(url, body, {"Content-Type": "application/json"}), timeout=300).read()
    ms = (time.perf_counter() - t) * 1000
    assert len(json.loads(r)) == len(texts)
    return ms

for r in rows[:10]:                       # 데움 — 첫 요청의 모델 적재·할당을 빼려고
    call(r["query"], r["texts"])
print("qid\tlang\ttype\tn\tconcurrency\tms")
for n in (20, 10, 5):
    for r in rows:
        print(f"{r['qid']}\t{r['lang']}\t{r['type']}\t{n}\t1\t{call(r['query'], r['texts'][:n]):.1f}", flush=True)
sub = rows[::3]                           # 동시 2 — 120 질문을 두 개씩 겹쳐 보낸다
with ThreadPoolExecutor(2) as ex:
    for r, ms in zip(sub, ex.map(lambda r: call(r["query"], r["texts"]), sub)):
        print(f"{r['qid']}\t{r['lang']}\t{r['type']}\t20\t2\t{ms:.1f}", flush=True)
