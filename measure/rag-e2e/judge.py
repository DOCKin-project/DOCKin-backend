#!/usr/bin/env python3
"""RAG 답변 채점 — ADR-0013 3절 지표. RagAnswerEvalTest가 남긴 answers.jsonl을 읽는다.

  JUDGE_BASE_URL=http://localhost:11434/v1 JUDGE_MODEL=qwen2.5:14b \
    python3 measure/rag-e2e/judge.py build/rag-e2e/<TAG>/answers.jsonl

무엇을 어떻게 재나
- 언어 일치: 규칙(한글·베트남어 글자 비율). LLM을 쓰지 않는다 — 결정적이고 판정기 편향이 없다.
- 답변 충실도: 판정기가 답을 주장 단위로 나누고, 주장마다 근거 자료 중 하나에라도 있는지 본다.
- 출처 정확도: 답이 어떤 주장에 자료 번호를 달았으면, 그 번호의 자료가 정말 그 주장을 담는지.
- 답 없음 처리: 답이 "기록을 찾지 못했다"는 취지인지. 답 없는 질문(N)에서는 높아야, 답 있는 질문에서는 낮아야 한다.

판정기를 그대로 믿지 않는다(ADR-0013 3절). human_check.tsv에 언어별 표본을 뽑아 두고, 사람이 다시 채점해
판정기와 맞는 비율을 같이 적는다. 의존성 없이 표준 라이브러리만 쓴다.
"""
from __future__ import annotations

import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE_URL = os.environ.get("JUDGE_BASE_URL", "http://localhost:11434/v1").rstrip("/")
MODEL = os.environ.get("JUDGE_MODEL", "qwen2.5:14b")
API_KEY = os.environ.get("JUDGE_API_KEY", "ollama")
TIMEOUT = float(os.environ.get("JUDGE_TIMEOUT", "180"))

HANGUL = re.compile(r"[가-힣]")
# 베트남어에만 있는 글자(영어·한국어에 안 나온다). 성조 결합 문자는 NFC로 합쳐진 꼴을 본다.
VIET = re.compile(r"[ăâđêôơưĂÂĐÊÔƠƯàảãáạằẳẵắặầẩẫấậèẻẽéẹềểễếệìỉĩíịòỏõóọồổỗốộờởỡớợùủũúụừửữứựỳỷỹýỵ]")
LATIN = re.compile(r"[A-Za-z]")


def detect_lang(text: str) -> str:
    """답의 언어. 자료 인용으로 한국어 설비명이 섞여도 본문 언어를 고르게 비율로 본다."""
    h, v, l = len(HANGUL.findall(text)), len(VIET.findall(text)), len(LATIN.findall(text))
    if h == 0 and v == 0 and l == 0:
        return "?"
    if h >= max(v, l) * 0.8 and h > 0:
        return "ko"
    if v >= 3 or (v > 0 and v / max(l, 1) > 0.05):
        return "vi"
    return "en"


PROMPT = """You are grading an answer from a shipyard maintenance assistant. The assistant was given numbered
evidence documents (work logs) and a question. Judge ONLY against the evidence; do not use outside knowledge.

Question ({lang}):
{question}

Evidence:
{evidence}

Answer to grade:
{answer}

Return a JSON object with exactly these keys:
- "says_not_found": true if the answer's main message is that no relevant record/information was found
  (in any language), otherwise false.
- "claims": a list (at most 8) of the factual claims the answer makes about equipment, symptoms, causes,
  actions, people, dates or procedures. Skip greetings, hedges and statements that nothing was found.
  For each claim give:
    "text": the claim, short, in English,
    "cited": the evidence numbers the ANSWER itself attributes to this claim (empty list if none),
    "supported_by_cited": true if the cited evidence states this claim (false if cited is empty),
    "supported_by_any": true if ANY evidence document states this claim.
Return only JSON."""


def call_judge(prompt: str) -> dict:
    body = json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "temperature": 0,
        "response_format": {"type": "json_object"},
    }).encode()
    req = urllib.request.Request(f"{BASE_URL}/chat/completions", body, {
        "Content-Type": "application/json", "Authorization": f"Bearer {API_KEY}"})
    last = None
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                text = json.load(r)["choices"][0]["message"]["content"]
            m = re.search(r"\{.*\}", text, re.S)
            return json.loads(m.group(0) if m else text)
        except (urllib.error.URLError, json.JSONDecodeError, KeyError, TimeoutError) as e:
            last = e
            time.sleep(2 * (attempt + 1))
    return {"error": f"{type(last).__name__}: {last}"}


def grade(row: dict) -> dict:
    reply = row.get("reply") or ""
    g = {"qid": row["qid"], "lang": row["lang"], "type": row["type"], "no_answer": row["no_answer"],
         "hit": row["hit"], "latency_ms": row["latency_ms"], "error": row.get("error"),
         "answer_lang": detect_lang(reply) if reply else "?"}
    g["lang_match"] = (g["answer_lang"] == row["lang"]) if reply else None   # 답이 없으면 언어를 셀 수 없다
    if not reply or row.get("error"):
        g["judge"] = None
        g["says_not_found"] = None
        return g
    evidence = "\n".join(f"[{s['n']}] {s.get('content') or ''}" for s in row["sources"]) or "(no evidence was provided)"
    j = call_judge(PROMPT.format(lang=row["lang"], question=row["query"], evidence=evidence, answer=reply))
    g["judge"] = j
    claims = j.get("claims") if isinstance(j, dict) else None
    if isinstance(claims, list):
        g["claims"] = len(claims)
        g["supported"] = sum(1 for c in claims if c.get("supported_by_any"))
        cited = [c for c in claims if c.get("cited")]
        g["cited_claims"] = len(cited)
        g["cited_ok"] = sum(1 for c in cited if c.get("supported_by_cited"))
    g["says_not_found"] = bool(j.get("says_not_found")) if isinstance(j, dict) and "error" not in j else None
    return g


def pct(xs):
    xs = [x for x in xs if x is not None]
    return f"{sum(xs) / len(xs):.2f}" if xs else "-"


def quantile(xs, q):
    xs = sorted(xs)
    return xs[min(len(xs) - 1, int(q * len(xs)))] if xs else 0


def summarize(rows: list[dict], tag: str) -> str:
    out = [f"# RAG 답변 채점 ({tag}, 판정기 {MODEL})", "",
           "판정기 값이다. 사람 재채점(human_check.tsv)으로 맞는 비율을 확인하기 전까지 `[미검증]`.", "",
           "| 언어 | 유형 | n | Hit@5 | 충실도 | 출처 정확도 | 모른다(답 있음) | 모른다(답 없음) | 언어 일치 | p50 ms | p95 ms |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for lang in ["ko", "en", "vi"]:
        for typ in ["T", "S", "N", "전체"]:
            sel = [r for r in rows if r["lang"] == lang and (typ == "전체" or r["type"] == typ)]
            if not sel:
                continue
            ans = [r for r in sel if not r["no_answer"]]
            none = [r for r in sel if r["no_answer"]]
            faith = [r["supported"] / r["claims"] for r in ans if r.get("claims")]
            cite = [r["cited_ok"] / r["cited_claims"] for r in ans if r.get("cited_claims")]
            lat = [r["latency_ms"] for r in sel]
            out.append("| {} | {} | {} | {} | {} | {} | {} | {} | {} | {} | {} |".format(
                lang, typ, len(sel),
                pct([r["hit"] for r in ans]),
                f"{sum(faith) / len(faith):.2f}" if faith else "-",
                f"{sum(cite) / len(cite):.2f}" if cite else "-",
                pct([r["says_not_found"] for r in ans]),
                pct([r["says_not_found"] for r in none]),
                pct([r["lang_match"] for r in sel]),
                quantile(lat, 0.5), quantile(lat, 0.95)))
    errors = [r for r in rows if r.get("error") or (isinstance(r.get("judge"), dict) and "error" in r["judge"])]
    out += ["", f"오류(생성 또는 판정): {len(errors)}건", "",
            "- 충실도: 답 있는 질문에서 주장 중 근거에 있는 비율의 평균 (목표 ≥ 0.95)",
            "- 출처 정확도: 자료 번호를 단 주장 중 그 자료가 실제로 담은 비율 (목표 ≥ 0.95)",
            "- 모른다(답 있음): 답 있는 질문을 거부한 비율 (목표 ≤ 0.10)",
            "- 모른다(답 없음): 답 없는 질문에 기록을 찾지 못했다고 답한 비율 (목표 ≥ 0.90)",
            "- 언어 일치: 규칙 판정 (목표 ≥ 0.99)"]
    return "\n".join(out) + "\n"


def main():
    path = Path(sys.argv[1])
    rows = [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    tag = path.parent.name
    graded = []
    for i, row in enumerate(rows, 1):
        g = grade(row)
        graded.append(g)
        print(f"[judge] {i}/{len(rows)} {g['qid']} {g['lang']} claims={g.get('claims')} "
              f"supported={g.get('supported')} not_found={g.get('says_not_found')} lang={g['answer_lang']}", flush=True)
    out_dir = path.parent
    with open(out_dir / "judged.jsonl", "w", encoding="utf-8") as f:
        for g in graded:
            f.write(json.dumps(g, ensure_ascii=False) + "\n")
    summary = summarize(graded, tag)
    (out_dir / "judge.md").write_text(summary, encoding="utf-8")
    # 사람 재채점 표본 — 언어마다 10건, 시드 고정
    rnd = random.Random(13)
    by_id = {(r["qid"], r["lang"]): r for r in rows}
    lines = ["qid\tlang\ttype\tjudge_supported/claims\tjudge_not_found\thuman_supported/claims\thuman_not_found\tquery\treply"]
    for lang in ["ko", "en", "vi"]:
        pool = [g for g in graded if g["lang"] == lang and g.get("judge")]
        for g in rnd.sample(pool, min(10, len(pool))):
            r = by_id[(g["qid"], g["lang"])]
            reply = (r.get("reply") or "").replace("\t", " ").replace("\n", " / ")
            lines.append(f"{g['qid']}\t{lang}\t{g['type']}\t{g.get('supported')}/{g.get('claims')}\t"
                         f"{g.get('says_not_found')}\t\t\t{r['query']}\t{reply}")
    (out_dir / "human_check.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(summary)


if __name__ == "__main__":
    main()
