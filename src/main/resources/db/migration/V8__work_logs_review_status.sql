-- P2-17-1 — 작업일지 승인·반려 루프. work_logs에 검토 상태 넷을 붙인다.
--
-- PPT 15P 화면에 `승인됨`/`반려됨` 배지가 그려져 있는데 서버에는 상태 컬럼이 없었다.
-- 관리자 코멘트(work_log_comments)만 있었고 그건 대화지 결정이 아니다.
-- absence_requests의 status/processed_by/processed_at/decision_comment와 같은 꼴이다.
-- 이름만 reviewed_*로 다르다 -- 휴가는 "처리"하고 작업일지는 "검토"한다.
--
-- 기존 행은 전부 PENDING이다. 아무도 검토한 적이 없으니 그게 사실이고, 실사용 데이터가
-- 아직 없어(PRODUCTION-READINESS H1) 대량 미승인 큐가 생기는 것도 아니다.
--
-- 상수 DEFAULT를 단 ADD COLUMN은 PG11+에서 메타데이터만 바꾼다 -- 테이블 재작성 없이
-- 순간이다(docs/db/online-ddl.md 2절, V6의 last_message_seq가 같은 경우). 100만 행
-- 벤치 테이블에서도 마찬가지라 무중단 절차 없이 간다.
--
-- status에 인덱스는 만들지 않는다. 목록은 idx_work_logs_user로 구역 멤버를 좁힌 뒤
-- 필터하면 되고, 느리면 그때 부분 인덱스(WHERE status = 'PENDING')를 재서 넣는다 --
-- V3·V4가 지킨 "실측 없이 인덱스를 늘리지 않는다"와 같은 판단.
-- reviewed_by는 users를 가리키는 FK라 V4의 원칙대로 인덱스를 붙인다 -- 없으면
-- users 한 행 삭제가 work_logs 전체를 훑는다(P2-15-7, 110.7배).

ALTER TABLE work_logs
    ADD COLUMN IF NOT EXISTS status character varying(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS reviewed_by character varying(50),
    ADD COLUMN IF NOT EXISTS reviewed_at timestamp(6) without time zone,
    ADD COLUMN IF NOT EXISTS review_comment character varying(255);

DO $$ BEGIN
    ALTER TABLE work_logs ADD CONSTRAINT work_logs_status_check
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE work_logs ADD CONSTRAINT fk_work_logs_reviewed_by
        FOREIGN KEY (reviewed_by) REFERENCES users(user_id);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE INDEX IF NOT EXISTS idx_work_logs_reviewed_by
    ON work_logs (reviewed_by);
