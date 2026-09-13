-- V1: pgvector 확장 + document_chunks (ADR-0006 Phase 2b)
--
-- 이 마이그레이션이 존재하는 이유는 Hibernate ddl-auto가 할 수 없는 일이 둘이기 때문이다.
--
--   ① CREATE EXTENSION vector
--      없으면 vector(384) 컬럼 생성이 실패한다. 그런데 ddl-auto=update는 DDL 오류를
--      로그만 남기고 기동을 막지 않아, 테이블이 없는 채로 앱이 정상 기동한 것처럼 보인다.
--      (2a에서 SafetyCourse의 columnDefinition="DATETIME"이 정확히 이렇게 숨어 있었다.)
--
--   ② HNSW 인덱스
--      @Index는 btree만 만들 수 있고 HNSW 문법을 모른다. 빠뜨려도 에러가 나지 않고
--      전체 스캔으로 조용히 돌아가므로 몇 달 뒤에나 눈치챈다.
--
-- docker-entrypoint-initdb.d로는 해결되지 않는다 -- 그것은 컨테이너가 빈 데이터 디렉터리를
-- 초기화할 때 실행되므로 ①은 되지만 ②는 안 된다(그 시점에는 테이블이 아직 없다).
-- 게다가 볼륨이 이미 있으면 아예 돌지 않는다.
--
-- Flyway는 Spring Boot가 JPA EntityManagerFactory보다 먼저 실행하므로
-- ① -> 테이블 -> ② 순서가 한 번에 보장된다.
--
-- 범위: document_chunks만 Flyway가 관리하고 나머지 테이블은 여전히 ddl-auto=update가 만든다.
-- 그래서 모든 문장이 멱등(IF NOT EXISTS)이며, 기존 DB에서도 안전하게 다시 돌 수 있다.
-- ddl-auto를 validate로 낮추지 않은 이유도 같다 -- 나머지 20여 개 테이블은 아직
-- 마이그레이션으로 옮기지 않았으므로, 빈 DB에서 validate를 걸면 기동이 실패한다.

-- ① 확장. 이미지에 확장 파일은 들어 있으나 데이터베이스 등록은 별개다.
CREATE EXTENSION IF NOT EXISTS vector;

-- 시퀀스. DocumentChunk가 GenerationType.SEQUENCE에 allocationSize=50이다.
-- IDENTITY는 INSERT 직후 생성 키를 읽어야 해서 Hibernate가 JDBC 배치를 포기한다
-- (MySQL 시절 10,000건 저장에 INSERT 문장이 정확히 10,000개였다).
-- INCREMENT 50이 allocationSize와 어긋나면 키가 충돌하므로 반드시 같아야 한다.
CREATE SEQUENCE IF NOT EXISTS document_chunk_seq INCREMENT BY 50 START WITH 1;

-- 테이블. 컬럼 순서와 제약 이름을 Hibernate가 생성한 것과 맞춰,
-- ddl-auto=update가 뒤이어 돌 때 아무 차이도 발견하지 못하게 한다.
CREATE TABLE IF NOT EXISTS document_chunks (
    chunk_id        bigint                 NOT NULL,
    chunk_index     integer                NOT NULL,
    content         text                   NOT NULL,
    content_hash    character varying(64)  NOT NULL,
    created_at      timestamp(6) without time zone,
    -- 차원을 384로 못박은 것은 HNSW 인덱스가 고정 차원 컬럼에만 걸리기 때문이다.
    -- 그 대가로 차원이 다른 모델의 청크가 공존할 수 없다(BYTEA 시절에는 가능했다).
    embedding       vector(384)            NOT NULL,
    embedding_dim   integer                NOT NULL,
    embedding_model character varying(64)  NOT NULL,
    language_code   character varying(10),
    owner_user_id   character varying(50),
    source_id       bigint                 NOT NULL,
    source_type     character varying(32)  NOT NULL,
    updated_at      timestamp(6) without time zone,
    visibility      character varying(16)  NOT NULL,

    CONSTRAINT document_chunks_pkey PRIMARY KEY (chunk_id),
    -- 재색인 시 UPSERT 기준 키. 모델을 포함해야 모델 교체분과 기존분이 공존할 수 있다.
    CONSTRAINT uk_chunk_source UNIQUE (source_type, source_id, chunk_index, embedding_model),
    CONSTRAINT document_chunks_source_type_check
        CHECK (source_type IN ('WORK_LOG', 'WORK_LOG_TRANSLATION', 'SAFETY_COURSE', 'CHECKLIST_ITEM')),
    CONSTRAINT document_chunks_visibility_check
        CHECK (visibility IN ('PUBLIC', 'OWNER'))
);

-- FK를 걸지 않는다. work_logs / work_log_translations / safety_courses 등 여러 원본을
-- (source_type, source_id)로 다형 참조하므로 단일 FK로 표현할 수 없다.
-- 원본 삭제 시 정리는 IndexingService 책임이며 idx_chunk_source가 그 조회를 받는다.
CREATE INDEX IF NOT EXISTS idx_chunk_source     ON document_chunks (source_type, source_id);
CREATE INDEX IF NOT EXISTS idx_chunk_model      ON document_chunks (embedding_model);
CREATE INDEX IF NOT EXISTS idx_chunk_visibility ON document_chunks (visibility, owner_user_id);

-- ② HNSW. vector_cosine_ops인 이유는 검색 쿼리가 <=>(코사인 거리)를 쓰기 때문이다 --
-- 연산자 클래스가 쿼리의 연산자와 맞아야 인덱스를 탄다.
-- m / ef_construction은 기본값(16 / 64). 실측 없이 조정하지 않는다.
--
-- 빌드 비용 주의: 10만 청크에서 3~5분, 인덱스 크기 195MB(테이블과 맞먹는다).
-- 빈 테이블에서는 즉시 끝나므로 신규 환경에서는 부담이 없다.
-- 대량 데이터가 이미 있는 DB에 이 마이그레이션이 처음 도는 경우에만 기동이 그만큼 지연된다.
--
-- maintenance_work_mem은 여기서 올리지 않는다. 기본 64MB에서는 약 28,000건부터
-- 디스크 기반 빌드로 떨어지지만(NOTICE로 알려준다), 올리면 병렬 빌드가 Docker의
-- /dev/shm 기본 64MB를 넘겨 실패한다. 대량 재색인 시에는
-- SET maintenance_work_mem='256MB'; SET max_parallel_maintenance_workers=0;
-- 을 준 세션에서 수동으로 다시 만드는 편이 낫다(ADR-0006 8-2).
CREATE INDEX IF NOT EXISTS idx_chunk_embedding_hnsw
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
