-- ADR-0006 Phase 2b — document_chunks.embedding: BYTEA -> pgvector vector(384)
--
-- 이미 만들어진 DB(dockin_db_data 볼륨)에 적용할 때 쓴다.
-- 새로 만드는 환경은 db/init/01-pgvector.sql이 자동으로 실행되므로 이 파일이 필요 없다.
--
-- 실행:
--   docker exec -i dockin-db psql -U root -d dockindb < docs/migration/2b-pgvector.sql

-- 1. 확장 등록. 이미지에 파일은 있으나 DB에 등록하는 것은 별개다.
CREATE EXTENSION IF NOT EXISTS vector;

-- 2. 기존 청크를 버린다.
--
-- BYTEA를 vector로 캐스팅할 방법이 없다. 저장된 값은 리틀엔디언 float32를 눕힌 바이트 뭉치이고,
-- pgvector는 '[0.1,0.2,...]' 텍스트 표현으로 입력받는다. SQL 안에서 바이트를 float로 되돌리려면
-- 384개 오프셋을 하나씩 뜯어야 하는데, 그 노력을 들일 이유가 없다 --
-- document_chunks는 work_logs / safety_courses 등에서 파생된 캐시성 데이터이고,
-- 원본이 그대로 남아 있으므로 다시 만들면 된다.
--
-- 주의: content_hash 기반 멱등 재색인은 "내용이 같으면 임베딩을 건너뛴다"는 최적화다.
-- 행 자체를 지우면 비교 대상이 없어져 전 건이 다시 임베딩된다(실측 12ms/건).
DELETE FROM document_chunks;

-- 3. 컬럼 타입 교체.
--
-- ddl-auto=update는 컬럼을 추가하기만 하고 기존 컬럼의 타입은 바꾸지 않는다.
-- bytea인 채로 두면 Hibernate가 vector로 읽으려다 실패하므로 여기서 직접 바꾼다.
--
-- USING이 필요하다. PostgreSQL은 행이 0건이어도 "bytea -> vector 변환 규칙이 있는가"를
-- DDL 시점에 검사하고, 없으면 거부한다(cannot be cast automatically).
-- 값을 옮기지 않겠다는 뜻이므로 NULL을 명시한다.
--
-- 이 문장은 2단계를 빠뜨리면 실패한다 -- 남아 있는 행이 NULL이 되어 NOT NULL을 위반하기 때문이다.
-- 실수로 실제 임베딩을 조용히 날리지 않게 막아주는 안전장치이므로 그대로 둔다.
ALTER TABLE document_chunks
    ALTER COLUMN embedding TYPE vector(384) USING NULL::vector(384);

-- 4. 재색인
--
-- 애플리케이션을 기동하고 IndexingService 배치를 돌린다(기본 매일 03:00, rag.indexing.cron).
-- 즉시 확인하려면 cron을 앞당기거나 배치를 직접 호출한다.
--
-- 확인:
--   SELECT count(*), min(vector_dims(embedding)) FROM document_chunks;
--
-- HNSW 인덱스는 여기서 만들지 않는다. 유사도 계산이 아직 애플리케이션에 있어
-- 인덱스를 걸어도 타지 않는다. 인덱스는 검색 쿼리를 DB로 옮기는 단계에서 함께 만든다.
