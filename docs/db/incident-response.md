# DB 장애 대응 — 세 시나리오, 무엇을 먼저 보나

- 대상: 운영 `dockin-db`(compose.yaml, PostgreSQL 17 + pgvector, 컨테이너 512M)가 이상할 때 처음 여는 사람.
- 근거: `DB-IMPROVEMENT-PLAN.md` E4, `PRODUCTION-READINESS.md` O6. 시나리오 셋은 `PG-BOOK-EXPERIMENTS.md` 5절 장애 기록 중 **실제로 겪고 실제로 풀어 본 것**만 승격했다 — 여기 적힌 명령은 그때 쓴 것이거나 그 뒤 절차(`after-bulk-delete.sql`, `slow-query-report.sql`, `bloat-snapshot.sh`)에 들어간 것이다.
- 짝: `online-ddl.md`(DDL 앞에), `after-bulk-delete.sql`(삭제 뒤), `rebuild-hnsw-index.sql`, `../OPERATIONS-BACKUP.md`(복구), `../OPERATIONS-SLOW-QUERY.md`(주 1회).
- 없는 것: "번역 서버가 죽었다", "디스크가 찼다"는 이 문서에 **아직 없다**(#89). 겪은 적이 없어 절차를 적으면 추측이 된다.

공통 준비. 아래 명령은 전부 컨테이너 안 psql이다:

```bash
alias pg='docker exec -i dockin-db psql -U root -d dockindb'     # .env의 DB_USERNAME이 root가 아니면 바꾼다
pg -c "select 1"                                                  # 이게 안 되면 → 1번
```

원칙 하나. **재시작은 마지막이 아니라 "원인을 가르는 개입"이다.** 앱을 다시 띄워서 돌아오면 앱, 안 돌아오고 DB를 다시 띄워서 돌아오면 DB 프로세스 상태(밤 14). 다만 재시작하면 `pg_stat_activity`·`pg_locks`·컨테이너 메모리 같은 **현장이 사라진다** — 아래 "먼저 보는 것"을 찍은 뒤에 한다. 찍는 데 1분이면 된다.

---

## 1. DB가 안 뜬다 / 앱이 DB에 못 붙는다

**증상.** `docker compose ps`에서 `dockin-app`이 `Restarting`을 반복하거나, `dockin-db`가 `unhealthy`. 앱 로그 첫 오류가 답의 절반이다.

**먼저 보는 것 — 앱 로그의 첫 예외 한 줄로 셋 중 어디인지 가른다.**

```bash
docker compose ps
docker logs dockin-app-1 2>&1 | grep -m1 -E "Exception|FATAL|ERROR"
docker logs dockin-db 2>&1 | tail -20
```

| 앱 로그의 첫 줄 | 어디가 문제 | 이 저장소에서 있었던 일 | 조치 |
|---|---|---|---|
| `UnknownHostException: DOCKin-DB` | **네트워크** — DB는 멀쩡한데 앱 컨테이너가 옛 네트워크에 있다 | `docker compose down` 뒤 남은 옛 컨테이너가 Docker 재시작 때 `restart: on-failure`로 혼자 올라왔다. `docker inspect dockin-app-1`의 `Networks`가 `{}` (#71) | `docker compose up -d dockin-app` — 컨테이너를 다시 만들어 현재 네트워크에 붙인다. `restart`로는 안 된다 |
| `Connection refused` / `the database system is starting up` | **DB 컨테이너**가 안 떴거나 복구 중 | 밤 3의 `VACUUM` 40분(장애 #5)처럼 종료 직전 큰 작업이 있었으면 기동 시 WAL 재생이 길다 | `docker logs dockin-db`에서 `database system is ready`를 기다린다. `FATAL`이 있으면 그 줄이 답(비밀번호·볼륨 권한·`/dev/shm`) |
| `Migration checksum mismatch for migration version N` | **Flyway** — 이미 적용된 `V*.sql`을 누가 고쳤다 | `online-ddl.md` 1절 "V3는 고치지 않는다" | 파일을 원래대로 되돌린다. 고친 게 의도라면 새 V 파일로 |
| `canceling statement due to lock timeout` (55P03) 뒤 기동 실패 | **Flyway + DDL 락** — 마이그레이션이 5초 넘게 락을 기다렸다 | `CREATE INDEX CONCURRENTLY`가 Flyway 자신의 advisory lock을 기다린 것(E3, `OnlineDdlMigrationTest`). 또는 긴 트랜잭션이 그 테이블을 쥐고 있다 | 전자는 `spring.flyway.postgresql.transactional-lock=false`가 있는지. 후자는 아래 ①로 쥔 세션을 찾고 끝난 뒤 앱 재시작. **재시도 전에 invalid 인덱스 확인** (`online-ddl.md` 1절) |
| `Schema-validation: missing table [...]` | **스키마 불일치** — 엔티티와 DB가 다르다 | `ddl-auto=update`가 DDL 실패를 삼키고 기동하던 시절(장애 #12) → `validate`로 바꿔 지금은 **안 뜨는 것이 정상** | 빠진 마이그레이션이 있다. `pg -c "select version, success from flyway_schema_history order by installed_rank"`로 어디까지 갔는지 |

① 누가 락을 쥐고 있나 (기동이 락에서 막힐 때):

```sql
SELECT pid, state, wait_event_type, wait_event, now() - xact_start AS xact_age,
       pg_blocking_pids(pid) AS blocked_by, left(query, 60) AS query
FROM pg_stat_activity
WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid()
ORDER BY xact_start NULLS LAST;
```

`blocked_by`가 가리키는 pid가 `idle in transaction`이면 끝나기를 기다리거나 `SELECT pg_terminate_backend(<pid>)`. `wait_event='transactionid'`가 장애 #4(13분 44초)에서 본 그 모양이고, `DeadlockDetectionTest`가 같은 것을 재현한다.

**해 본 기록.** #71(2026-09-17, README에 절차), E3(2026-09-16, 테스트로 재현·`transactional-lock=false`로 해소), 장애 #12(`validate`로 전환).

---

## 2. 삭제가 안 끝난다 / 지웠는데 디스크가 안 줄었다

**증상.** 대량 `DELETE`(벤치 시드 걷어내기, ADR-0007 보존 삭제)가 분 단위를 넘긴다. 또는 끝났는데 `pg_relation_size`가 그대로다.

**먼저 보는 것 — 안 끝나는 것과 안 줄어드는 것은 원인이 다르다.**

```sql
-- ② 그 DELETE가 지금 무엇을 기다리나. wait_event가 NULL이고 state='active'면 기다리는 게 아니라 일하는 중이다.
SELECT pid, state, wait_event_type, wait_event, now() - xact_start AS xact_age, left(query, 80) AS query
FROM pg_stat_activity WHERE query ILIKE 'DELETE%' AND state <> 'idle';

-- ③ 지우는 부모 테이블을 참조하는 FK 중 자식 쪽에 인덱스가 없는 것. 이게 있으면 부모 행마다 자식을 순차 스캔한다.
SELECT c.conrelid::regclass AS child, c.conname, a.attname AS fk_column
FROM pg_constraint c
JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
WHERE c.contype = 'f'
  AND NOT EXISTS (
        SELECT 1 FROM pg_index i
        WHERE i.indrelid = c.conrelid AND i.indkey[0] = c.conkey[1])
ORDER BY 1, 2;
```

| 보이는 것 | 원인 | 이 저장소에서 있었던 일 | 조치 |
|---|---|---|---|
| ②가 `active`·`wait_event` NULL이고 ③에 행이 있다 | **FK 자식 인덱스 결손** — 부모 한 행 지울 때마다 자식 테이블 전체를 읽는다 | 98만 행 `DELETE`가 24분을 넘겨도 안 끝났다(장애 #1). `users` FK 9개 중 8개 무인덱스로 500명 삭제 5분(장애 #2) | ③의 컬럼에 인덱스(V3·V4가 그것). 같은 문장이 24분+ → **49초**, 13.6초 → 0.12초 |
| ②가 `wait_event_type='Lock'` | **다른 트랜잭션이 행·테이블을 쥐고 있다** | — (이 모양은 삭제가 아니라 장애 #4의 `FOR UPDATE`에서 봤다) | 1절 ①로 `blocked_by`를 찾는다. 운영은 `lock_timeout=5s`라 5초 뒤 55P03으로 죽는 것이 정상 — 죽지 않고 기다린다면 `lock_timeout`이 안 먹는 것 |
| 끝났는데 크기가 그대로 | **VACUUM은 힙을 돌려주지 않는다** — 빈 자리는 재사용될 뿐. 잘리는 건 파일 꼬리의 빈 페이지뿐이고 그것도 1,000페이지·1/16 이상일 때만 | 20,362행에 힙 780MB(장애 #2), 0행에 26MB + truncate 거부(장애 #3) | `after-bulk-delete.sql` 절차 — 앞뒤로 `bloat-snapshot.sh`. `VACUUM (VERBOSE)` 로그의 `pages: N removed`·`truncated A to B`가 결과. 안 잘렸으면 그 파일 3절로 ①②③ 중 무엇인지 가른다. 반환이 꼭 필요하면 `VACUUM FULL`(ACCESS EXCLUSIVE, 앱이 그 테이블을 안 쓰는 창에서) |
| 삭제가 `document_chunks`고 `VACUUM`이 40분+ | **HNSW 인덱스 정리** — `maintenance_work_mem` 64MB로 19만 청크 인덱스를 훑는다 | 밤 3(장애 #5) | `VACUUM (VERBOSE, INDEX_CLEANUP OFF)`로 힙만 먼저. 인덱스는 `rebuild-hnsw-index.sql`(세션에서 256MB) |

**해 본 기록.** 장애 #1·#2(2026-08-08, V3·V4로 해소, 110.7배), 장애 #3(관찰만 → A3 절차 `after-bulk-delete.sql`, 2026-09-15 로컬에서 세 가지 사실 실측), 장애 #5(밤 3 우회).

---

## 3. 느려졌는데 재시작해도 안 돌아온다

**증상.** 같은 일(색인 배치, 목록 조회)이 어느 시점부터 1.5~3배 느리다. 앱을 다시 띄워도 그대로.

**먼저 보는 것 — 재시작 전에 찍는다. 재시작하면 아래가 전부 리셋되거나 사라진다.**

```bash
./scripts/db/bloat-snapshot.sh                          # A1: dead tuple·힙·인덱스 크기
./scripts/db/slow-query-report.sh                       # D3·C2: pg_stat_statements top + wait_event
docker stats --no-stream dockin-db dockin-app-1         # CPU·메모리 — DB가 512M 상한 근처인가
docker exec dockin-db cat /sys/fs/cgroup/memory.stat | grep -E '^(anon|file|shmem) '   # #83: 페이지 캐시가 상한을 나눠 쓰는가
```

```sql
-- ④ 체크포인트가 요청형(requested)으로 몰리나. timed만 있으면 체크포인트는 아니다.
SELECT num_timed, num_requested, write_time, sync_time, stats_reset FROM pg_stat_checkpointer;

-- ⑤ 느려진 문장 하나의 "버퍼 미스 수"와 "시간"을 같이 본다. 미스 수가 같은데 시간만 늘었으면 디스크로 가는 것(밤 14).
SELECT calls, round(mean_exec_time::numeric, 2) AS mean_ms,
       round(shared_blks_read::numeric / calls, 0) AS read_per_call,
       round(shared_blks_hit::numeric / calls, 0) AS hit_per_call, left(query, 60) AS query
FROM pg_stat_statements WHERE query ILIKE 'INSERT INTO document_chunks%' OR query ILIKE '%work_logs%'
ORDER BY total_exec_time DESC LIMIT 5;

-- ⑥ autovacuum이 지금 그 테이블을 잡고 있나.
SELECT relid::regclass, phase, heap_blks_scanned, heap_blks_total FROM pg_stat_progress_vacuum;
```

| 보이는 것 | 원인 | 이 저장소에서 있었던 일 | 조치 |
|---|---|---|---|
| ⑤에서 미스/건은 같고 ms/건만 10배+, `memory.stat`의 file이 상한 근처에서 고정, `memory.events`의 max가 분당 수백~수천씩 는다 | **DB 컨테이너 메모리 상한이 페이지 캐시까지 세서 인덱스 읽기가 디스크로 간다** (밤 16에서 확정) | 밤 2·14·15: 17만 청크부터 INSERT/건 3 → 26ms, 앱 재시작 미회복, DB 재시작 즉시 회복(장애 #6). 밤 16: 재시작 없이 상한만 1G로 올려 다음 표본부터 회복 | **재시작 대신 `docker update --memory <힙+HNSW+shared_buffers+여유> --memory-swap <같은 값> dockin-db`** — 무중단, 다음 표본부터 돌아온다. `compose.yaml`의 값도 같이 올려 두지 않으면 다음 `up`에서 되돌아간다(B2). 2026-09-19부터 compose는 1G — 그래도 이 증상이면 코퍼스가 1G를 넘은 것 |
| ④의 `num_requested`가 분 단위로 는다 | **`max_wal_size`(1GB)에 자주 걸린다** | 밤 14에서 3시간 반 requested 0 — 지금 부하에선 아니다 | 그때 가서. 값을 먼저 올리지 않는다(B1) |
| ⑥에 그 테이블이 있고 `wait_event`에 `Lock`이 보인다 | **autovacuum과 경합** | 밤 14에서 18회 왔지만 열화 구간엔 없었다 — 아니었다 | 실험 ② 결론대로 기본값 유지. 끝나기를 기다린다 |
| `slow-query-report` [4]에 `wait_event_type='Lock'` 세션이 쌓여 있다 | **한 트랜잭션이 오래 쥐고 있다** — 느린 게 아니라 기다리는 것 | 장애 #4, 장애 #8(풀 고갈은 `@Async` 631 스레드 — DB 사고처럼 보이는 앱 사고) | 1절 ①. `blocked_by`가 앱 커넥션이면 앱 쪽 트랜잭션 경계를 본다. **풀을 올리지 않는다**(B4) |
| ⑤에서 `calls`는 그대로인데 `hit_per_call`이 크게 늘었다 | **계획이 바뀌었다** (통계 낡음, 인덱스 invalid) | D1에서 흔한 3자 검색이 통계 표본 따라 GIN을 쓰다 말다 했다 | `ANALYZE <table>` — 단, `work_logs`는 로케일 정렬로 50초(D1 부수 발견). `SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid` |

**해 본 기록.** 밤 14(2026-09-17, `AWS-MEASUREMENT-RESULTS.md`): 앱 재시작 → 12분 미회복 → DB 재시작 → 즉시 회복. 밤 16(2026-09-19): 위 네 번째 명령(cgroup)을 매 분 찍으며 재현, `docker update --memory 1g`만으로 회복 — 첫 행은 이제 실측이다.

---

## 이 문서를 고칠 때

- 새 시나리오는 겪고 나서. 절차는 "한 번 해 본 것"만 적는다(`DB-IMPROVEMENT-PLAN.md` 5절).
- 표의 "이 저장소에서 있었던 일" 열이 비면 그 행은 추측이다 — `[추측]`을 단다.
- 장애가 나면 `PG-BOOK-EXPERIMENTS.md` 5절에 한 줄, 여기에 행 하나. 둘 다.
