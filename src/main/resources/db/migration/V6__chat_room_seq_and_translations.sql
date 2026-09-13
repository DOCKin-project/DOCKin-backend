-- ADR-0008 — 채팅의 순서 축을 message_id에서 방 단위 시퀀스로 옮기고, 번역 결과의 자리를 만든다.
--
-- 왜 message_id로는 안 되는가 (M3 실측, 2026-09-13, MessageIdCommitOrderMeasurementTest)
--
--   message_id는 IDENTITY다. 번호는 INSERT 때 나오고 커밋은 그 뒤다. 그 사이에 다른
--   트랜잭션이 더 큰 번호를 받아 먼저 커밋하면, "본 것보다 큰 ID"로 따라잡는 커서는
--   나중에 커밋된 작은 ID를 영영 못 본다.
--
--     INSERT만 하고 커밋                         순진한 커서 유실  21%   (발신자 10명)
--     saveMessage 모양(INSERT + 같은 방 행 UPDATE)                 56~78%
--     같은 방 행 락 안에서 방 시퀀스를 발급                        0%
--
--   둘째 줄이 첫째 줄의 3배인 이유가 이 마이그레이션의 근거다. saveMessage는 last_message_*를
--   갱신하려고 같은 방의 chat_rooms 행을 잠근다. ID는 줄을 서기 전에 받았고, 락을 먼저 얻은
--   쪽이 먼저 커밋되면 기다리던 작은 ID 전부가 그 뒤에 커밋돼 사라진다. 그 락 안에서 번호를
--   받으면 락 순서 = 커밋 순서 = 번호 순서다. 새 락이 아니라 이미 내던 비용이다(ADR-0008 5-4).
--
-- 읽음 경계도 같은 구멍이다. last_read_message_id = 37일 때 id 20이 뒤에 커밋되면 화면에
-- 뜬 적 없는 메시지가 읽음 처리된다. 그래서 커서와 읽음 둘 다 room_seq를 축으로 한다(D7).

-- ---------------------------------------------------------------- 1. 방 시퀀스 (D7·D8)

ALTER TABLE chat_rooms ADD COLUMN last_message_seq bigint NOT NULL DEFAULT 0;

ALTER TABLE chat_messages ADD COLUMN room_seq bigint;

-- 기존 행은 message_id 순으로 번호를 매긴다. 근사다 — 과거의 역전은 이미 일어난 일이라
-- 되돌릴 수 없고, ID 순서가 그나마 가장 가까운 값이다. 이 UPDATE는 chat_messages 전체를
-- 한 번 훑는다. 행 수가 크면(수십만) 락을 쥐는 시간을 미리 보고 적용 시점을 고른다.
UPDATE chat_messages m
   SET room_seq = r.rn
  FROM (SELECT message_id,
               ROW_NUMBER() OVER (PARTITION BY room_id ORDER BY message_id) AS rn
          FROM chat_messages) r
 WHERE m.message_id = r.message_id;

ALTER TABLE chat_messages ALTER COLUMN room_seq SET NOT NULL;

-- (room_id, room_seq)는 유일해야 하고 그 자체가 따라잡기 인덱스다. 기존 (room_id, sent_at)는
-- 커서 축이 바뀌었으므로 내린다 — sent_at으로 범위를 긋는 조회가 남아 있다면 V2의 인덱스가
-- 아니라 이 유니크 인덱스가 room_id 선행 컬럼으로 대신 탄다.
ALTER TABLE chat_messages ADD CONSTRAINT uq_chat_messages_room_seq UNIQUE (room_id, room_seq);
DROP INDEX IF EXISTS idx_room_sent;

-- 방의 현재 번호를 기존 메시지의 최대값으로 맞춘다. 맞지 않으면 다음 발급이 유니크 제약에 걸린다.
UPDATE chat_rooms r
   SET last_message_seq = COALESCE((SELECT MAX(room_seq) FROM chat_messages WHERE room_id = r.room_id), 0);

-- 적용 직후의 불변식: 방의 번호 = 그 방 메시지의 최대 번호. 어긋나면 여기서 멈춘다.
DO $$
DECLARE
    broken bigint;
BEGIN
    SELECT count(*) INTO broken
      FROM chat_rooms r
     WHERE r.last_message_seq <> COALESCE((SELECT MAX(room_seq) FROM chat_messages m WHERE m.room_id = r.room_id), 0);

    IF broken > 0 THEN
        RAISE EXCEPTION 'chat_rooms.last_message_seq가 메시지 최대 room_seq와 다른 방이 % 개 있다. 위 UPDATE 둘 중 하나가 기대와 다르게 돌았다.', broken;
    END IF;
END $$;

-- ---------------------------------------------------------------- 2. 읽음 (D7)

-- last_read_time은 한 릴리스 동안 병행한다. 읽음 처리 API가 seq를 쓰게 바뀐 뒤 V7에서 내린다.
-- 0으로 시작하는 것은 "아무것도 안 읽음"이다. 기존 last_read_time을 seq로 환산하지 않는 이유는
-- 시각과 번호의 대응이 M3가 보인 그 역전 때문에 정확하지 않기 때문이다 — 틀린 값을 옮기느니
-- 안읽음이 한 번 많이 보이는 쪽을 고른다.
ALTER TABLE chat_members ADD COLUMN last_read_seq bigint NOT NULL DEFAULT 0;

-- ---------------------------------------------------------------- 3. 멱등·언어·시계 (D9, P2-8-5, D6)

-- 클라이언트가 발급하는 재전송 키. NULL은 "키 없이 보낸 옛 클라이언트"이며 PostgreSQL의
-- UNIQUE는 NULL을 서로 다른 값으로 보므로 옛 클라이언트가 막히지 않는다.
ALTER TABLE chat_messages ADD COLUMN client_msg_id uuid;
ALTER TABLE chat_messages ADD CONSTRAINT uq_chat_messages_client_msg_id UNIQUE (room_id, client_msg_id);

-- 원문의 언어. 기존 행은 알 수 없으므로 NULL로 두고, 새 행은 발신자의 users.language_code로 채운다.
-- 소급이 불가능한 값이라 번역 기능보다 컬럼이 먼저 온다(P2-8-5).
ALTER TABLE chat_messages ADD COLUMN language_code varchar(8);

-- 시계를 DB 하나로(D6). last_message_at·last_read_time이 이미 NOW()라 sent_at을 그쪽에 맞춘다.
ALTER TABLE chat_messages ALTER COLUMN sent_at SET DEFAULT now();

-- ---------------------------------------------------------------- 4. 번역 (D4)

-- work_log_translations의 UNIQUE(log_id, language_code)와 같은 꼴. 메시지 하나에 언어별 한 행.
-- 수신자 수가 아니라 언어 수만큼 생긴다(ADR-0008 4-2). 원문이 지워지면 함께 지운다.
CREATE TABLE chat_message_translations (
    message_id    bigint      NOT NULL REFERENCES chat_messages(message_id) ON DELETE CASCADE,
    language_code varchar(8)  NOT NULL,
    content       text        NOT NULL,
    model         varchar(64),
    trace_id      varchar(64),
    created_at    timestamp(6) without time zone NOT NULL DEFAULT now(),
    CONSTRAINT chat_message_translations_pkey PRIMARY KEY (message_id, language_code)
);
