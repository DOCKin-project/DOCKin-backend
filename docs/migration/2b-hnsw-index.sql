-- ADR-0006 Phase 2b — document_chunks.embedding에 HNSW 인덱스 생성
--
-- 실행:
--   docker exec -i dockin-db psql -U root -d dockindb < docs/migration/2b-hnsw-index.sql
--
-- 선행 조건: docs/migration/2b-pgvector.sql (확장 등록 + 컬럼 타입 교체)
--
-- 이 인덱스는 Hibernate가 관리하지 않는다. @Index는 btree만 만들 수 있고 HNSW 문법을 모른다.
-- db/init/에 넣을 수도 없다 — 그건 컨테이너 최초 기동 시 실행되는데 그 시점에는
-- Hibernate가 아직 테이블을 만들기 전이다. 즉 이 파일을 잊으면 인덱스가 조용히 없는 상태가 된다.

-- 빌드 설정. 아래 두 줄이 없으면 4분 56초, 있으면 3분 09초다(10만 청크 실측).
--
-- maintenance_work_mem: 기본 64MB에서는 28,363건 만에 그래프가 넘쳐 디스크 기반 빌드로 떨어진다
--   ("hnsw graph no longer fits into maintenance_work_mem" NOTICE로 알려준다).
--
-- max_parallel_maintenance_workers = 0: 병렬 빌드를 끈다.
--   PostgreSQL의 병렬 워커는 스레드가 아니라 별도 프로세스라 메모리를 공유하려면
--   DSM(동적 공유 메모리)이 필요하고, 리눅스에서 그것은 /dev/shm에 잡힌다.
--   Docker는 /dev/shm을 컨테이너 메모리 한도와 무관하게 기본 64MB로 준다.
--   그래서 maintenance_work_mem을 256MB로 올리면 병렬 빌드가 이렇게 실패한다:
--     ERROR: could not resize shared memory segment ... No space left on device
--   (디스크가 아니라 RAM 기반 tmpfs가 꽉 찼다는 뜻이다. 메시지가 오해를 부른다.)
--
--   compose.yaml에 shm_size를 키우는 대안도 있으나 택하지 않았다 —
--   tmpfs 페이지도 컨테이너 메모리 cgroup에 잡히는데 한도가 512MB뿐이라
--   shared_buffers 128MB + DSM 256MB면 빌드 중 OOM 위험이 실재한다.
--   인덱스 빌드는 재색인 때나 도는 작업이지 상시 경로가 아니므로,
--   상시 메모리 압박을 만들면서까지 병렬을 살릴 이유가 없다.
SET maintenance_work_mem = '256MB';
SET max_parallel_maintenance_workers = 0;

-- vector_cosine_ops인 이유: RetrievalService가 코사인 유사도를 쓴다.
-- 연산자 클래스가 쿼리의 연산자(<=>)와 맞아야 인덱스를 탄다.
-- TEI가 정규화된 벡터를 반환하므로 내적(vector_ip_ops)도 수학적으로는 같은 순위를 주지만,
-- 정규화 여부가 모델/설정에 따라 달라질 수 있어 코사인으로 맞춘다(RetrievalService.cosine 주석과 같은 판단).
--
-- m / ef_construction은 기본값(16 / 64)을 쓴다. 실측 없이 조정하지 않는다.
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
    ON document_chunks USING hnsw (embedding vector_cosine_ops);

-- 확인
--   SELECT indexname, pg_size_pretty(pg_relation_size(indexname::regclass))
--   FROM pg_indexes WHERE tablename = 'document_chunks';
--
-- 10만 청크에서 인덱스 크기는 195MB로 테이블(195MB)과 맞먹는다.
-- shared_buffers가 128MB이므로 둘 다 캐시에 상주할 수 없다 — 재기동 직후 첫 질의는
-- 콜드 캐시라 133ms가 걸리고, 데워지면 1.6ms로 떨어진다(실측).

-- 주의: 검색 쿼리가 아직 애플리케이션에서 코사인을 계산하므로 이 인덱스는 현재 쓰이지 않는다.
-- RetrievalService를 SQL ORDER BY <=> 로 옮기는 단계에서 비로소 타기 시작한다.
