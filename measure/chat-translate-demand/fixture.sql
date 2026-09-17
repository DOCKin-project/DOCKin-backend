-- demand.sql 검증용 픽스처. 시드(R__seed_sample.sql)에는 채팅이 없고 사용자 넷(ko·ko·vi·ko)만 있다.
-- 방 1 = 넷 다(ko+vi) → 발신자가 누구든 메시지당 1호출, 방 2 = ko 둘 → 0. 5번째마다 language_code NULL(V6 이전 행 흉내), IMAGE 하나(안 셈).
-- 기대: ③ 메시지 40 · 호출 30 · 혼합 방 75% · 혼합 방 메시지당 1.00, ① 09-16 한 분에 29호출(0.48/s).
--
-- 빈 DB에서 한 번에(마이그레이션·시드·픽스처·질의 전부 한 트랜잭션, 끝에 ROLLBACK — 남는 것 없음):
--   { echo 'BEGIN;'; cat src/main/resources/db/migration/V[1-7]__*.sql src/main/resources/db/seed/R__seed_sample.sql --     measure/chat-translate-demand/fixture.sql; echo; cat measure/chat-translate-demand/demand.sql; echo 'ROLLBACK;'; } --   | sed 's/$//' | docker exec -i dockin-db psql -U root -d dockindb -v ON_ERROR_STOP=1 -v days=30 -q
-- 2026-09-17 pgvector/pgvector:pg17 에서 확인.
INSERT INTO chat_rooms (room_id, room_name, is_group, creator_id, created_at) VALUES
  (1, '1도크 조립 A조', true, 'worker01', now() - interval '20 days'),
  (2, '반장들', false, 'worker01', now() - interval '20 days');
INSERT INTO chat_members (room_id, user_id, joined_at) VALUES
  (1, 'admin01', now() - interval '20 days'), (1, 'worker01', now() - interval '20 days'),
  (1, 'worker02', now() - interval '20 days'), (1, 'worker03', now() - interval '20 days'),
  (2, 'worker01', now() - interval '20 days'), (2, 'worker03', now() - interval '20 days');
-- 방 1: ko 발신 → vi 1호출, vi 발신 → ko 1호출. 방 2: ko만 → 0. 한 분에 몰아 넣어 피크가 보이게.
INSERT INTO chat_messages (room_id, room_seq, sender_id, content, message_type, sent_at, language_code)
SELECT 1, g, CASE WHEN g % 3 = 0 THEN 'worker02' ELSE 'worker01' END, '메시지 ' || g, 'TEXT',
       date_trunc('minute', now() - interval '1 day') + (g || ' seconds')::interval * 2,
       CASE WHEN g % 5 = 0 THEN NULL ELSE (CASE WHEN g % 3 = 0 THEN 'vi' ELSE 'ko' END) END
FROM generate_series(1, 30) g;
INSERT INTO chat_messages (room_id, room_seq, sender_id, content, message_type, sent_at, language_code)
SELECT 2, g, 'worker03', '반장 ' || g, 'TEXT', now() - interval '2 days' + (g || ' minutes')::interval, 'ko'
FROM generate_series(1, 10) g;
INSERT INTO chat_messages (room_id, room_seq, sender_id, content, message_type, sent_at, language_code, file_url)
VALUES (1, 31, 'worker01', 'photo', 'IMAGE', now() - interval '1 day', 'ko', 'x.jpg');
