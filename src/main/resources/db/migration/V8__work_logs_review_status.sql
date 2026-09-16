-- P2-17-1 — 작업일지 승인·반려. PPT 15P의 `승인됨`/`반려됨` 배지에 서버가 답이 없었다.
--
-- 상태 셋(PENDING/APPROVED/REJECTED)과 검토자·시각·코멘트. AbsenceRequest의
-- status/processed_by/processed_at/decision_comment와 같은 모양이라 관리자 화면이 두 도메인을
-- 같은 방식으로 다룰 수 있다.
--
-- 기존 행은 전부 PENDING이다 — 검토된 적이 없으니 사실에 맞다. APPROVED로 backfill하면
-- "검토 안 한 것을 승인됨으로 표시"가 된다(2026-09-16 결정).
--
-- 무중단(docs/db/online-ddl.md):
--   ADD COLUMN ... NOT NULL DEFAULT 'PENDING'  PG11+는 상수 DEFAULT를 메타데이터만 바꾼다. 행을 안 쓴다
--   FK는 NOT VALID로 걸고 VALIDATE           VALIDATE는 SHARE UPDATE EXCLUSIVE라 쓰기를 안 막는다.
--                                           지금은 새 컬럼이라 검사할 행이 0이지만 절차를 절차대로
SET LOCAL lock_timeout = '2s';

ALTER TABLE work_logs
    ADD COLUMN status         varchar(20)  NOT NULL DEFAULT 'PENDING',
    ADD COLUMN reviewed_by    varchar(50),
    ADD COLUMN reviewed_at    timestamp(6),
    ADD COLUMN review_comment varchar(500);

ALTER TABLE work_logs
    ADD CONSTRAINT fk_work_logs_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (user_id) NOT VALID;
ALTER TABLE work_logs VALIDATE CONSTRAINT fk_work_logs_reviewed_by;

-- 인덱스는 넣지 않는다. 관리자 대기 목록은 (같은 구역 작성자, status='PENDING', 커서)인데
-- 선택도가 얼마인지 잰 적이 없다 — P2-15-8이 복합 인덱스를 측정 없이 넣지 않기로 한 것과 같다.
-- 대기가 쌓여 느려지면 그때 partial index (status) WHERE status = 'PENDING'을 CONCURRENTLY로.
