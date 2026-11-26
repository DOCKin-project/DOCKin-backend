-- V20: 회원 탈퇴를 행 삭제가 아니라 비활성화로 (#100, 사용자 결정: 비활성화 + 익명화)
--
-- users를 참조하는 FK 9개(attendance, absence_requests×2, authority, chat_members, checklist_results,
-- safety_enrollments, work_log_comments, work_logs)가 전부 NO ACTION이라, 근태·일지가 하나라도 있으면 탈퇴가 500이었다.
-- 그 기록은 퇴사 뒤에도 남아야 하므로 FK를 CASCADE로 바꾸지 않고, 행을 남긴 채 탈퇴 시각을 둔다.
-- 이름·비밀번호는 애플리케이션이 지운다(Member.withdraw). NULL이면 재직 중.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted_at timestamp(6) without time zone;
