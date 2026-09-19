-- P2-19-1 작업일지 번역 캐시 — 저장된 번역을 돌려주기 시작하면서 어느 모델이 낸 번역인지가 값이 된다.
--
-- 지금까지 work_log_translations는 쓰기만 하고 읽지 않았다(RAG 색인 제외). FastAPI 응답의 model은
-- 클라이언트에게 그대로 지나갔고 표에는 남지 않았다. 캐시 히트 응답도 미스 때와 같은 꼴이어야 하므로
-- model을 남긴다. 같은 int8 모델이 호스트마다 다른 문장을 낸 기록(AWS-MEASUREMENT-RESULTS 밤 13)이 있어,
-- 어느 모델이 낸 번역이 굳어 있는지는 나중에 표만 보고 알 수 있어야 한다.
--
-- 기존 행은 NULL — 어느 모델이었는지 모른다. 그 행은 원문이 같으면 그대로 히트가 되고 model만 null로 나간다.
-- 번호가 9인 것은 V8(작업일지 승인 status, PR #76)이 먼저 열려 있어서다. #76을 먼저 머지한다.
ALTER TABLE work_log_translations ADD COLUMN IF NOT EXISTS model character varying(100);
