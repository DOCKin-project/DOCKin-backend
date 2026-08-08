-- P2-15-7 — users를 참조하는 자식 테이블의 FK 컬럼에 인덱스를 만든다.
--
-- V3가 work_logs의 자식들에 한 것과 같은 일이다. 다른 점은 층위다 --
-- users는 이 스키마에서 거의 모든 것이 매달린 부모이고, 참조하는 FK 9개 중
-- 인덱스가 있는 것은 attendance.user_id 하나뿐이었다.
--
-- 어떻게 드러났나: P2-15-5 벤치마크의 뒷정리에서 벤치 사용자 500명을 지우는 DELETE가
-- 5분을 넘겼다. 사용자 한 명을 지울 때마다 자식 테이블 여덟 개를 각각 통째로 훑는데,
-- 그중 work_logs는 그 시점에 힙이 780MB였다. 500 x 780MB다.
--
-- 이 문제는 벤치마크 전용이 아니다. 퇴사자 처리나 계정 삭제가 같은 경로를 탄다.

-- work_logs.user_id — 단일 컬럼으로 만든다.
--
-- 두 가지를 한꺼번에 해결한다.
--   1. users 삭제 시 FK 검사 (이 마이그레이션의 목적)
--   2. 목록 API의 user_id IN (...) 필터. P2-15-5 실측에서 100만 행 기준
--      구역 목록 COUNT가 4,047ms -> 12.8ms(315배)였다.
--
-- 복합 인덱스((user_id, created_at DESC) 등)는 여기 넣지 않는다. 같은 실측에서
-- 정렬 붙은 첫 페이지가 오히려 느려진 결과가 나왔는데, 그 측정에 캐시 오염이 섞여 있어
-- (아무 인덱스도 쓸 수 없는 LIKE %kw% 항목까지 함께 느려졌다) 원인을 아직 분리하지 못했다.
-- 근거가 확정되기 전에 인덱스를 늘리지 않는다 -- ADR-0002가 경계한 "실측 없이 튜닝했다"가
-- 된다. 복합 인덱스 판단은 P2-15-8로 남긴다.
CREATE INDEX IF NOT EXISTS idx_work_logs_user
    ON work_logs (user_id);

-- 나머지 일곱 개. 전부 users(user_id)를 가리키는 varchar(50) FK다.
-- 지금은 행이 거의 없어 만드는 비용이 0에 가깝고, 늘어난 뒤에 만들면 그때는 비싸다.
CREATE INDEX IF NOT EXISTS idx_work_log_comments_user
    ON work_log_comments (user_id);

CREATE INDEX IF NOT EXISTS idx_authority_member
    ON authority (member_id);

CREATE INDEX IF NOT EXISTS idx_chat_members_user
    ON chat_members (user_id);

CREATE INDEX IF NOT EXISTS idx_checklist_results_user
    ON checklist_results (user_id);

CREATE INDEX IF NOT EXISTS idx_safety_enrollments_user
    ON safety_enrollments (user_id);

-- absence_requests는 users를 두 번 참조한다 -- 신청자와 처리자.
-- 둘 다 별개의 FK라 각각 인덱스가 필요하다. 하나만 만들면 나머지 하나에서
-- 여전히 전체 스캔이 나간다.
CREATE INDEX IF NOT EXISTS idx_absence_requests_user
    ON absence_requests (user_id);

CREATE INDEX IF NOT EXISTS idx_absence_requests_processed_by
    ON absence_requests (processed_by);

-- attendance.user_id에는 만들지 않는다 -- 이미 인덱스가 있다.
