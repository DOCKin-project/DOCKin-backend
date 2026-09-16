"""FastAPI /api/translate 건당 지연·동시성 벤치. 채팅 길이 한국어 문장.

측정: (1) 순차 ko->en p50/p95/p99, (2) ko->en->vi 피벗(2호출) 지연,
     (3) 동시성 1/2/4/8에서 처리량과 지연 — 어디서 무릎이 나오나.
"""
import asyncio, statistics, time
import httpx

BASE = "http://127.0.0.1:8000"
# TBM(작업 전 회의) 지시·현장 채팅 꼴. 10~45자.
SENTENCES = [
    "안전모 착용하세요",
    "오늘 3번 도크 용접 작업 오전 10시에 시작합니다",
    "크레인 작업 중이니 아래로 지나가지 마세요",
    "점심 후에 블록 조립 이어서 하겠습니다",
    "산소통 압력 확인하고 보고해 주세요",
    "비 오니까 고소 작업은 오후로 미룹니다",
    "장갑 새것 창고에 있어요",
    "어제 작업일지 아직 안 올린 사람 있나요",
    "안전 교육 2시 회의실에서 합니다",
    "지금 바로 사무실로 와주세요",
]


def pct(xs, p):
    xs = sorted(xs)
    k = max(0, min(len(xs) - 1, round(p / 100 * (len(xs) - 1))))
    return xs[k]


def summarize(name, ms):
    print(f"{name:<34} n={len(ms):<4} p50={pct(ms,50):7.0f}  p95={pct(ms,95):7.0f}  p99={pct(ms,99):7.0f}  max={max(ms):7.0f}  mean={statistics.mean(ms):7.0f} ms")


async def translate(client, text, src, tgt):
    body = {"title": "-", "text": text, "source": src, "target": tgt, "traceId": "bench"}
    t = time.perf_counter()
    r = await client.post(f"{BASE}/api/translate", json=body)
    r.raise_for_status()
    return (time.perf_counter() - t) * 1000, r.json()["translated"]


async def sequential(client, n, src, tgt):
    ms = []
    for i in range(n):
        d, _ = await translate(client, SENTENCES[i % len(SENTENCES)], src, tgt)
        ms.append(d)
    return ms


async def pivot(client, n):
    ms = []
    for i in range(n):
        t = time.perf_counter()
        _, en = await translate(client, SENTENCES[i % len(SENTENCES)], "ko", "en")
        _, vi = await translate(client, en, "en", "vi")
        ms.append((time.perf_counter() - t) * 1000)
    return ms


async def concurrent(client, conc, total):
    sem = asyncio.Semaphore(conc)
    ms = []

    async def one(i):
        async with sem:
            d, _ = await translate(client, SENTENCES[i % len(SENTENCES)], "ko", "en")
            ms.append(d)

    t = time.perf_counter()
    await asyncio.gather(*(one(i) for i in range(total)))
    wall = time.perf_counter() - t
    return ms, total / wall


async def main():
    async with httpx.AsyncClient(timeout=120) as client:
        # 워밍업 — 모델은 이미 메모리에 있지만 첫 몇 건은 캐시·스레드풀 비용이 섞인다
        await sequential(client, 5, "ko", "en")
        await sequential(client, 3, "en", "vi")

        print("== 문장 길이:", [len(s) for s in SENTENCES], "자")
        print()
        ms = await sequential(client, 40, "ko", "en")
        summarize("ko->en 직접 (순차)", ms)
        ms = await pivot(client, 20)
        summarize("ko->en->vi 피벗 2호출 (순차)", ms)
        print()
        print("== 동시성 (ko->en, 총 40건)")
        for c in (1, 2, 4, 8):
            ms, rps = await concurrent(client, c, 40)
            summarize(f"동시 {c}", ms)
            print(f"{'':<34} 처리량 = {rps:.2f} req/s")

        # 번역 품질 눈으로 보기
        print()
        print("== 표본 (ko->en->vi)")
        for s in SENTENCES[:4]:
            _, en = await translate(client, s, "ko", "en")
            _, vi = await translate(client, en, "en", "vi")
            print(f"  {s}\n    -> {en}\n    -> {vi}")


asyncio.run(main())
