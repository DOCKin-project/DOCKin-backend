#!/usr/bin/env bash
# m7i.2xlarge에서 팀원 FastAPI(DOCKin-aiserver)를 그대로 띄우고 M7 벤치를 돌린다.
set -euo pipefail
cd "$HOME"
echo "== host: $(nproc) vCPU, $(lscpu | grep 'Model name' | sed 's/.*: *//'), $(free -g | awk '/Mem/{print $2}')GB"
sudo dnf install -y -q git >/dev/null 2>&1 || true
if ! command -v uv >/dev/null; then curl -LsSf https://astral.sh/uv/install.sh | sh >/dev/null 2>&1; fi
export PATH="$HOME/.local/bin:$PATH"

[[ -d aiserver ]] || git clone -q --depth 1 https://github.com/DOCKin-project/DOCKin-aiserver.git aiserver
cd aiserver
uv venv -q -p 3.12 .venv
uv pip install -q -p .venv/bin/python --index-url https://download.pytorch.org/whl/cpu torch
uv pip install -q -p .venv/bin/python fastapi "uvicorn[standard]" pydantic-settings transformers sentencepiece sacremoses faster-whisper openai python-multipart httpx ctranslate2
.venv/bin/python -c "import torch; print('torch', torch.__version__, 'threads', torch.get_num_threads())"

# 서버 (uvicorn 단일 프로세스 — 팀원 README와 같다)
pkill -f "uvicorn app.main" || true
nohup .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8000 --log-level warning > uvicorn.log 2>&1 &
for i in $(seq 1 60); do curl -sf http://127.0.0.1:8000/health >/dev/null && break; sleep 3; done
echo "== server up"

# 모델 내려받기(첫 호출)
for pair in "ko en" "en vi"; do set -- $pair
  curl -s -o /dev/null -w "[$1->$2 first call] %{http_code} %{time_total}s\n" -H 'Content-Type: application/json' \
    -d "{\"title\":\"-\",\"text\":\"안전모 착용하세요\",\"source\":\"$1\",\"target\":\"$2\",\"traceId\":\"warm\"}" http://127.0.0.1:8000/api/translate
done
echo "== setup done"
