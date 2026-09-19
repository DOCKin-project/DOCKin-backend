-- 단계 사이에 오늘 출근 기록을 비운다. 매 반복의 POST /api/attendance/in 이 "첫 출근"(INSERT 경로)이 되게.
-- Redis의 attendance:lock:* 은 리스 30초라 알아서 사라진다. refresh_token 도 같이 비운다(로그인마다 쌓인다).
DELETE FROM attendance WHERE user_id LIKE 'k6u%';
DELETE FROM refresh_token WHERE user_id LIKE 'k6u%';
SELECT count(*) AS attendance_left FROM attendance WHERE user_id LIKE 'k6u%';
