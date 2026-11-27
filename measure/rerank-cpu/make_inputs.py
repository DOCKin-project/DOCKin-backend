"""재순위 CPU 지연 측정 입력 — 질문 360개(E1 평가셋 ko/en/vi) × 후보 청크 20개.

후보 = PR #220 `vector` 방식이 실제로 고른 근거 5개 + 코퍼스에서 무작위 15개(시드 고정).
cross-encoder의 시간은 내용이 아니라 토큰 길이로 정해지므로, 나머지 15개는 같은 코퍼스에서 무작위로 채운다.
청크는 운영 청킹(FixedSizeChunkingStrategy 500자)에 맞춰 앞 500자로 자른다.

사용: python -I make_inputs.py <corpus-text.tsv> <modes per_query.tsv> <queries.tsv> > inputs.jsonl
  corpus-text.tsv — CorpusSeedGeneratorTest(Random 1L)로 다시 만든 일지 본문(log_id, title, log_text)
"""
import csv, json, random, sys

csv.field_size_limit(10**7)
corpus = {int(r["log_id"]): r["log_text"][:500]
          for r in csv.DictReader(open(sys.argv[1], encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE)}
top = {}
for r in csv.DictReader(open(sys.argv[2], encoding="utf-8"), delimiter="\t"):
    if r["mode"] == "vector":
        top[(r["qid"], r["lang"])] = [int(x) for x in r["log_ids"].strip("[]").split(",") if x.strip() not in ("", "null")]
rnd = random.Random(23)
ids = sorted(corpus)
out = sys.stdout
out.reconfigure(encoding="utf-8")
for r in csv.DictReader(open(sys.argv[3], encoding="utf-8"), delimiter="\t", quoting=csv.QUOTE_NONE):
    picked = list(dict.fromkeys(top.get((r["qid"], r["lang"]), [])))
    while len(picked) < 20:
        x = rnd.choice(ids)
        if x not in picked:
            picked.append(x)
    out.write(json.dumps({"qid": r["qid"], "lang": r["lang"], "type": r["type"], "query": r["query"],
                          "texts": [corpus[i] for i in picked]}, ensure_ascii=False) + "\n")
