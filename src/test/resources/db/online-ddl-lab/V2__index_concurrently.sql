-- CONCURRENTLY는 트랜잭션 블록 안에서 못 돈다. Flyway의 PostgreSQL 파서가 이 문장을 알아보고
-- 이 스크립트만 트랜잭션 밖에서 돌린다(.sql.conf의 executeInTransaction=false 는 필요 없다 --
-- OnlineDdlMigrationTest가 확인). 대신 flyway.postgresql.transactional.lock=false 가 없으면
-- Flyway 자신의 히스토리 트랜잭션을 기다리다 lock_timeout에 걸린다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_lab_rows_v ON lab_rows (v);
