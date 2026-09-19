-- ===========================================================================
-- 팽창 스냅샷 -- 테이블마다 "살아 있는 행 / 죽은 행 / 힙 크기 / 마지막 청소"를 한 화면에.
-- (docs/DB-IMPROVEMENT-PLAN.md A1, PG-BOOK-EXPERIMENTS.md 실험 ②)
--
-- 왜 있나: 이 저장소는 힙 780MB(20,362행)와 0행 26MB를 pg_relation_size 하나로
-- 우연히 봤다. 죽은 행이 몇 개인지, autovacuum이 언제 왔는지는 읽은 적이 없다.
-- 이 파일이 그 두 값을 읽는다. 벤치·측정이 끝날 때마다 산출물 옆에 남긴다.
--
-- 실행: scripts/db/bloat-snapshot.sh            (파일로 남긴다)
--       docker exec -i dockin-db psql -U root -d dockindb -f - < scripts/db/bloat-snapshot.sql
--
-- 두 표를 찍는다.
--   [1] pg_stat_user_tables   -- 통계 수집기가 세는 값. 스캔이 없어 공짜다.
--                                n_dead_tup은 "청소 대상"이고 autovacuum이 이 값으로 깨어난다
--                                (기본 임계 50 + 0.2 x reltuples).
--   [2] pgstattuple_approx    -- 가시성 맵으로 건너뛰고 나머지만 읽어 죽은 비율을 추정한다.
--                                정확한 pgstattuple()은 힙 전체를 읽으므로 780MB 테이블에
--                                평상시에 돌릴 것이 아니다. 정확값이 필요할 때만 아래 [3]을 푼다.
--
-- 읽는 법:
--   dead_pct가 높은데 last_autovacuum이 오래됐다     -> autovacuum이 못 오고 있다 (실험 ② (b))
--   n_dead_tup은 0인데 heap이 안 줄었다               -> 청소는 됐고 truncate만 안 됐다 (장애 #3)
--   n_live_tup보다 pg_class.reltuples가 크게 다르다  -> ANALYZE가 밀려 플래너가 옛 통계를 본다
-- ===========================================================================

\pset border 2
\pset footer off

\echo
\echo '[1] pg_stat_user_tables — 통계 수집기 값 (스캔 없음)'
SELECT
    relname                                            AS "table",
    n_live_tup                                         AS live,
    n_dead_tup                                         AS dead,
    CASE WHEN n_live_tup + n_dead_tup = 0 THEN 0
         ELSE round(100.0 * n_dead_tup / (n_live_tup + n_dead_tup), 1) END AS dead_pct,
    pg_size_pretty(pg_relation_size(relid))            AS heap,
    pg_size_pretty(pg_indexes_size(relid))             AS indexes,
    to_char(last_autovacuum, 'MM-DD HH24:MI')          AS last_autovac,
    autovacuum_count                                   AS autovac_n,
    to_char(last_vacuum, 'MM-DD HH24:MI')              AS last_vacuum,
    to_char(last_autoanalyze, 'MM-DD HH24:MI')         AS last_autoanalyze
FROM pg_stat_user_tables
WHERE schemaname = 'public'
ORDER BY pg_relation_size(relid) DESC;

-- pgstattuple은 진단 도구라 Flyway에 넣지 않는다(V2 머리말의 pg_stat_statements와 같은 이유).
-- 이미지(pgvector/pgvector:pg17)에 contrib로 들어 있어 여기서 만들면 된다.
CREATE EXTENSION IF NOT EXISTS pgstattuple;

\echo
\echo '[2] pgstattuple_approx — 힙 기준 추정 (전부 보이는 페이지는 건너뛴다)'
SELECT
    c.relname                                          AS "table",
    pg_size_pretty(a.table_len)                        AS heap,
    round(a.scanned_percent::numeric, 1)               AS scanned_pct,
    round(a.approx_tuple_percent::numeric, 1)          AS live_pct,
    round(a.dead_tuple_percent::numeric, 1)            AS dead_pct,
    round(a.approx_free_percent::numeric, 1)           AS free_pct,
    pg_size_pretty(a.approx_free_space)                AS free
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
CROSS JOIN LATERAL pgstattuple_approx(c.oid) a
WHERE n.nspname = 'public'
  AND c.relkind = 'r'
  AND pg_relation_size(c.oid) > 8192       -- 빈 테이블은 뺀다. 한 페이지짜리도 뺀다
ORDER BY a.table_len DESC;

-- [3] 정확값이 필요할 때 -- 힙 전체를 읽는다. 큰 테이블에는 시간이 든다.
-- SELECT * FROM pgstattuple('work_logs');

\echo
\echo '[4] autovacuum 설정 — 전역값과 테이블 단위 재정의'
SELECT name, setting, unit
FROM pg_settings
WHERE name IN ('autovacuum', 'autovacuum_vacuum_scale_factor', 'autovacuum_vacuum_threshold',
               'autovacuum_vacuum_cost_delay', 'autovacuum_vacuum_cost_limit',
               'autovacuum_naptime', 'autovacuum_max_workers', 'autovacuum_work_mem',
               'maintenance_work_mem')
ORDER BY name;

SELECT c.relname AS "table", c.reloptions
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.reloptions IS NOT NULL;

\echo
\echo '[5] 지금 도는 VACUUM'
SELECT p.pid, c.relname, p.phase,
       p.heap_blks_scanned || '/' || p.heap_blks_total AS blocks,
       p.index_vacuum_count AS idx_passes,
       p.num_dead_item_ids  AS dead_ids
FROM pg_stat_progress_vacuum p
LEFT JOIN pg_class c ON c.oid = p.relid;
