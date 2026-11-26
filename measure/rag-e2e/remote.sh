#!/usr/bin/env bash
#
# RAG 끝에서 끝까지 (ADR-0013 1단계) — GPU 측정 인스턴스에서 돈다.
# g4dn.xlarge(T4 16GB, 4 vCPU, 16GB), Deep Learning Base OSS Nvidia Driver GPU AMI(AL2023): 드라이버·Docker·NVIDIA 컨테이너 툴킷이 들어 있다.
#
# 무엇을 띄우나 (전부 이 인스턴스 안, 포트는 127.0.0.1에만)
#   TEI(GPU, turing)      :58081  임베딩 — 2만 건 색인을 GPU로
#   Ollama(GPU)           :11434  생성 모델 GEN_MODELS, 판정 모델 JUDGE_MODEL
#   pylab aiserver        :8000   /api/chatbot — Spring과 같은 계약, base_url만 Ollama로(pylab ADR-0004 D5)
#   Spring(테스트 JVM)            RagAnswerEvalTest — 실제 RagChatService로 수집
#
# [실행] 로컬에서 scp로 올리고:  nohup bash remote.sh > rag-e2e.log 2>&1 &
#   끝나면 ~/RAG_E2E_DONE. 산출물 ~/DOCKin-spring/build/rag-e2e/<모델>/ — 로컬 fetch 뒤 terminate (AGENTS.md)
set -uo pipefail
BRANCH=${BRANCH:-measure/rag-e2e}
CORPUS=${CORPUS:-20000}
GEN_MODELS=${GEN_MODELS:-"qwen2.5:7b exaone3.5:7.8b"}
JUDGE_MODEL=${JUDGE_MODEL:-qwen2.5:14b}
TEI_IMAGE=${TEI_IMAGE:-ghcr.io/huggingface/text-embeddings-inference:turing-latest}
OLLAMA_IMAGE=${OLLAMA_IMAGE:-ollama/ollama:latest}
say() { printf '\n%s == %s\n' "$(date -u +%FT%TZ)" "$*"; }
finish() { echo "$1" > "$HOME/RAG_E2E_DONE"; say "끝 (exit $1)"; exit "$1"; }

say "하드 watchdog — 세션이 죽어도 4시간 뒤 꺼진다(stop. terminate는 로컬이 한다)"
sudo shutdown -h +240 || true

say "환경"
nproc; free -g | head -2; nvidia-smi --query-gpu=name,memory.total,driver_version --format=csv,noheader
sudo dnf install -y -q git java-21-amazon-corretto-devel >/dev/null
sudo systemctl enable --now docker
sudo usermod -aG docker "$USER"

say "저장소"
[[ -d DOCKin-spring ]] || git clone -q --depth 1 -b "$BRANCH" https://github.com/DOCKin-project/DOCKin-backend.git DOCKin-spring
[[ -d pylab ]] || git clone -q --depth 1 https://github.com/Khyojae/pylab.git pylab
git -C DOCKin-spring log --oneline -1; git -C pylab log --oneline -1

say "TEI ($TEI_IMAGE)"
sudo docker rm -f eval-embed >/dev/null 2>&1
sudo docker run -d --name eval-embed --gpus all -p 127.0.0.1:58081:80 "$TEI_IMAGE" \
    --model-id=intfloat/multilingual-e5-small --auto-truncate >/dev/null
for i in $(seq 1 120); do curl -sf http://localhost:58081/health >/dev/null && break; sleep 5; done
curl -sf http://localhost:58081/health >/dev/null || { say "TEI가 안 떴다"; sudo docker logs --tail 30 eval-embed; finish 2; }

say "Ollama ($OLLAMA_IMAGE) — 모델 받기"
sudo docker rm -f ollama >/dev/null 2>&1
sudo docker run -d --name ollama --gpus all -p 127.0.0.1:11434:11434 -v ollama:/root/.ollama "$OLLAMA_IMAGE" >/dev/null
for i in $(seq 1 60); do curl -sf http://localhost:11434/api/version >/dev/null && break; sleep 2; done
for m in $GEN_MODELS $JUDGE_MODEL; do sudo docker exec ollama ollama pull "$m" >/dev/null && echo "pulled $m" || { say "$m 받기 실패"; finish 3; }; done
sudo docker exec ollama ollama list; sudo docker inspect eval-embed ollama --format "{{.Name}} {{.Image}}"

say "pylab aiserver 준비"
command -v uv >/dev/null || curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null 2>&1
export PATH="$HOME/.local/bin:$PATH"
(cd pylab && uv sync -q --all-packages) || { say "uv sync 실패"; finish 4; }

serve() { # $1 생성 모델
  pkill -f "bin/aiserver" || true; sleep 2
  (cd pylab && AISERVER_PRELOAD='[]' AISERVER_STT_PRELOAD=false \
     AISERVER_CHAT_MODEL="$1" AISERVER_CHAT_BASE_URL=http://127.0.0.1:11434/v1 AISERVER_CHAT_API_KEY=ollama \
     AISERVER_CHAT_TIMEOUT_SECONDS=120 nohup uv run aiserver > "$HOME/aiserver-${1//[:\/]/_}.log" 2>&1 &)
  for i in $(seq 1 60); do curl -sf http://127.0.0.1:8000/health >/dev/null && break; sleep 2; done
  curl -s http://127.0.0.1:8000/health; echo
  # 모델을 GPU에 올려 둔다 — 첫 질문이 적재 시간까지 재지 않게
  curl -s -o /dev/null -w "warmup %{http_code} %{time_total}s\n" http://127.0.0.1:8000/api/chatbot \
    -H 'content-type: application/json' -d '{"messages":[{"role":"user","content":"안녕하세요"}],"lang":"ko"}'
}

( while true; do echo "$(date +%T) $(nvidia-smi --query-gpu=utilization.gpu,memory.used --format=csv,noheader)"; sleep 10; done ) > "$HOME/gpu.log" 2>&1 &

cd DOCKin-spring
code=0
for m in $GEN_MODELS; do
  say "수집 — $m"
  serve "$m"
  sg docker -c "RUN_RAG_EVAL=1 SEARCH_EVAL_EMBED_URL=http://localhost:58081 SEARCH_EVAL_CORPUS=$CORPUS \
      RAG_EVAL_AI_URL=http://127.0.0.1:8000 RAG_EVAL_TAG=${m//[:\/]/_} \
      ./gradlew test --tests '*RagAnswerEvalTest' --no-daemon -q" || code=$?
  cp "$HOME/aiserver-${m//[:\/]/_}.log" "build/rag-e2e/${m//[:\/]/_}/" 2>/dev/null
done
pkill -f "bin/aiserver" || true

say "채점 — 판정 모델 $JUDGE_MODEL"
for m in $GEN_MODELS; do
  f="build/rag-e2e/${m//[:\/]/_}/answers.jsonl"
  [[ -s $f ]] || { echo "없음: $f"; code=5; continue; }
  JUDGE_BASE_URL=http://127.0.0.1:11434/v1 JUDGE_MODEL="$JUDGE_MODEL" python3 measure/rag-e2e/judge.py "$f" \
      > "build/rag-e2e/${m//[:\/]/_}/judge.log" 2>&1 || code=6
  tail -25 "build/rag-e2e/${m//[:\/]/_}/judge.log"
done
cp "$HOME/gpu.log" build/rag-e2e/ 2>/dev/null
ls -la build/rag-e2e/*/
finish $code
