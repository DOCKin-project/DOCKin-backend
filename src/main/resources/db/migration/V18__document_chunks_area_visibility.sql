-- V18: 승인된 작업일지를 같은 구역에 RAG 근거로 공유한다 (ADR-0012, #174)
--
-- 전에는 작업일지·번역본 청크가 전부 OWNER라 근로자의 챗봇은 자기 일지만 근거로 썼다.
-- 앱 화면(목록·검색·상세)은 같은 구역 일지를 이미 보여주므로 RAG가 앱보다 좁았다.
-- 이제 APPROVED 일지의 청크는 AREA로, 작성자의 구역을 area에 복사해 같은 구역 근로자에게 보인다.
-- 범위를 구역 밖으로 넓히지 않는 이유는 ADR-0012 2-2(앱의 권한을 넘지 않는다).

-- 1. 구역. AREA일 때만 채운다. 길이는 users.ship_yard_area(100)와 같게.
ALTER TABLE document_chunks ADD COLUMN IF NOT EXISTS area character varying(100);

-- 2. visibility에 AREA를 허용한다. V1의 CHECK가 두 값만 알아서 그대로면 UPDATE가 실패한다.
ALTER TABLE document_chunks DROP CONSTRAINT IF EXISTS document_chunks_visibility_check;
ALTER TABLE document_chunks ADD CONSTRAINT document_chunks_visibility_check
    CHECK (visibility IN ('PUBLIC', 'OWNER', 'AREA'));

-- 3. AREA인데 area가 없으면 아무 구역에도 안 보이는 행이 된다. 조용히 사라지는 근거를 막는다.
ALTER TABLE document_chunks DROP CONSTRAINT IF EXISTS document_chunks_area_check;
ALTER TABLE document_chunks ADD CONSTRAINT document_chunks_area_check
    CHECK (visibility <> 'AREA' OR area IS NOT NULL);

-- 4. 구역 선필터. 부분 인덱스라 OWNER·PUBLIC 행은 담지 않는다.
--    HNSW가 고르는 계획에서는 쓰이지 않지만, 선택도가 낮아 플래너가 정확 최근접으로 갈 때(ADR-0006 8-4) 후보를 줄인다.
CREATE INDEX IF NOT EXISTS idx_chunk_area ON document_chunks (area) WHERE visibility = 'AREA';

-- 5. 이미 승인된 일지의 청크를 지금 맞춘다. 안 하면 다음 03:00 색인까지 OWNER로 남는다.
--    시드는 전부 PENDING이라 개발 DB에서는 0행이다.
UPDATE document_chunks c
SET visibility = 'AREA', area = u.ship_yard_area
FROM work_logs w
JOIN users u ON u.user_id = w.user_id
WHERE c.source_type = 'WORK_LOG'
  AND c.source_id = w.log_id
  AND w.status = 'APPROVED';

UPDATE document_chunks c
SET visibility = 'AREA', area = u.ship_yard_area
FROM work_log_translations t
JOIN work_logs w ON w.log_id = t.log_id
JOIN users u ON u.user_id = w.user_id
WHERE c.source_type = 'WORK_LOG_TRANSLATION'
  AND c.source_id = t.translation_id
  AND w.status = 'APPROVED';
