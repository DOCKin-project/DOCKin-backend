-- ===========================================================================
-- 슬로우 쿼리 + 대기 이벤트 보고서 -- 주 1회 (docs/OPERATIONS-SLOW-QUERY.md, DB-IMPROVEMENT-PLAN D3·C2, PRODUCTION-READINESS O4)
--
-- 실행: scripts/db/slow-query-report.sh            (파일로 남긴다)
--       docker exec -i dockin-db psql -U root -d dockindb -f - < scripts/db/slow-query-report.sql
--
-- pg_stat_statements는 compose.yaml이 shared_preload_libraries로 적재하고 있었지만 읽는 곳은
-- 배치 검증 테스트 하나였다(O4 △). 이 파일이 세 축으로 읽는다:
--   [1] 누적 시간이 큰 것    -- 시스템 전체 부하의 주범. 짧아도 자주 불리면 여기 온다
--   [2] 한 번이 오래 걸리는 것 -- 사용자가 체감하는 것. 목록·검색·색인 배치가 여기 온다
--   [3] 가장 자주 불리는 것  -- N+1과 캐시 후보. P2-12-1(방 목록 41개 쿼리)이 이 표에 보였을 것
-- 그리고 [4] 지금 이 순간 기다리는 세션 -- wait_event. ShadowFit이 data_locks로 본 것을 PG에서는 이렇게 본다
-- (막힘없이 PostgreSQL 3장·부록, PostgreSQL Wait Interface).
--
-- 임계값은 없다. "느리다"의 기준은 이 표가 몇 주 쌓인 뒤 분포에서 온다(DB-IMPROVEMENT-PLAN 0절 3).
-- auto_explain을 안 켜는 이유도 그것이다 -- log_min_duration의 값을 지금은 댈 수 없다.
--
-- 주의: pg_stat_statements는 재기동·pg_stat_statements_reset()에 리셋된다. [0]의 stats_reset을 먼저 본다.
--       배치 검증 테스트가 리셋을 부르지만 그것은 테스트 컨테이너의 일이고 운영 DB와 무관하다.
-- ===========================================================================

\pset border 2
\pset footer off

\echo
\echo '[0] 통계 창 — 언제부터 쌓인 값인가'
SELECT stats_reset, now() - stats_reset AS window, dealloc AS evicted_entries
FROM pg_stat_statements_info;

\echo
\echo '[1] 누적 실행 시간 top 10 (total_exec_time)'
SELECT
    round(total_exec_time::numeric / 1000, 1)        AS total_s,
    calls,
    round(mean_exec_time::numeric, 2)                AS mean_ms,
    round(max_exec_time::numeric, 1)                 AS max_ms,
    rows,
    round(100.0 * shared_blks_hit / NULLIF(shared_blks_hit + shared_blks_read, 0), 1) AS hit_pct,
    left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT ILIKE '%pg_stat_%'
ORDER BY total_exec_time DESC
LIMIT 10;

\echo
\echo '[2] 평균 실행 시간 top 10 (mean_exec_time, calls >= 5)'
SELECT
    round(mean_exec_time::numeric, 2)                AS mean_ms,
    round(stddev_exec_time::numeric, 2)              AS stddev_ms,
    round(max_exec_time::numeric, 1)                 AS max_ms,
    calls,
    round(100.0 * shared_blks_hit / NULLIF(shared_blks_hit + shared_blks_read, 0), 1) AS hit_pct,
    left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT ILIKE '%pg_stat_%'
  AND calls >= 5
ORDER BY mean_exec_time DESC
LIMIT 10;

\echo
\echo '[3] 호출 횟수 top 10 (calls) — N+1·캐시 후보'
SELECT
    calls,
    round(mean_exec_time::numeric, 3)                AS mean_ms,
    round(total_exec_time::numeric / 1000, 1)        AS total_s,
    rows,
    left(regexp_replace(query, '\s+', ' ', 'g'), 110) AS query
FROM pg_stat_statements
WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
  AND query NOT ILIKE '%pg_stat_%'
ORDER BY calls DESC
LIMIT 10;

\echo
\echo '[4] 지금 기다리는 세션 — wait_event (자기 자신 제외)'
-- wait_event_type: Lock(다른 트랜잭션의 락) / LWLock(내부 래치) / IO / Client(클라이언트가 안 보냄) / Activity(유휴 프로세스)
-- Lock이면 blocked_by에 쥔 pid가 나온다. 13분 44초 사건(PG-BOOK-EXPERIMENTS 장애 #4)이 이 줄로 보였어야 했다.
SELECT
    a.pid,
    a.state,
    a.wait_event_type,
    a.wait_event,
    pg_blocking_pids(a.pid)                          AS blocked_by,
    now() - a.query_start                            AS query_age,
    now() - a.xact_start                             AS xact_age,
    a.application_name                               AS app,
    left(regexp_replace(a.query, '\s+', ' ', 'g'), 80) AS query
FROM pg_stat_activity a
WHERE a.datname = current_database()
  AND a.backend_type = 'client backend'
  AND a.pid <> pg_backend_pid()
  AND a.state <> 'idle'
ORDER BY a.xact_start NULLS LAST;

\echo
\echo '[5] 오래 열린 트랜잭션 (idle in transaction 포함) — VACUUM이 이 뒤의 죽은 행을 못 치운다'
SELECT
    a.pid, a.state, now() - a.xact_start AS xact_age, a.application_name AS app,
    left(regexp_replace(a.query, '\s+', ' ', 'g'), 80) AS last_query
FROM pg_stat_activity a
WHERE a.datname = current_database()
  AND a.backend_type = 'client backend'
  AND a.pid <> pg_backend_pid()
  AND a.xact_start IS NOT NULL
  AND now() - a.xact_start > interval '1 minute'
ORDER BY a.xact_start;

\echo
\echo '[6] 락 대기 로그 수 — log_lock_waits=on(C1)이 남긴 것. 서버 로그를 세는 것은 .sh가 한다'
SELECT name, setting FROM pg_settings WHERE name IN ('log_lock_waits', 'deadlock_timeout', 'lock_timeout');
