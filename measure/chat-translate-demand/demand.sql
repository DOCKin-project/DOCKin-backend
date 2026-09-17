-- 채팅 자동 번역(ADR-0008 D3)의 실제 호출 수요 — 4-2의 "혼합 언어 방의 메시지 × (언어 수 − 1)"을 저장된 메시지로 센다.
--
-- 메시지 하나의 호출 수 = 방 멤버의 언어 집합에서 원문 언어를 뺀 크기.
--   · 원문 언어는 chat_messages.language_code(V6, 발신자 language_code 기본값), NULL(V6 이전 행)이면 발신자의 현재 값.
--   · 방 멤버는 "지금" chat_members 에 있는 사람이다 — 메시지 시점의 멤버가 아니다. 들어오고 나간 이력이 없어 근사다.
--   · 피벗 곱하기는 없다 — pylab ADR-0003 뒤 서버는 어느 쌍이든 한 홉(직접 opus 또는 NLLB)이다.
--   · TEXT 만 센다. IMAGE·FILE 은 번역하지 않는다.
--
-- 실행:  docker exec -i dockin-db psql -U dockin -d dockin -v days=30 < measure/chat-translate-demand/demand.sql
-- 결과 셋: ① 분 단위 상위 20(지속 피크 — 서버 대수를 정하는 값) ② 초 단위 상위 20(순간 피크 — 큐 깊이를 정하는 값) ③ 전체 요약.
-- 파일럿(PRODUCTION-READINESS H1) 데이터로 처음 돌린다. 그 전엔 시드 데이터라 숫자에 뜻이 없다.

\if :{?days}
\else
\set days 30
\endif

WITH msg AS (
    SELECT m.message_id, m.room_id, m.sent_at,
           COALESCE(m.language_code, u.language_code) AS src_lang
      FROM chat_messages m
      JOIN users u ON u.user_id = m.sender_id
     WHERE m.message_type = 'TEXT'
       AND m.sent_at >= now() - make_interval(days => :days)
),
calls AS (
    SELECT msg.message_id, msg.room_id, msg.sent_at, msg.src_lang,
           (SELECT count(DISTINCT u2.language_code)
              FROM chat_members cm
              JOIN users u2 ON u2.user_id = cm.user_id
             WHERE cm.room_id = msg.room_id
               AND u2.language_code <> msg.src_lang) AS n_calls
      FROM msg
)
SELECT '① 분 단위 상위 20' AS "구간", to_char(date_trunc('minute', sent_at), 'MM-DD HH24:MI') AS "시각",
       count(*) AS "메시지", sum(n_calls) AS "호출",
       round(sum(n_calls) / 60.0, 2) AS "호출/s",
       round(100.0 * count(*) FILTER (WHERE n_calls > 0) / count(*), 1) AS "혼합 방 비율 %",
       round(avg(n_calls) FILTER (WHERE n_calls > 0), 2) AS "혼합 방 메시지당 호출"
  FROM calls
 GROUP BY date_trunc('minute', sent_at)
 ORDER BY sum(n_calls) DESC
 LIMIT 20;

WITH msg AS (
    SELECT m.message_id, m.room_id, m.sent_at,
           COALESCE(m.language_code, u.language_code) AS src_lang
      FROM chat_messages m
      JOIN users u ON u.user_id = m.sender_id
     WHERE m.message_type = 'TEXT'
       AND m.sent_at >= now() - make_interval(days => :days)
),
calls AS (
    SELECT msg.sent_at,
           (SELECT count(DISTINCT u2.language_code)
              FROM chat_members cm
              JOIN users u2 ON u2.user_id = cm.user_id
             WHERE cm.room_id = msg.room_id
               AND u2.language_code <> msg.src_lang) AS n_calls
      FROM msg
)
SELECT '② 초 단위 상위 20' AS "구간", to_char(date_trunc('second', sent_at), 'MM-DD HH24:MI:SS') AS "시각",
       count(*) AS "메시지", sum(n_calls) AS "호출/s"
  FROM calls
 GROUP BY date_trunc('second', sent_at)
 ORDER BY sum(n_calls) DESC
 LIMIT 20;

WITH msg AS (
    SELECT m.message_id, m.room_id, m.sent_at,
           COALESCE(m.language_code, u.language_code) AS src_lang
      FROM chat_messages m
      JOIN users u ON u.user_id = m.sender_id
     WHERE m.message_type = 'TEXT'
       AND m.sent_at >= now() - make_interval(days => :days)
),
calls AS (
    SELECT msg.room_id, msg.src_lang,
           (SELECT count(DISTINCT u2.language_code)
              FROM chat_members cm
              JOIN users u2 ON u2.user_id = cm.user_id
             WHERE cm.room_id = msg.room_id
               AND u2.language_code <> msg.src_lang) AS n_calls
      FROM msg
)
SELECT '③ 요약 (' || :days || '일)' AS "구간",
       count(*) AS "메시지",
       sum(n_calls) AS "호출",
       round(100.0 * count(*) FILTER (WHERE n_calls > 0) / nullif(count(*), 0), 1) AS "혼합 방 비율 %",
       round(avg(n_calls) FILTER (WHERE n_calls > 0), 2) AS "혼합 방 메시지당 호출",
       round(sum(n_calls)::numeric / nullif(count(*), 0), 2) AS "메시지당 호출(전체)",
       count(DISTINCT room_id) AS "방",
       count(DISTINCT src_lang) AS "원문 언어 수"
  FROM calls;
