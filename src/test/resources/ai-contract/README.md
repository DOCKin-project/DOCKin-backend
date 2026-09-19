# FastAPI 응답 표본 — Spring↔팀원 서버 경계의 계약 (#96)

`FastApiContractTest`가 이 파일들을 그대로 되돌려주며 Spring DTO가 필드를 전부 읽는지 확인한다.
필드 이름이 어긋나면(#75의 `text` vs `logText`) 컴파일도 CI도 통과하고 붙여 돌려야만 드러나던 것을
여기서 떨어지게 한다.

## 출처

팀원 저장소 `DOCKin-project/DOCKin-aiserver` **`eaf9a28`** (2026-03-21) `app/schemas/*.py`의 pydantic 모델을
손으로 옮겼다. 표본 갱신일: **2026-09-19**. 서버가 OpenAPI를 내지 않아(확인 안 됨) 코드가 진실이다.

| 표본 | 서버 모델 | 경로 |
|---|---|---|
| `chatbot-response.json` | `ChatResponse` | `POST /api/chatbot` |
| `translate-response.json` | `TranslateResponse` | `POST /api/translate` (별칭 `/api/ai/translate`) |
| `stt-response.json` | `SttResponse` | `POST /api/worklogs/stt` (별칭 `/api/work-logs/stt`) |
| `error-detail.json` | `HTTPException(detail={...})` — FastAPI가 `{"detail": {...}}`로 감싼다 | 4xx·5xx 공통 |

## 갱신하는 법

```bash
for f in chat stt translate; do
  gh api repos/DOCKin-project/DOCKin-aiserver/contents/app/schemas/$f.py --jq .content | base64 -d
done
gh api repos/DOCKin-project/DOCKin-aiserver/commits --jq '.[0].sha[:7]'
```
모델이 바뀌면 표본을 고치고 이 파일의 커밋 해시·갱신일을 올린다. 표본이 낡는 문제는 남는다 —
서버가 OpenAPI를 내면 그 스펙에서 생성하는 쪽으로 옮긴다.
