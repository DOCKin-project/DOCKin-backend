-- ADR-0008 D7 마무리 — 읽음의 축이 시각에서 room_seq로 완전히 옮겨졌으므로 시각 컬럼을 내린다.
--
-- V6가 last_read_seq를 만들며 이 컬럼을 "한 릴리스 동안 병행"으로 뒀다. 그 한 릴리스가 끝났다:
--
--   읽는 곳    0곳  (안읽음은 last_message_seq − last_read_seq, 목록·상세·따라잡기 전부 seq)
--   쓰는 곳    1곳  (ChatJdbcRepository.markRead가 last_read_seq와 함께 NOW()를 넣고 있었다 — 습관성 갱신)
--
-- 쓰기만 남은 컬럼은 값이 맞는지 아무도 확인하지 않는 컬럼이다. 남겨 두면 다음 사람이
-- "이건 뭘 기준으로 세는 거지"를 다시 묻는다. P2-12-2(시계가 둘)·P2-12-3(읽음 기준이 시각)이
-- 여기서 스키마 차원에서 닫힌다.
--
-- 값을 seq로 옮기지 않는 이유는 V6에 적었다 — 시각과 번호의 대응은 M3가 보인 역전 때문에
-- 정확하지 않고, 이미 V6 시점에 last_read_seq는 0에서 새로 시작했다. 여기서 옮길 것이 없다.
--
-- 내리기 전에 확인한다: 이 컬럼을 참조하는 뷰·인덱스·제약이 있으면 DROP이 실패해야 한다.
-- 조용히 CASCADE로 딸려 지우는 것보다 낫다. (실제로는 V2에 그런 것이 없다 — 이 DO 블록은
-- 그 사실을 문서가 아니라 실행으로 남긴다.)
DO $$
DECLARE
    dependents bigint;
BEGIN
    SELECT count(*) INTO dependents
      FROM pg_depend d
      JOIN pg_attribute a ON a.attrelid = d.refobjid AND a.attnum = d.refobjsubid
     WHERE d.refobjid = 'chat_members'::regclass
       AND a.attname = 'last_read_time'
       AND d.deptype IN ('n', 'a');

    IF dependents > 0 THEN
        RAISE EXCEPTION 'chat_members.last_read_time을 참조하는 객체가 % 개 있다. 무엇인지 확인한 뒤 먼저 정리하라.', dependents;
    END IF;
END $$;

ALTER TABLE chat_members DROP COLUMN last_read_time;
