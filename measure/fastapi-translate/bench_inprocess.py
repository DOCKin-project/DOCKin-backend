"""모델을 직접 불러 API 비용을 분해한다: 제목 '-' 오버헤드, beam 4 vs greedy(1), 문장 길이."""
import time, statistics, torch
from transformers import MarianMTModel, MarianTokenizer

print("torch threads:", torch.get_num_threads())
name = "Helsinki-NLP/opus-mt-ko-en"
tok = MarianTokenizer.from_pretrained(name)
model = MarianMTModel.from_pretrained(name)
model.eval()

S = [
    "안전모 착용하세요",
    "오늘 3번 도크 용접 작업 오전 10시에 시작합니다",
    "크레인 작업 중이니 아래로 지나가지 마세요",
    "점심 후에 블록 조립 이어서 하겠습니다",
    "산소통 압력 확인하고 보고해 주세요",
    "비 오니까 고소 작업은 오후로 미룹니다",
    "장갑 새것 창고에 있어요",
    "어제 작업일지 아직 안 올린 사람 있나요",
]


def gen(text, beams):
    inputs = tok(text, return_tensors="pt", padding=True, truncation=True, max_length=256)
    t = time.perf_counter()
    with torch.inference_mode():
        out = model.generate(**inputs, max_length=256, num_beams=beams, early_stopping=True)
    ms = (time.perf_counter() - t) * 1000
    return ms, tok.batch_decode(out, skip_special_tokens=True)[0]


def run(label, texts, beams, reps=3):
    ms = []
    for _ in range(reps):
        for s in texts:
            d, _ = gen(s, beams)
            ms.append(d)
    ms.sort()
    print(f"{label:<40} n={len(ms):<3} p50={ms[len(ms)//2]:6.0f}  max={ms[-1]:6.0f}  mean={statistics.mean(ms):6.0f} ms")


for s in S[:3]:
    gen(s, 4)  # warm

run("제목 '-' 만 (beam 4)", ["-"], 4, reps=8)
run("본문 beam 4 (현재 설정)", S, 4)
run("본문 beam 1 greedy", S, 1)
run("본문 beam 2", S, 2)

print()
print("== 8문장 배치 한 번에 (beam 4) — 언어별 1회 호출을 배치로 묶을 때")
inputs = tok(S, return_tensors="pt", padding=True, truncation=True, max_length=256)
t = time.perf_counter()
with torch.inference_mode():
    out = model.generate(**inputs, max_length=256, num_beams=4, early_stopping=True)
ms = (time.perf_counter() - t) * 1000
print(f"배치 8건: {ms:.0f} ms 총, 건당 {ms/len(S):.0f} ms")

print()
print("== greedy 품질 표본")
for s in S[:4]:
    _, a = gen(s, 4)
    _, b = gen(s, 1)
    print(f"  {s}\n    beam4 : {a}\n    greedy: {b}")
