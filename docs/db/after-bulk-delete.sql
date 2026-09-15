-- ===========================================================================
-- 운영 절차: 대량 삭제 뒤 (마이그레이션 아님)
--
-- 언제 쓰나:
--   - 코퍼스 보존 정책(ADR-0007)으로 document_chunks를 수십만 행 지운 뒤
--   - 벤치 시드(work_logs 100만 등)를 걷어낸 뒤
--   - "행은 지웠는데 디스크가 안 줄었다"가 보일 때
--
-- 실행:
--   ./scripts/db/bloat-snapshot.sh                              # 전
--   docker exec -i dockin-db psql -U root -d dockindb < docs/db/after-bulk-delete.sql
--   ./scripts/db/bloat-snapshot.sh                              # 후
--   전·후 파일을 나란히 둔다. 전이 없으면 후만 봐서는 줄었는지 모른다.
--
-- 배경(docs/DB-IMPROVEMENT-PLAN.md A3, PG-BOOK-EXPERIMENTS.md 장애 #2·#3):
--   98만 행을 지운 뒤 work_logs는 20,362행에 힙 780MB였고, work_log_images는 0행에 26MB였다.
--   VACUUM으로 잘라내려 하자 실행 중인 삭제가 잠금을 쥐고 있어 truncate가 거부됐다.
--   그때는 절차가 없어서 "언제 줄어드는지"를 아무도 몰랐다.
--
-- 이 절차가 전제하는 세 가지 사실 (2026-09-15, pgvector/pgvector:pg17 로컬 실측):
--
--   ① VACUUM은 죽은 행을 치우지만 힙을 돌려주지는 않는다.
--      20만 행 중 앞쪽 18만을 지우고 VACUUM: "tuples: 180000 removed, pages: 0 removed" -- 112MB 그대로.
--      빈 자리는 다음 INSERT가 재사용할 뿐이다. 앞쪽 행을 지운 테이블은 VACUUM으로 안 줄어든다.
--
--   ② 파일 끝의 빈 페이지만 잘라낸다(truncate). 그것도 충분히 길 때만.
--      꼬리 1만 행(714페이지, 5%)을 지우고 VACUUM: "pages: 0 removed" -- 안 잘렸다.
--      꼬리 10만 행(7,143페이지, 50%): "truncated 14286 to 7143 pages" -- 112MB -> 56MB.
--      PostgreSQL은 잘라낼 꼬리가 1,000페이지(8MB) 또는 테이블의 1/16 이상일 때만 시도한다
--      (src/backend/access/heap/vacuumlazy.c의 REL_TRUNCATE_MINIMUM / REL_TRUNCATE_FRACTION).
--
--   ③ truncate에는 ACCESS EXCLUSIVE 락이 필요하다.
--      다른 트랜잭션이 그 테이블에 어떤 락이든 쥐고 있으면 VACUUM은 truncate만 건너뛰고 끝난다
--      (장애 #3이 그것이다). 앱이 떠 있어도 되지만, 그 테이블을 건드리는 긴 트랜잭션은 없어야 한다.
--
-- 그래서 순서는: 삭제 트랜잭션이 끝난 것을 확인 -> VACUUM (VERBOSE) -> 로그의 "truncated"와
-- "pages: N removed"를 읽는다 -> 안 잘렸으면 이유가 ①인지 ②인지 ③인지를 가른다.
-- ===========================================================================

-- 0. 그 테이블에 아직 열린 트랜잭션이 있는가. 있으면 ③이라 truncate가 안 된다.
--    (vacuum이 기다리지 않고 건너뛰므로, 먼저 보고 시작한다.)
SELECT a.pid, a.state, a.wait_event_type, a.wait_event,
       now() - a.xact_start AS xact_age, left(a.query, 60) AS query
FROM pg_stat_activity a
WHERE a.datname = current_database()
  AND a.backend_type = 'client backend'
  AND a.pid <> pg_backend_pid()
  AND a.xact_start IS NOT NULL
ORDER BY a.xact_start;

-- 1. 대상 테이블. 대량 삭제가 있는 곳은 둘이고, FK로 딸려 지워지는 자식들이 뒤에 온다.
--    VACUUM은 트랜잭션 블록 안에서 못 돈다 -- psql -f로 한 줄씩 실행되게 두고 BEGIN을 넣지 않는다.
--
--    VERBOSE로 두는 이유: "tuples: N removed"와 "truncated A to B pages"가 곧 결과 기록이다.
--    INDEX_CLEANUP은 기본(AUTO)이다. HNSW가 붙은 document_chunks에서 40분을 넘긴 적이 있다
--    (밤 3, 장애 #5) -- 급하면 (VERBOSE, INDEX_CLEANUP OFF)로 힙만 먼저 치우고,
--    인덱스는 시간이 있을 때 다시 VACUUM한다. 그 대가는 죽은 인덱스 항목이 남는 것이다.
VACUUM (VERBOSE) work_logs;
VACUUM (VERBOSE) work_log_images;
VACUUM (VERBOSE) work_log_comments;
VACUUM (VERBOSE) log_images;
VACUUM (VERBOSE) work_log_translations;
VACUUM (VERBOSE) document_chunks;

-- 2. 통계도 새로 낸다. n_live_tup이 실제와 다르면 플래너가 옛 카디널리티로 계획을 짠다.
ANALYZE work_logs;
ANALYZE document_chunks;

-- 3. 결과 읽기. 힙이 안 줄었다면:
--      pgstattuple_approx.free_pct가 높다  -> ① 앞쪽에 구멍. 재사용은 되지만 반환은 안 된다.
--                                              반환이 꼭 필요하면 아래 4.
--      dead_pct가 여전히 높다             -> ③ 열린 트랜잭션이 있어 "dead but not yet removable".
--                                              0절의 그 pid가 끝난 뒤 다시.
--      둘 다 낮은데 힙만 크다              -> ② 꼬리가 1,000페이지 미만. 그냥 둔다.
-- ./scripts/db/bloat-snapshot.sh 가 이 세 값을 찍는다.

-- 4. 힙을 진짜로 돌려받아야 할 때 -- 평상시엔 하지 않는다.
--    VACUUM FULL은 테이블을 새로 쓰고 그동안 ACCESS EXCLUSIVE로 읽기까지 막는다.
--    document_chunks라면 HNSW 인덱스도 다시 만든다(rebuild-hnsw-index.sql의 세션 설정을 먼저).
--    디스크는 원본 + 사본이 동시에 필요하다.
-- VACUUM (FULL, VERBOSE) work_logs;
