-- P2-15-4 — work_logs를 참조하는 자식 테이블의 FK 컬럼에 인덱스를 만든다.
--
-- PostgreSQL은 외래키를 걸면 참조되는 쪽(부모 PK)의 인덱스는 요구하지만
-- 참조하는 쪽(자식 FK 컬럼)의 인덱스는 만들어 주지 않는다. 그래서 아래 둘이 비쌌다.
--
--   1. 부모 삭제 — 부모 행을 지울 때마다 "이 행을 참조하는 자식이 있는가"를 확인해야 하고,
--      인덱스가 없으면 자식 테이블을 통째로 훑는다. 지우는 행마다, 자식 테이블마다.
--      벤치 뒷정리에서 98만 행 DELETE 하나가 24분을 넘긴 것이 이것이다.
--
--   2. 목록 조회 — 이쪽이 더 중요하다. WorkLogListQueryCountTest가 센 "행당 1쿼리"가
--      SELECT * FROM work_log_images WHERE work_log_id = ? 이고, 인덱스가 없으면
--      그 20번이 전부 순차 스캔이다. 쿼리 개수(P2-15-1)와 쿼리 비용이 같은 곳에서 만난다.
--
-- CONCURRENTLY를 쓰지 않는 이유: Flyway는 마이그레이션을 트랜잭션 안에서 돌리는데
-- CREATE INDEX CONCURRENTLY는 트랜잭션 안에서 실행할 수 없다. 두 테이블 모두 작고
-- (자식 테이블이지 원본이 아니다) 배포 중 짧은 쓰기 잠금은 감수할 수 있다.
-- work_logs 본체에 인덱스를 만들 때는 이 판단을 다시 해야 한다 — 100만 행이다.

-- 벤치마크(WorkLogListBenchmarkTest)가 뒷정리용으로 만들었다가 남기고 간 인덱스를 걷어낸다.
-- 실행이 중간에 끊기면 남는데, 이름만 다를 뿐 아래에서 만들 것과 같은 인덱스라
-- 두면 같은 컬럼에 인덱스가 둘이 된다. 개발 DB에만 있으므로 IF EXISTS로 둔다.
--
-- 이것들이 남아 있었다는 것 자체가 한 번 확인된 사실이다 -- ddl-auto=validate도
-- SchemaValidationTest도 인덱스는 보지 않으므로, 정리 도구가 만든 인덱스가 남아
-- "운영 스키마인 것처럼" 보여도 아무도 알려주지 않는다.
DROP INDEX IF EXISTS cleanup_idx_work_log_images_log;
DROP INDEX IF EXISTS cleanup_idx_work_log_comments_log;
DROP INDEX IF EXISTS cleanup_idx_log_images_log;

CREATE INDEX IF NOT EXISTS idx_work_log_images_work_log
    ON work_log_images (work_log_id);

CREATE INDEX IF NOT EXISTS idx_work_log_comments_log
    ON work_log_comments (log_id);

-- work_log_translations.log_id는 이미 인덱스가 있다.
-- uk_log_lang UNIQUE (log_id, language_code)의 선두 컬럼이 log_id라 FK 검사에 그대로 쓰인다.
-- 그래서 여기에는 아무것도 만들지 않는다 -- 만들면 같은 일을 하는 인덱스가 둘이 된다.


-- log_images를 지운다 — 살아 있는 테이블이 아니었다.
--
-- 2026-02-07 b67134f가 "사진 여러 장 저장을 위해" work_log_images를 새로 만들면서
-- 이 테이블을 대체했다. 그런데 앞의 것을 지우지 않아 같은 개념의 테이블이 둘 남았고,
-- LogImage 엔티티는 그 뒤로 자기 파일 밖에서 참조가 0건이다(리포지토리도 서비스도 없다).
-- P0-2에서 지운 ChatHistory와 같은 형태다.
--
-- 컬럼명 imgae_id(image 오타)가 엔티티 필드 imgaeId에서 그대로 내려온 것도
-- 이 테이블이 한 번도 쓰이지 않았다는 방증이다.
--
-- 지우지 않고 인덱스만 붙이면 안 되는 이유: 안 쓰는 테이블에 인덱스가 붙어 있으면
-- 다음 사람에게 쓰이는 것처럼 보인다. 이 저장소가 계속 잡아 온 "선언과 실제의 불일치"를
-- 하나 더 만드는 셈이다.
--
-- 행이 있으면 지우지 않고 실패시킨다. 로컬에서 0건인 것은 확인했지만 이 마이그레이션은
-- 내가 못 보는 환경에서도 돈다. 2b의 ALTER ... USING이 "행이 남아 있으면 NOT NULL 위반으로
-- 실패해 실수로 임베딩을 날리지 못한다"고 한 것과 같은 장치다.
DO $$
DECLARE
    remaining bigint;
BEGIN
    IF to_regclass('public.log_images') IS NULL THEN
        RETURN;
    END IF;

    SELECT count(*) INTO remaining FROM log_images;

    IF remaining > 0 THEN
        RAISE EXCEPTION
            'log_images에 행이 % 건 있다. 이 테이블은 work_log_images로 대체된 것으로 판단해 '
            '삭제하려 했으나, 데이터가 있다면 그 판단이 틀렸다. '
            '옮길 것인지 버릴 것인지 정한 뒤 이 마이그레이션을 다시 실행하라.', remaining;
    END IF;

    DROP TABLE log_images;
END $$;
