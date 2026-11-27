#!/usr/bin/env python3
"""재순위 CPU 지연 — 운영과 같은 모양(질문 1개 + 후보 20개)으로 TEI /rerank를 부른다.

  python3 measure/search-eval/rerank_cpu_latency.py http://localhost:8082 [회차]

후보 본문은 CorpusSeedGeneratorTest와 같은 문장 틀(설비·호기·부품·증상·원인·조치)로 만든다. 길이가 비슷해야
지연이 비슷하다 — 실제 청크는 대략 200~500자다. 질문은 E1 평가셋 꼴로 언어 셋을 섞는다.
"""
import json
import random
import statistics
import sys
import time
import urllib.request

URL = sys.argv[1].rstrip("/") if len(sys.argv) > 1 else "http://localhost:8082"
ROUNDS = int(sys.argv[2]) if len(sys.argv) > 2 else 3

EQUIP = ["CO2 용접기", "겐트리 크레인", "플라즈마 절단기", "지게차", "고소작업대", "무인 도장설비", "에어 컴프레서"]
PART = ["유압 실린더", "송급 롤러", "베어링부", "안전 스위치", "냉각 팬", "제어반"]
SYMP = ["누유가 확인되었다", "간헐적으로 정지했다", "과전류로 차단되었다", "이상 진동이 발생했다", "이상 소음이 발생했다"]
CAUSE = ["윤활 부족", "필터 막힘", "절연 저하", "체결 볼트 이완", "냉각수 부족"]
ACTION = ["그리스를 보충했다", "개스킷을 신품으로 교체했다", "필터를 청소했다", "규정 토크로 재체결했다", "배선을 교체했다"]
QUERIES = [
    "겐트리 크레인 3호기 유압 실린더 누유",
    "용접기가 자꾸 멈춰",
    "Abnormal vibration in CO2 welder wire feed roller",
    "What did they do when the crane cylinder leaked oil?",
    "Rung động bất thường ở con lăn cấp liệu của máy hàn CO2",
    "Xe nâng bị chảy dầu thì xử lý thế nào?",
    "필터 막혀서 교체한 기록",
    "지게차 베어링 소음",
    "Plasma cutter tripped by overcurrent",
    "Máy nén khí dừng liên tục",
]


def passage(rnd: random.Random) -> str:
    e, u, p, s = rnd.choice(EQUIP), rnd.randint(1, 9), rnd.choice(PART), rnd.choice(SYMP)
    c, a, m = rnd.choice(CAUSE), rnd.choice(ACTION), rnd.choice([10, 20, 30])
    text = (f"{e} {u}호기 {p}에서 {s}. 점검 결과 {c}이(가) 원인으로 확인되었다. {a}. "
            f"조치 후 {m}분간 시운전하여 이상 없음을 확인하였다. 다음 정기 점검 때 같은 부위를 다시 확인하기로 하였다.")
    if rnd.random() < 0.2:   # 다섯 건 중 하나는 긴 본문(부품·증상 두 쌍) — 생성기와 같다
        text += f" 추가로 {rnd.choice(PART)}에서 {rnd.choice(SYMP)}. {rnd.choice(ACTION)}."
    return text


def rerank(query: str, texts: list[str]) -> float:
    body = json.dumps({"query": query, "texts": texts, "raw_scores": False, "truncate": True}).encode()
    req = urllib.request.Request(f"{URL}/rerank", body, {"Content-Type": "application/json"})
    t = time.perf_counter()
    with urllib.request.urlopen(req, timeout=300) as r:
        r.read()
    return (time.perf_counter() - t) * 1000


def main():
    rnd = random.Random(7)
    cands = [[passage(rnd) for _ in range(20)] for _ in QUERIES]
    print(f"후보 길이: 평균 {statistics.mean(len(t) for c in cands for t in c):.0f}자")
    rerank(QUERIES[0], cands[0][:2])   # 첫 호출(모델 적재·캐시) 제외
    ms = []
    for _ in range(ROUNDS):
        for q, c in zip(QUERIES, cands):
            ms.append(rerank(q, c))
    ms.sort()
    p = lambda x: ms[min(len(ms) - 1, int(x * len(ms)))]
    print(f"질문 {len(QUERIES)} × {ROUNDS}회 = {len(ms)}건, 후보 20개씩")
    print(f"p50 {p(0.5):.0f}ms  p95 {p(0.95):.0f}ms  max {ms[-1]:.0f}ms")
    for n in (10, 5):
        t = [rerank(q, c[:n]) for q, c in zip(QUERIES, cands)]
        t.sort()
        print(f"후보 {n}개: p50 {t[len(t) // 2]:.0f}ms  max {t[-1]:.0f}ms")


if __name__ == "__main__":
    main()
