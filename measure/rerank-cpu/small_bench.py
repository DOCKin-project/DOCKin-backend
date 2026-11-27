"""후보 N개 재순위 지연 — 질문 파일 하나로(밤 23 마지막 비교, 6G·배치 4096). 사용: python3 small_bench.py inputs-30.jsonl 20,5"""
import json, sys, time, urllib.request
rows = [json.loads(l) for l in open(sys.argv[1], encoding="utf-8")]
ns = [int(x) for x in sys.argv[2].split(",")]
def call(q, t):
    b = json.dumps({"query": q, "texts": t, "raw_scores": False, "truncate": True}).encode()
    s = time.perf_counter()
    urllib.request.urlopen(urllib.request.Request("http://localhost:8082/rerank", b, {"Content-Type": "application/json"}), timeout=300).read()
    return (time.perf_counter() - s) * 1000
for r in rows[:3]:
    call(r["query"], r["texts"][:5])
print("qid\tlang\ttype\tn\tconcurrency\tms")
for n in ns:
    for r in rows:
        print("%s\t%s\t%s\t%d\t1\t%.1f" % (r["qid"], r["lang"], r["type"], n, call(r["query"], r["texts"][:n])), flush=True)
