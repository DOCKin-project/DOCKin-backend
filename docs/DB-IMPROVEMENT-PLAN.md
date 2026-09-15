# DB 개선안 — 책 목록에서 바꿀 것으로

- 작성: 2026-09-15
- 관계: `PG-BOOK-EXPERIMENTS.md`가 **무엇을 잴 것인가**(실험 카드·장애 기록)라면 이 문서는 **무엇을 바꿀 것인가**다. 운영 항목 번호(D1~D6, O1~O6)는 `PRODUCTION-READINESS.md`의 것을 그대로 쓴다.
- 책 약칭: **막힘없이**(엑셈 『막힘없이 PostgreSQL』) · **TUNER**(디비안 『SQL TUNER for PostgreSQL 기본원리편』) · **Internals**(Rogov 『PostgreSQL 14 Internals』 한국어판) · **Wait**(엑셈 『PostgreSQL Wait Interface』) · **Admin**(디비안 『PostgreSQL DBA를 위한 Admin 이야기』) · **Smith**(『PostgreSQL 성능 최적화』)

---

## 0. 원칙 셋

1. **실측 없이 값을 바꾸지 않는다.** 각 항목의 "바꾸는 것" 열은 근거 열이 채워진 뒤에만 실행한다. 근거가 아직 실험 카드라면 항목 상태는 **조건부**다.
2. **안 바꾸는 것도 적는다.** 책이 권해도 이 저장소의 측정이 반대라면 그쪽을 따른다(4절).
3. **임계값은 실측 분포에서 온다.** `log_min_duration_statement` 같은 값을 "보통 이 정도"로 넣지 않는다. 값이 없으면 빈칸으로 둔다.

---

## 1. 지금 DB는 이렇다

| 항목 | 현재 | 어디 |
|---|---|---|
| 엔진 | PostgreSQL 17 + pgvector | `compose.yaml:145` |
| 컨테이너 | 512M / 1.5 cpu | `compose.yaml:186` |
| 서버 인자 | `lock_timeout=5s` · `deadlock_timeout=1s` · `shared_preload_libraries=pg_stat_statements` **셋뿐** | `compose.yaml:152~160` |
| 메모리 | `shared_buffers` 128MB · `work_mem` 4MB · `maintenance_work_mem` 64MB — **전부 기본값** | `WORK-BACKLOG.md:1181` |
| autovacuum | 기본값(`scale_factor` 0.2 · `cost_delay` 2ms · `cost_limit` 200). **테이블 단위 설정 0건** | — |
| 체크포인트·WAL | 기본값(`checkpoint_timeout` 5min · `max_wal_size` 1GB) | — |
| 커넥션 | `max_connections` 100(기본) · HikariCP `maximum-pool-size` **미지정**(기본 10) | `application.properties:16~17` |
| 락 로그 | `log_lock_waits` off(기본) | — |
| 관측 | `pg_stat_statements` 로드됨, **읽는 곳은 배치 검증 테스트 하나** | O4 △ |
| 백업 | 매일 02:00 `pg_dump -Fc`, RPO 24h. PITR 없음 | D1 △ |
| 복제 | **없음** | ADR-0004 3-3 계획만 |
| 무중단 DDL | 절차 없음. V6가 첫 실전 | D5 ❌ |
| 인덱스 | FK 인덱스(V3·V4), `idx_room_sent`, `(room_id, room_seq)` 유니크, HNSW. **복합 인덱스는 측정 후 안 넣음**(P2-15-8) | `docs/db/postgresql-schema.sql` |
| 페이징 | 색인은 keyset(P1-13). **작업일지 목록은 `Page<>`(COUNT 동반) + OFFSET** | `WorkLogRepository` |
| 검색 | `LIKE %kw%` — 작업일지·채팅. B-tree 무효 확인됨, `pg_trgm` 미측정 | P2-15-5 ⑤ |

---

## 2. 개선안

상태: **바로**(근거 확정, 실행만 남음) / **조건부**(실험 카드 결과 뒤) / **보류**(트리거 대기).

### A. 팽창·VACUUM — 막힘없이 Ch.2·부록, TUNER 10장

이 저장소의 장애 13건 중 5건이 팽창에 걸려 있는데 고친 것은 없다(`PG-BOOK-EXPERIMENTS.md` 5절). 그래서 A가 첫 묶음이다.

| ID | 바꾸는 것 | 근거(이 저장소) | 검증 | 상태 |
|---|---|---|---|---|
| **A1** | **팽창 관측을 들인다.** `pgstattuple` 확장 + `scripts/db/bloat-snapshot.sql`(`pg_stat_user_tables`의 `n_live_tup`·`n_dead_tup`·`last_autovacuum`·`autovacuum_count` + `pg_relation_size` + `pgstattuple.dead_tuple_percent`). 벤치·측정 스크립트가 끝날 때 이 스냅샷을 산출물에 같이 남긴다 | 780MB 힙(20,362행), 0행 26MB — 둘 다 `pg_relation_size` 하나로 우연히 봤다. dead tuple 수를 읽은 적이 없다 | 스냅샷이 산출물 폴더에 있는가. 실험 카드 ②의 전제 | **완료** (2026-09-15, 6절) |
| **A2** | **`work_logs`·`document_chunks`에 테이블 단위 autovacuum.** `ALTER TABLE ... SET (autovacuum_vacuum_scale_factor = _, autovacuum_vacuum_cost_delay = _)` — 값은 실험 ②가 낸다 | 두 테이블만 대량 삭제·삽입이 있다(코퍼스 보존 ADR-0007, 벤치 시드). 나머지는 행 수가 작아 기본값으로 충분 | 실험 ② 조건 A(기본) vs B(조정) — 98만 삭제 뒤 회수 완료까지 시간, 힙 크기 복귀 여부 | **조건부** (실험 ②) |
| **A3** | **대량 삭제 절차를 문서로.** ADR-0007 보존 삭제·벤치 뒷정리 뒤 `VACUUM (VERBOSE)`를 명시 실행하고, truncate가 잠금에 막히면 그 사실을 기록한다. `docs/db/`에 `rebuild-hnsw-index.sql`과 나란히 | truncate 거부(장애 #3)는 벤치 트랜잭션이 잠금을 쥔 채였다. 절차가 없어서 "언제 줄어드는지"를 아무도 몰랐다 | 절차대로 한 뒤 A1 스냅샷에서 힙이 줄었는가 | **완료** (2026-09-15, 6절) |
| **A4** | **작업일지 목록 `Page<>` → `Slice`.** COUNT를 없앤다 | ② COUNT 4초(무인덱스) → 12.8ms(인덱스 후). MVCC라 COUNT는 전부 센다(막힘없이 Ch.2). 화면이 전체 페이지 수를 쓰는지 먼저 확인 | 목록 1페이지 쿼리 수 2 → 1. `pg_stat_statements`에서 COUNT 문장이 사라졌는가 | **완료** (2026-09-15, 6절) — 고정 비용 4 → 3 |

### B. 메모리·WAL·커넥션 — 막힘없이 Ch.1, Internals 버퍼 캐시·WAL, Smith 체크포인트

| ID | 바꾸는 것 | 근거 | 검증 | 상태 |
|---|---|---|---|---|
| **B1** | **E8 열화 원인을 가른다** — `scripts/e8-longrun-cause.sh` 실행. 앱 재시작으로 돌아오면 앱, 안 돌아오면 DB. DB 쪽이면 `checkpoint_timeout`·`max_wal_size`·autovacuum 중 무엇인지 `pg_stat_bgwriter`(`checkpoints_timed`/`checkpoints_req`)와 A1 스냅샷으로 좁힌다 | 밤 2 1.8배 열화, 밤 3이 코퍼스 크기 반증. 후보 넷 미결(장애 #6) | 한 시간. 결과가 나오기 전엔 체크포인트 값을 **안 바꾼다** | **조건부** (AWS 1h) |
| **B2** | **`shared_buffers`는 안 올린다.** 컨테이너 512M도 그대로 | 밤 3: 128MB → 1GB, 컨테이너 4배에도 처리량 불변 | — | 결정 (4절) |
| **B3** | **`maintenance_work_mem`은 세션 단위로만.** 전역 인자로 안 올린다. 대신 `autovacuum_work_mem`을 따로 둘지는 실험 ②가 말한다 | HNSW 빌드는 `rebuild-hnsw-index.sql`이 `SET`으로 256MB를 준다. 전역으로 올리면 autovacuum worker 3개가 같은 값을 쓴다 — 512M 컨테이너에서 OOM(장애 #7의 `/dev/shm`과 같은 종류) | — | 결정 |
| **B4** | **HikariCP `maximum-pool-size`를 명시하고 `hikaricp.connections.pending` 알림(O2)을 건다.** 값은 지금의 기본 10을 그대로 적는다 — 올리지 않는다 | M1: 풀 고갈은 `@Async` 스레드 631개라는 **앱 결함**이었고, 풀 100은 증상을 가렸을 뿐이다. PG는 커넥션 = 백엔드 프로세스라 풀을 올린 만큼 DB 메모리를 낸다(막힘없이 Ch.1) | 알림이 실제 pending에 울리는가. 값 변경은 ADR-0004 3-2 부하 실측 뒤 | **바로** (명시+알림) / 값은 보류 |

### C. 락·대기 이벤트 — 막힘없이 Ch.3, Wait

| ID | 바꾸는 것 | 근거 | 검증 | 상태 |
|---|---|---|---|---|
| **C1** | **`log_lock_waits=on`** 서버 인자 한 줄. `deadlock_timeout`(1s)보다 오래 기다린 락을 로그에 남긴다 | 13분 44초 `transactionid` 대기(장애 #4)는 `pg_stat_activity`를 사람이 열어서야 알았다. 로그에 한 줄도 없었다 | `LockTimeoutVerificationTest`에서 대기 로그가 찍히는가 | **완료** (2026-09-15, 6절) |
| **C2** | **슬로우 쿼리 절차(O4)에 `wait_event` 열을 넣는다.** `pg_stat_activity`의 `wait_event_type`·`wait_event`·`pg_blocking_pids()` 스냅샷을 D3의 주간 절차에 포함 | ShadowFit은 `data_locks`로 GRANTED/WAITING을 실물로 봤고 DOCKin은 그 기록이 없다(2절 Ch.3). Wait 책의 주제 그 자체 | 실험 카드 ③의 산출물이 이 스냅샷으로 나오는가 | **바로** (절차) |
| **C3** | **교착 테스트 하나** — 두 트랜잭션이 반대 순서로 `FOR UPDATE`. `deadlock_timeout=1s`가 실제로 한쪽을 죽이는지 | 설정은 있는데 교착을 재현한 적이 없다 | 1초 안팎에 `deadlock detected`. 안 나오면 설정이 안 먹는 것 | **바로** |
| **C4** | **연차 승인의 REPEATABLE READ 대안을 ADR-0001 4-1 표에 한 줄 추가** — `FOR UPDATE` 없이 두 번째 승인이 `could not serialize`로 실패하는지, 재시도 비용은 | 4-1이 낙관락을 "재시도 복잡도"로 거절했다. RR도 같은 이유로 거절되는지 실측으로 답한다 | 실험 카드 ⑤ | **조건부** (실험 ⑤) |

### D. 실행계획·인덱스·페이징 — TUNER 2~8장, 막힘없이 Ch.4

| ID | 바꾸는 것 | 근거 | 검증 | 상태 |
|---|---|---|---|---|
| **D1** | **`pg_trgm` GIN을 측정한다** — `work_logs(title, log_text)`·`chat_messages(content)`. 붙일지는 측정이 정한다. **쓰기 비용과 인덱스 크기를 같이 잰다** — 작업일지는 쓰기도 있다 | ⑤ `LIKE %kw%` 432ms(100만). B-tree 무효는 확인됐고 GIN은 안 해봤다. `WORK-BACKLOG.md:150`이 "ngram FULLTEXT → `pg_trgm`/tsvector"로 미뤄둔 것 | 같은 100만·같은 `pg_prewarm` 조건에서 전후. 통제군 ⑥ 방식 그대로 | **조건부** (측정) |
| **D2** | **작업일지 목록 OFFSET → keyset** `(created_at, log_id)` 커서. 색인은 이미 했고(P1-13) 목록은 안 했다 | ③ 500페이지 150ms — OFFSET 10,000이 앞을 읽고 버린다. 정렬 없는 OFFSET은 행 중복·누락(P2-15-3) | ③이 1페이지(①)와 같은 자릿수가 되는가 | **구현 완료** (2026-09-15, 6절) — 100만 벤치 재측정은 남음 |
| **D3** | **`pg_stat_statements` 주간 top-10 절차**(O4) — `total_exec_time DESC`·`mean_exec_time DESC`·`calls DESC` 셋. `auto_explain`은 `log_min_duration`의 임계가 실측 분포에서 나오기 전엔 **안 켠다** | 확장은 로드돼 있는데 보는 사람이 없다(O4 △). 임의 임계 금지(0절 3) | 첫 주 top-10이 `docs/`에 남는가 | **바로** (절차) / auto_explain 보류 |
| **D4** | **복합 인덱스 `(user_id, created_at DESC, log_id DESC)`는 안 넣는다** | P2-15-8: 선택도 16%라 플래너가 안 고른다. 계획에 인덱스가 없는데 1.2배는 인덱스의 공이 아니다 | — | 결정 (4절) |

### E. 복제·백업·무중단 DDL — Admin 5·6장, 막힘없이 Ch.1(WAL)

DBA 지원서에서 비어 보이는 자리 셋(백업·복제·무중단 DDL)이 그대로 이 묶음이다. 백업은 D1이 채웠고 둘이 남았다.

| ID | 바꾸는 것 | 근거 | 검증 | 상태 |
|---|---|---|---|---|
| **E1** | **WAL 아카이브 + `pg_basebackup`(PITR).** `archive_mode=on`, `archive_command`로 볼륨/S3, 주 1회 베이스 백업. RPO 24h → 분 단위 | `OPERATIONS-BACKUP.md` 5절이 이미 설계했다. 트리거는 파일럿 H1의 "하루 유실이 얼마인가" | `recovery_target_time`으로 임의 시점 복구 리허설, 유실 행 수 | **보류** (H1 트리거) |
| **E2** | **스트리밍 복제 standby 하나 — 로컬 compose에서.** `pg_stat_replication`·복제 지연을 실측하고, ADR-0004 3-3(읽기 분리)의 전제를 만든다. 운영 투입은 아니다 | 복제 0건. ADR-0004 3-3이 Read Replica를 적었는데 붙여본 적이 없다. Admin 5장 | standby에서 `getMyAttendanceRecords` 조회, primary 쓰기 → standby 반영까지 ms. 지연이 채팅 따라잡기(ADR-0008)와 충돌하는지 | **바로** (로컬) |
| **E3** | **무중단 DDL 절차(D5).** ① `CREATE INDEX CONCURRENTLY`는 Flyway 트랜잭션 밖(`V__.sql.conf`의 `executeInTransaction=false`) ② 컬럼 추가는 nullable 먼저, NOT NULL은 채운 뒤 ③ DDL 앞에 `SET LOCAL lock_timeout`을 짧게 + 재시도 | V3 주석이 "CONCURRENTLY는 트랜잭션 안에서 못 한다"를 이미 안다. V6(ADR-0008)가 첫 실전이었는데 절차 없이 했다 | 다음 마이그레이션이 이 절차로 나가는가. 앱이 떠 있는 채로 인덱스를 만들며 `lock_timeout` 예외 0건 | **바로** (절차) |
| **E4** | **장애 대응 문서(O6)** — `PG-BOOK-EXPERIMENTS.md` 5절 장애 기록에서 시나리오 셋을 승격: "DB가 안 뜬다", "삭제가 안 끝난다"(#1·#2), "느려졌는데 재시작으로 안 돌아온다"(#6) | O6 ❌. E8의 "앱 재시작으로 안 돌아오면 DB도"가 이미 하나 | 시나리오마다 "무엇을 먼저 보나"(A1 스냅샷·C2 wait_event·`pg_stat_bgwriter`)가 적혀 있는가 | **바로** |

---

## 3. 순서

효과 × 근거 확정도 × 비용으로 늘어놨다. "바로"는 근거가 이미 있으니 비용 순, "조건부"는 실험 카드 순서(`PG-BOOK-EXPERIMENTS.md` 6절)를 따른다.

| 순서 | 묶음 | 왜 이 자리인가 | 크기 |
|---|---|---|---|
| ~~1~~ | ~~**A1 + C1 + A3** — 관측 들이기~~ | **완료 2026-09-15** (6절) | 반나절 |
| ~~2~~ | ~~**A4 + D2** — 목록 COUNT 제거·keyset~~ | **완료 2026-09-15** (6절). 100만 벤치 ③ 재측정만 남음 | 반나절 |
| 3 | **실험 ② → A2** — autovacuum | PostgreSQL 축 고유 실험. A1이 있어야 한다 | 하루 + 로컬 |
| 4 | **B4 + D3 + C2** — 풀 명시·알림, 슬로우 쿼리 절차 | O2·O4를 같이 채운다 | 반나절 |
| 5 | **E2 + E3** — 복제 로컬 실측·무중단 DDL 절차 | DBA 축 결손 둘. E2는 compose에 서비스 하나 | 하루 |
| 6 | **B1** — E8 원인 | AWS 한 시간. 결과가 체크포인트 값을 정한다 | 1h + $1 안팎 |
| 7 | **D1** — `pg_trgm` 측정 | 측정 뒤 붙일지 결정 | 반나절 |
| 8 | **C3 + C4 + E4** | 테스트 둘, 문서 하나 | 반나절 |
| — | E1 | H1 파일럿 트리거 | — |

---

## 4. 안 하는 것 — 책이 권해도

| 무엇 | 왜 안 하나 | 출처 |
|---|---|---|
| `shared_buffers` ↑ (RAM 25% 권고) | 8배 올려도 불변. **맞는 크기 계산이 원인의 증거가 아니었다** | 밤 3 |
| 복합 인덱스 `(user_id, created_at, log_id)` | 플래너가 안 고른다(선택도 16%). 쓰기 비용만 낸다 | P2-15-8 |
| `maintenance_work_mem` 전역 ↑ | autovacuum worker가 같이 먹는다. 512M 컨테이너 | B3 |
| HikariCP 풀 ↑ | 풀 고갈의 원인은 앱이었다. PG 커넥션은 프로세스다 | M1 |
| `auto_explain` 지금 켜기 | 임계값 근거가 없다. top-10 절차(D3)가 분포를 먼저 만든다 | 0절 3 |
| 파티셔닝 | 시계열 대용량이 없다. ShadowFit 소재 | ADR-0002 3절 |
| Redis 캐시 확장 | 단일 인스턴스. 로컬 캐시가 맞다 | ADR-0002 2-4 |
| 샤딩·클러스터 | 규모 밖 | ADR-0002 3절 |

---

## 5. 각 항목이 끝났다고 말하려면

- **설정 변경**(A2·B1·C1·B4): 변경 전후를 **같은 조건에서** 잰 표 + 통제군(같은 조건 반복) 한 줄. 밤 3의 D=A 방식.
- **쿼리 변경**(A4·D2·D1): `EXPLAIN (ANALYZE, BUFFERS)` 전후 + `pg_stat_statements`에서 옛 문장이 사라졌는가.
- **절차**(A3·C2·D3·E3·E4): 절차대로 **한 번 실제로 해 본** 기록. `OPERATIONS-BACKUP.md`가 "복구를 해 본 것까지가 백업이다"라고 한 것과 같은 기준.
- **결과는 답변이 아니라 문서에.** 실험은 `PG-BOOK-EXPERIMENTS.md` 카드의 "결과" 칸, 운영 항목은 `PRODUCTION-READINESS.md`의 상태 열.

---

## 6. 진행 기록

### 2026-09-15 — 순서 1: A1 · C1 · A3

로컬 `pgvector/pgvector:pg17` 일회용 컨테이너(`dockin-db`는 다른 세션이 쓰고 있어 건드리지 않았다)에
20만 행 테이블을 만들고 세 가지를 실제로 해 봤다. 절대 시간은 안 적는다 — 이 컨테이너의 값이다.

| ID | 무엇을 만들었나 | 확인한 것 |
|---|---|---|
| **A1** | `scripts/db/bloat-snapshot.sh` + `.sql` — `pg_stat_user_tables`(공짜) · `pgstattuple_approx`(가시성 맵으로 건너뛰는 추정) · autovacuum 설정·테이블 재정의 · 진행 중 VACUUM, 다섯 표를 파일 하나로 | 20만 중 18만 삭제 뒤 `dead 180,000 / 90.0%`, approx `dead_pct 84.6`. `pgstattuple`은 이미지에 contrib로 있어 스크립트가 `CREATE EXTENSION`한다 — Flyway엔 안 넣는다(V2의 `pg_stat_statements`와 같은 이유) |
| **C1** | `compose.yaml`·`ContainerTestSupport`에 `-c log_lock_waits=on` | 1초 넘는 `FOR UPDATE` 대기가 `process 129 still waiting for ShareLock on transaction 745 after 1000.203 ms / Process holding the lock: 122`로 남는다. 13분 44초 사건 때 없던 그 줄이다. `LockTimeoutVerificationTest` 통과 |
| **A3** | `docs/db/after-bulk-delete.sql` — 열린 트랜잭션 확인 → `VACUUM (VERBOSE)` 여섯 테이블 → `ANALYZE` → 안 줄었을 때 세 갈래 | 아래 셋 |

**A3를 쓰기 전에 알아야 했던 것 셋** — 절차 파일 머리말에 그대로 있다.

| 한 것 | `VACUUM (VERBOSE)`가 말한 것 | 힙 |
|---|---|---|
| 앞쪽 18만 행 삭제 | `tuples: 180000 removed` · **`pages: 0 removed`** | 112MB **그대로** |
| 꼬리 1만 행(714페이지, 5%) 삭제 | **`pages: 0 removed`** | 112MB 그대로 |
| 꼬리 10만 행(7,143페이지, 50%) 삭제 | **`truncated 14286 to 7143 pages`** | 112MB → **56MB** |

VACUUM은 행을 치우지 힙을 돌려주지 않는다. 돌려주는 것은 파일 끝의 빈 페이지뿐이고, 그것도 1,000페이지
또는 테이블의 1/16 이상일 때만 시도한다(`vacuumlazy.c`의 `REL_TRUNCATE_MINIMUM`/`FRACTION`).
**장애 #2의 780MB 힙(20,362행)은 앞쪽에 구멍이 난 첫 번째 경우다** — autovacuum이 왔어도 안 줄었을
것이고, 그건 autovacuum의 실패가 아니다. 실험 ②의 가설 (c)는 그래서 "truncate 여부"가 아니라
"구멍이 어디에 났는가"로 고쳐 물어야 한다.

부수 사고 하나: `psql -c "DELETE ...; VACUUM ...;"`처럼 한 `-c`에 두 문장을 넣으면 하나의
암묵 트랜잭션이 되어 VACUUM이 거부된다. 절차 파일은 그래서 `-f`로 한 줄씩 보내고 `BEGIN`을 넣지 않는다.

### 2026-09-15 — 순서 2: A4 · D2

작업일지 목록 셋(전체·타인·검색)을 `Page` → `Slice`, OFFSET → 커서 `(beforeCreatedAt, beforeLogId)`로.
채팅 `getChatHistory`의 `beforeSeq`와 같은 구조인데 정렬 키가 둘이라 커서도 둘이다.

| 무엇 | 어디 | 확인 |
|---|---|---|
| COUNT 제거 | `WorkLogRepository` 세 쿼리가 `Slice` | `WorkLogListQueryCountTest`: 페이지당 고정 비용 **4 → 3** (사라진 하나가 COUNT) |
| 커서 | 같은 쿼리에 `(:c IS NULL OR createdAt < :c OR (createdAt = :c AND logId < :id))`, `ORDER BY`는 쿼리에 고정 | 새 테스트 `커서_페이징`: 크기 7로 60행을 걸으면 OFFSET 걷기와 **같은 행·같은 순서**, 중복 0, 페이지 9=ceil(60/7). 같은 `createdAt` 3건을 크기 1로 넘기면 `logId` 내림차순으로 한 건씩 |
| 요청 sort 무시 | `WorkLogsService.sizeOnly` — 커서가 있으면 page 번호도 무시 | 커서 호출에 page=99를 줘도 결과 동일 |
| API | `Slice<WorkLogDto>` + `beforeCreatedAt`(ISO) · `beforeLogId`. 하나만 오면 400. `@PageableDefault(sort=...)`는 계약 표기로 유지(`PageableSortDefaultTest`) | Swagger description에 사용법 |

**응답 계약이 바뀐다** — `totalElements`·`totalPages`가 사라지고 다음 페이지 유무는 `last`다. 클라이언트가
전체 페이지 수를 쓰고 있었다면 거기가 깨진다. 채팅 목록이 이미 `Slice`라 클라이언트가 그 형태를 안다는 것이
근거이고, 확인은 못 했다(프론트가 이 저장소에 없다).

**JPA가 DB를 가려 주지 못한 자리 하나.** 커서가 null인 첫 페이지에서 PostgreSQL이
`could not determine data type of parameter`로 거부했다. Hibernate가 `? IS NULL`의 `?`를 타입 없이 보내고,
PostgreSQL은 타입 없는 파라미터를 받지 않는다. 채팅의 `:beforeSeq`(Long)는 같은 꼴로 통과했는데
`LocalDateTime`은 안 됐다. `CAST(:beforeCreatedAt AS Timestamp)`로 못 박아 해결. MySQL은 타입 없는 null을
받아 주므로 MySQL 시절이었으면 안 드러났을 종류다 — `@Lob`(장애 #11)과 같은 계열.

**안 잰 것**: ③ 500페이지 150ms가 커서로 얼마가 되는지. 100만 벤치 rig(`WorkLogListBenchmarkTest`)에
커서 케이스를 넣어 다시 돌려야 한다. 설계상 인덱스 없이는 `(created_at, log_id)` 정렬을 위해 어차피
후보 전체를 읽으므로(P2-15-8의 Parallel Seq Scan + top-N) **커서만으로는 ③이 ①과 같아지지 않을 수 있다** —
OFFSET이 버리는 1만 행은 없어지지만 정렬 비용은 남는다. 그때는 `(created_at DESC, log_id DESC)` 인덱스가
후보가 되는데, 그건 P2-15-8이 거절한 `(user_id, created_at, log_id)`와 다른 인덱스다. 측정 뒤 결정.
