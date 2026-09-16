"""같은 opus-mt ko-en 모델을 CTranslate2(int8)로 변환해 transformers와 건당 지연을 비교한다.
팀원 서버에 faster-whisper가 있어 ctranslate2는 이미 의존성이다 — '엔진만 바꾸면 몇 배'의 숫자."""
import os, time, statistics, subprocess, sys
import ctranslate2, transformers

NAME = "Helsinki-NLP/opus-mt-ko-en"
OUT = "ct2-opus-mt-ko-en-int8"
if not os.path.isdir(OUT):
    subprocess.run([sys.executable, "-m", "ctranslate2.converters.transformers",
                    "--model", NAME, "--output_dir", OUT, "--quantization", "int8"], check=True)

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
tok = transformers.MarianTokenizer.from_pretrained(NAME)
threads = int(os.environ.get("CT2_THREADS", "0"))  # 0 = 라이브러리 기본
tr = ctranslate2.Translator(OUT, device="cpu", compute_type="int8", inter_threads=1, intra_threads=threads)


def one(text, beams):
    toks = tok.convert_ids_to_tokens(tok.encode(text))
    t = time.perf_counter()
    res = tr.translate_batch([toks], beam_size=beams, max_decoding_length=256)
    ms = (time.perf_counter() - t) * 1000
    return ms, tok.decode(tok.convert_tokens_to_ids(res[0].hypotheses[0]), skip_special_tokens=True)


for s in S[:3]:
    one(s, 4)
for beams in (4, 1):
    ms = sorted(one(s, beams)[0] for _ in range(3) for s in S)
    print(f"CT2 int8 beam {beams:<2} n={len(ms)} p50={ms[len(ms)//2]:6.0f}  max={ms[-1]:6.0f}  mean={statistics.mean(ms):6.0f} ms")

print("== 품질 표본 (CT2 int8 beam 4)")
for s in S[:4]:
    print(f"  {s} -> {one(s, 4)[1]}")

# 동시 처리: inter_threads=N 이 요청 N개를 병렬로 받는 CT2 방식
import concurrent.futures
for inter in (1, 2, 4):
    trN = ctranslate2.Translator(OUT, device="cpu", compute_type="int8", inter_threads=inter, intra_threads=threads)
    def call(s):
        toks = tok.convert_ids_to_tokens(tok.encode(s))
        return trN.translate_batch([toks], beam_size=4, max_decoding_length=256)
    t = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=inter) as ex:
        list(ex.map(call, [S[i % len(S)] for i in range(24)]))
    wall = time.perf_counter() - t
    print(f"CT2 inter_threads={inter}: 24건 {wall:.1f}s → {24/wall:.2f} req/s")
