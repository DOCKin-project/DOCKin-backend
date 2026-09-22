-- 채팅 팬아웃 측정용 방 (measure/k6-load/chat.js). seed-users.sql 뒤에 넣는다.
-- 방 400개 × 10명 = k6u00001..k6u04000. 방 번호는 1001..1400 (VU n → 1000 + ceil(n/10)).
-- room_id를 명시하는 이유: chat.js가 계정 번호에서 방 번호를 계산하므로 IDENTITY에 맡기면 어긋난다.
\set ON_ERROR_STOP on
INSERT INTO chat_rooms (room_id, room_name, is_group, creator_id, created_at, last_message_seq)
SELECT 1000 + r, '팬아웃 방 ' || r, true, 'k6u' || lpad(((r - 1) * 10 + 1)::text, 5, '0'), now(), 0
FROM generate_series(1, 400) r
ON CONFLICT (room_id) DO NOTHING;

INSERT INTO chat_members (room_id, user_id, joined_at, last_read_seq)
SELECT 1000 + r, 'k6u' || lpad(((r - 1) * 10 + i)::text, 5, '0'), now(), 0
FROM generate_series(1, 400) r, generate_series(1, 10) i
WHERE NOT EXISTS (SELECT 1 FROM chat_members m WHERE m.room_id = 1000 + r AND m.user_id = 'k6u' || lpad(((r - 1) * 10 + i)::text, 5, '0'));

SELECT setval(pg_get_serial_sequence('chat_rooms', 'room_id'), GREATEST((SELECT max(room_id) FROM chat_rooms), 1));
SELECT (SELECT count(*) FROM chat_rooms WHERE room_id > 1000) AS rooms, (SELECT count(*) FROM chat_members WHERE room_id > 1000) AS members;
