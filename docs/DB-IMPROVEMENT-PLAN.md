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
| **A2** | **`work_logs`·`document_chunks`에 테이블 단위 autovacuum.** `ALTER TABLE ... SET (autovacuum_vacuum_scale_factor = _, autovacuum_vacuum_cost_delay = _)` — 값은 실험 ②가 낸다 | 두 테이블만 대량 삭제·삽입이 있다(코퍼스 보존 ADR-0007, 벤치 시드). 나머지는 행 수가 작아 기본값으로 충분 | 실험 ② 조건 A(기본) vs B(조정) — 98만 삭제 뒤 회수 완료까지 시간, 힙 크기 복귀 여부 | **결정: 기본값 유지** (측정 2026-09-15, 결정 2026-09-19, 6절·4절). 대량 삭제 뒤는 A3 절차로 사람이 `VACUUM (VERBOSE)` |
| **A3** | **대량 삭제 절차를 문서로.** ADR-0007 보존 삭제·벤치 뒷정리 뒤 `VACUUM (VERBOSE)`를 명시 실행하고, truncate가 잠금에 막히면 그 사실을 기록한다. `docs/db/`에 `rebuild-hnsw-index.sql`과 나란히 | truncate 거부(장애 #3)는 벤치 트랜잭션이 잠금을 쥔 채였다. 절차가 없어서 "언제 줄어드는지"를 아무도 몰랐다 | 절차대로 한 뒤 A1 스냅샷에서 힙이 줄었는가 | **완료** (2026-09-15, 6절) |
| **A4** | **작업일지 목록 `Page<>` → `Slice`.** COUNT를 없앤다 | ② COUNT 4초(무인덱스) → 12.8ms(인덱스 후). MVCC라 COUNT는 전부 센다(막힘없이 Ch.2). 화면이 전체 페이지 수를 쓰는지 먼저 확인 | 목록 1페이지 쿼리 수 2 → 1. `pg_stat_statements`에서 COUNT 문장이 사라졌는가 | **완료** (2026-09-15, 6절) — 고정 비용 4 → 3 |

### B. 메모리·WAL·커넥션 — 막힘없이 Ch.1, Internals 버퍼 캐시·WAL, Smith 체크포인트

| ID | 바꾸는 것 | 근거 | 검증 | 상태 |
|---|---|---|---|---|
| **B1** | **E8 열화 원인을 가른다** — `scripts/e8-longrun-cause.sh` 실행. 앱 재시작으로 돌아오면 앱, 안 돌아오면 DB. DB 쪽이면 `checkpoint_timeout`·`max_wal_size`·autovacuum 중 무엇인지 `pg_stat_bgwriter`(`checkpoints_timed`/`checkpoints_req`)와 A1 스냅샷으로 좁힌다 | 밤 2 1.8배 열화, 밤 3이 코퍼스 크기 반증. 후보 넷 미결(장애 #6) | 한 시간. 결과가 나오기 전엔 체크포인트 값을 **안 바꾼다** | **측정 완료 → DB 휘발 상태** (2026-09-17, 6절·AWS 밤 14). 앱 재시작 미회복, DB 재시작 즉시 회복. 체크포인트·autovacuum 아님 → **체크포인트 값은 안 바꾼다**(확정). 남은 후보는 컨테이너 512M의 페이지 캐시 `[추측]` #83 |
| **B2** | **`shared_buffers`는 안 올린다.** 컨테이너 512M도 그대로 | 밤 3: 128MB → 1GB, 컨테이너 4배에도 처리량 불변 | — | **반은 뒤집혔다** (2026-09-19, 밤 16). `shared_buffers`는 여전히 안 올린다 — 밤 16에서도 128MB 그대로 두고 회복했고 미스 수는 앞뒤가 같았다. **"컨테이너 512M 그대로"는 틀렸다**: 상한이 페이지 캐시까지 세서 힙+HNSW의 뜨거운 페이지가 512 − 공유메모리 142 − anon ≈ 330MB에 안 들어가는 자리(≈177k 청크)부터 INSERT가 3 → 26ms, 재시작 없이 `docker update --memory 1g` 한 줄로 다음 표본부터 2.8ms. 처방은 "힙 + HNSW + `shared_buffers` + 여유"가 들어가는 상한 — 지금 코퍼스면 1G. **운영 `compose.yaml` DB 상한 512M → 1G** (2026-09-19 결정, 사용자, (a)안) |
| **B3** | **`maintenance_work_mem`은 세션 단위로만.** 전역 인자로 안 올린다. 대신 `autovacuum_work_mem`을 따로 둘지는 실험 ②가 말한다 | HNSW 빌드는 `rebuild-hnsw-index.sql`이 `SET`으로 256MB를 준다. 전역으로 올리면 autovacuum worker 3개가 같은 값을 쓴다 — 512M 컨테이너에서 OOM(장애 #7의 `/dev/shm`과 같은 종류) | — | 결정 |
| **B4** | **HikariCP `maximum-pool-size`를 명시하고 `hikaricp.connections.pending` 알림(O2)을 건다.** 값은 지금의 기본 10을 그대로 적는다 — 올리지 않는다 | M1: 풀 고갈은 `@Async` 스레드 631개라는 **앱 결함**이었고, 풀 100은 증상을 가렸을 뿐이다. PG는 커넥션 = 백엔드 프로세스라 풀을 올린 만큼 DB 메모리를 낸다(막힘없이 Ch.1) | 알림이 실제 pending에 울리는가. 값 변경은 ADR-0004 3-2 부하 실측 뒤 | **완료** (2026-09-15, 6절) — 명시 + `HikariPoolWatch` WARN. 값은 보류 |

### C. 락·대기 이벤트 — 막힘없이 Ch.3, Wait

| ID | 바꾸는 것 | 근거 | 검증 | 상태 |
|---|---|---|---|---|
| **C1** | **`log_lock_waits=on`** 서버 인자 한 줄. `deadlock_timeout`(1s)보다 오래 기다린 락을 로그에 남긴다 | 13분 44초 `transactionid` 대기(장애 #4)는 `pg_stat_activity`를 사람이 열어서야 알았다. 로그에 한 줄도 없었다 | `LockTimeoutVerificationTest`에서 대기 로그가 찍히는가 | **완료** (2026-09-15, 6절) |
| **C2** | **슬로우 쿼리 절차(O4)에 `wait_event` 열을 넣는다.** `pg_stat_activity`의 `wait_event_type`·`wait_event`·`pg_blocking_pids()` 스냅샷을 D3의 주간 절차에 포함 | ShadowFit은 `data_locks`로 GRANTED/WAITING을 실물로 봤고 DOCKin은 그 기록이 없다(2절 Ch.3). Wait 책의 주제 그 자체 | 실험 카드 ③의 산출물이 이 스냅샷으로 나오는가 | **완료** (2026-09-15, 6절) — `slow-query-report.sql` [4] |
| **C3** | **교착 테스트 하나** — 두 트랜잭션이 반대 순서로 `FOR UPDATE`. `deadlock_timeout=1s`가 실제로 한쪽을 죽이는지 | 설정은 있는데 교착을 재현한 적이 없다 | 1초 안팎에 `deadlock detected`. 안 나오면 설정이 안 먹는 것 | **완료** (2026-09-19, 6절) — 1,106ms에 40P01, 55P03 0건 |
| **C4** | **연차 승인의 REPEATABLE READ 대안을 ADR-0001 4-1 표에 한 줄 추가** — `FOR UPDATE` 없이 두 번째 승인이 `could not serialize`로 실패하는지, 재시도 비용은 | 4-1이 낙관락을 "재시도 복잡도"로 거절했다. RR도 같은 이유로 거절되는지 실측으로 답한다 | 실험 카드 ⑤ | **완료** (2026-09-19, 6절) — 같은 이유로 거절. 대기도 안 준다 |

### D. 실행계획·인덱스·페이징 — TUNER 2~8장, 막힘없이 Ch.4

| ID | 바꾸는 것 | 근거 | 검증 | 상태 |
|---|---|---|---|---|
| **D1** | **`pg_trgm` GIN을 측정한다** — `work_logs(title, log_text)`·`chat_messages(content)`. 붙일지는 측정이 정한다. **쓰기 비용과 인덱스 크기를 같이 잰다** — 작업일지는 쓰기도 있다 | ⑤ `LIKE %kw%` 432ms(100만). B-tree 무효는 확인됐고 GIN은 안 해봤다. `WORK-BACKLOG.md:150`이 "ngram FULLTEXT → `pg_trgm`/tsvector"로 미뤄둔 것 | 같은 100만·같은 `pg_prewarm` 조건에서 전후. 통제군 ⑥ 방식 그대로 | **결정: 지금은 안 붙인다** (측정 2026-09-16, 결정 2026-09-19, 6절·4절). 희귀 4자에서만 160배(희귀 3자 `[실측 필요]`), 흔한 3자는 변동, 2자는 무효. 크기 39%·쓰기 4.5배. 재검토 조건은 4절, 느린 자리 자체는 #91(측정 2026-09-20, 4절 — 비정규화도 안 한다 추천) |
| **D2** | **작업일지 목록 OFFSET → keyset** `(created_at, log_id)` 커서. 색인은 이미 했고(P1-13) 목록은 안 했다 | ③ 500페이지 150ms — OFFSET 10,000이 앞을 읽고 버린다. 정렬 없는 OFFSET은 행 중복·누락(P2-15-3) | ③이 1페이지(①)와 같은 자릿수가 되는가 | **구현 완료** (2026-09-15, 6절) — 100만 벤치 재측정은 남음 |
| **D3** | **`pg_stat_statements` 주간 top-10 절차**(O4) — `total_exec_time DESC`·`mean_exec_time DESC`·`calls DESC` 셋. `auto_explain`은 `log_min_duration`의 임계가 실측 분포에서 나오기 전엔 **안 켠다** | 확장은 로드돼 있는데 보는 사람이 없다(O4 △). 임의 임계 금지(0절 3) | 첫 주 top-10이 `docs/`에 남는가 | **완료** (2026-09-15, 6절) — `OPERATIONS-SLOW-QUERY.md`. auto_explain은 4주 뒤 |
| **D4** | **복합 인덱스 `(user_id, created_at DESC, log_id DESC)`는 안 넣는다** | P2-15-8: 선택도 16%라 플래너가 안 고른다. 계획에 인덱스가 없는데 1.2배는 인덱스의 공이 아니다 | — | 결정 (4절) |

### E. 복제·백업·무중단 DDL — Admin 5·6장, 막힘없이 Ch.1(WAL)

DBA 지원서에서 비어 보이는 자리 셋(백업·복제·무중단 DDL)이 그대로 이 묶음이다. 백업은 D1이 채웠고 둘이 남았다.

| ID | 바꾸는 것 | 근거 | 검증 | 상태 |
|---|---|---|---|---|
| **E1** | **WAL 아카이브 + `pg_basebackup`(PITR).** `archive_mode=on`, `archive_command`로 볼륨/S3, 주 1회 베이스 백업. RPO 24h → 분 단위 | `OPERATIONS-BACKUP.md` 5절이 이미 설계했다. 트리거는 파일럿 H1의 "하루 유실이 얼마인가" | `recovery_target_time`으로 임의 시점 복구 리허설, 유실 행 수 | **보류** (H1 트리거) |
| **E2** | **스트리밍 복제 standby 하나 — 로컬 compose에서.** `pg_stat_replication`·복제 지연을 실측하고, ADR-0004 3-3(읽기 분리)의 전제를 만든다. 운영 투입은 아니다 | 복제 0건. ADR-0004 3-3이 Read Replica를 적었는데 붙여본 적이 없다. Admin 5장 | standby에서 `getMyAttendanceRecords` 조회, primary 쓰기 → standby 반영까지 ms. 지연이 채팅 따라잡기(ADR-0008)와 충돌하는지 | **측정 완료 2026-09-15** (6절). 유휴 p50 81ms, 부하 중 p50 382·max 856ms. 따라잡기 `after?seq=`는 standby 불가 |
| **E3** | **무중단 DDL 절차(D5).** ① `CREATE INDEX CONCURRENTLY`는 파일 하나에 단독 + `spring.flyway.postgresql.transactional-lock=false`(`.sql.conf`는 필요 없었다) ② 컬럼 추가는 nullable 먼저, NOT NULL은 채운 뒤 ③ DDL 앞에 `SET LOCAL lock_timeout`을 짧게 + 재시도 | V3 주석이 "CONCURRENTLY는 트랜잭션 안에서 못 한다"를 이미 안다. V6(ADR-0008)가 첫 실전이었는데 절차 없이 했다 | 다음 마이그레이션이 이 절차로 나가는가. 앱이 떠 있는 채로 인덱스를 만들며 `lock_timeout` 예외 0건 | **절차 완료 2026-09-16** (6절, `docs/db/online-ddl.md`). ①은 테스트로 검증, 운영 규모 실전은 D1에서 — 100만 행 GIN을 CONCURRENTLY로 180s(일반 90s의 2배), invalid 0건 |
| **E4** | **장애 대응 문서(O6)** — `PG-BOOK-EXPERIMENTS.md` 5절 장애 기록에서 시나리오 셋을 승격: "DB가 안 뜬다", "삭제가 안 끝난다"(#1·#2), "느려졌는데 재시작으로 안 돌아온다"(#6) | O6 ❌. E8의 "앱 재시작으로 안 돌아오면 DB도"가 이미 하나 | 시나리오마다 "무엇을 먼저 보나"(A1 스냅샷·C2 wait_event·`pg_stat_bgwriter`)가 적혀 있는가 | **완료** (2026-09-19, 6절) — `docs/db/incident-response.md`. 번역 서버·디스크는 #89 |

---

## 3. 순서

효과 × 근거 확정도 × 비용으로 늘어놨다. "바로"는 근거가 이미 있으니 비용 순, "조건부"는 실험 카드 순서(`PG-BOOK-EXPERIMENTS.md` 6절)를 따른다.

| 순서 | 묶음 | 왜 이 자리인가 | 크기 |
|---|---|---|---|
| ~~1~~ | ~~**A1 + C1 + A3** — 관측 들이기~~ | **완료 2026-09-15** (6절) | 반나절 |
| ~~2~~ | ~~**A4 + D2** — 목록 COUNT 제거·keyset~~ | **완료 2026-09-15** (6절). 100만 벤치 ③ 재측정만 남음 | 반나절 |
| ~~3~~ | ~~**실험 ② → A2** — autovacuum~~ | **측정 완료 2026-09-15, 결정 2026-09-19** (6절). 2ms→0은 2~3배지만 절대 15초 → 기본값 유지 | 하루 + 로컬 |
| ~~4~~ | ~~**B4 + D3 + C2** — 풀 명시·알림, 슬로우 쿼리 절차~~ | **완료 2026-09-15** (6절) | 반나절 |
| ~~5~~ | ~~**E2 + E3** — 복제 로컬 실측·무중단 DDL 절차~~ | **완료 2026-09-15~16** (6절). E2는 compose가 아니라 일회용 rig로 | 하루 |
| ~~6~~ | ~~**B1** — E8 원인~~ | **완료 2026-09-17** (6절, AWS 밤 14). 코퍼스가 없어 5h·$2.5. 체크포인트 값은 안 바꾼다. 남은 건 #83(컨테이너 메모리)·#84(창 A·B) | 하루 |
| ~~7~~ | ~~**D1** — `pg_trgm` 측정~~ | **측정 완료 2026-09-16, 결정 2026-09-19** (6절). 안 붙인다 — 느린 자리가 LIKE가 아니었다(#91) | 반나절 |
| ~~8~~ | ~~**C3 + C4 + E4**~~ | **완료 2026-09-19** (6절). ③의 관측 스레드·O6의 나머지 둘(#89)만 남음 | 반나절 |
| — | E1 | H1 파일럿 트리거 | — |

---

## 4. 안 하는 것 — 책이 권해도

| 무엇 | 왜 안 하나 | 출처 |
|---|---|---|
| `shared_buffers` ↑ (RAM 25% 권고) | 8배 올려도 불변. **맞는 크기 계산이 원인의 증거가 아니었다** | 밤 3 |
| 복합 인덱스 `(user_id, created_at, log_id)` | 플래너가 안 고른다(선택도 16%). 쓰기 비용만 낸다 | P2-15-8 |
| `maintenance_work_mem` 전역 ↑ | autovacuum worker가 같이 먹는다. 512M 컨테이너 | B3 |
| `autovacuum_vacuum_cost_delay` 0 (테이블 단위) | 2~3배 빠르지만 절대 15초(279MB), 780MB로 환산해도 1분 안팎. 아무도 기다리지 않는 시간을 위해 I/O를 3배 쓴다. **결정 2026-09-19: 기본값 유지.** 대량 삭제 뒤는 A3 절차(`after-bulk-delete.sql`)로 사람이 돌린다. 되돌릴 조건: 대량 삭제가 사람이 아니라 스케줄러 몫이 되고 그 회수 시간이 다음 배치를 막을 때 | 실험 ② |
| `pg_trgm` GIN을 `work_logs`에 지금 | 계획이 바뀐 건 **희귀 4자** 키워드뿐(160배, 희귀 3자는 `[실측 필요]`). 흔한 3자는 통계 표본 따라 120↔260ms 변동, 2자는 트라이그램이 없어 무효. 대가는 힙의 39%·벌크 쓰기 4.5배·512MB 캐시 경합 가능성`[추정]`. 실제 검색의 240ms는 LIKE가 아니라 구역 필터+정렬이다. **결정 2026-09-19: 지금은 안 붙인다.** 되돌릴 조건: 행 수십만 + D3 보고서 [2]에 3자 이상 희귀어 검색이 보일 때 — 그때도 `title`만(24MB, 11.5s)이 먼저. 느린 자리 자체(구역 필터+정렬)는 #91 | D1 |
| `work_logs.ship_yard_area` 비정규화 + `(ship_yard_area, created_at, log_id)` (#91) | 100만 행에서 #127(조인+V10) 대비 이기는 자리가 셋뿐이고 절대값이 작다: 1% 소구역 목록 7→0.7ms, 소구역 흔한 검색 77→1.7ms, 17% 구역 희귀 검색 0.7~2.6s→0.2~0.5s. **17%·50% 구역의 목록·흔한 검색은 A·B 모두 1~3ms.** 검색의 진짜 최악(구역에 없는 키워드 = 정렬 인덱스 100만 행 끝까지, 1g 2.1s·512m 31~47s)은 비정규화가 구역 비율만큼만 줄이고 모양은 같다(1.5s·46~53s). 대가는 이관 — 백필 UPDATE 100s 동안 힙 두 배(477→972MB), 되돌리려면 `VACUUM FULL` 1분 잠금 또는 pg_repack, 인덱스 39MB, 작성자 구역 변경 시 옛 일지의 구역 결정. **추천 2026-09-20: 지금은 안 한다(결정은 사용자).** 되돌릴 조건: 사용자 1% 미만 구역의 목록·검색이 D3 [2]에 보일 때, 또는 검색 기간 상한을 못 두는 요구가 확정될 때. 없는 키워드 경로는 #154 | #91 측정 `measure/area/` |
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

### 2026-09-15 — 순서 3: 실험 ② → A2

`scripts/db/autovacuum-lab.sh` (새 rig). 50만 행(279MB) 시드 → 앞쪽 45만 삭제 → autovacuum이 치울 때까지.
`cost_delay` 2ms(기본)·0을 A→B→A→B로 네 판. 결과 전문과 rig 함정 6개: `measure/bloat/autovac-20260915T150118/README.md`.

| `cost_delay` | VACUUM 자체(서버 로그 elapsed) | 읽기 속도 |
|---|---|---|
| 2ms | **24.58 / 25.67 s** (폭 4%) | 11 MB/s |
| 0 | **8.23 / 13.18 s** (폭 60%) | 21~34 MB/s |

네 판이 한 일은 동일하다(32,144 pages · 450,000 removed · WAL 8.3MB). 차이는 전부 `cost_delay`고,
로그의 buffer usage로 cost를 세면 **일부러 자는 시간이 10.8s**로 실측 차이(11~17s)와 맞는다.
힙은 네 판 모두 안 줄었다(`pages: 0 removed`) — 앞쪽 구멍이라 예상대로.

**A2 판단(추천 → 2026-09-19 결정, 추천대로)**: 기본값 유지. 780MB로 환산해도 1분 안팎이고 아무도 그 시간을 기다리지 않는다.
사고의 5분·24분·40분은 각각 FK 인덱스 부재·FK 인덱스 부재·HNSW 인덱스 청소였지 힙 VACUUM이 아니었다.
`document_chunks`(HNSW)는 별도 판 — 지배항이 `cost_delay`가 아니라 인덱스 청소다.

**부수 발견 → 이슈 #42에 붙임**: 시드 50만 INSERT만으로 autovacuum이 온다(PG13+ INSERT 트리거, 20%마다).
밤 2의 3시간 연속 색인은 `document_chunks`에 이것을 여러 번 불렀을 것이고, 거기선 한 번이 40분급이다.
E8 열화 후보 "autovacuum 개입"에 처음으로 **메커니즘**이 붙었다. `[미검증]`.

### 2026-09-15 — 순서 4: B4 · D3 · C2

| ID | 무엇을 만들었나 | 확인 |
|---|---|---|
| **B4** | `maximum-pool-size=10` 명시(값은 안 올림) + `HikariPoolWatch` — 10초마다 `pending`을 읽고 0이 아니면 WARN 한 줄(`pending/active/idle/total/max`) | `HikariPoolWatchTest`: 풀 2를 다 잡고 세 번째가 기다리는 1초 동안 WARN 4줄(200ms 주기), 여유 있는 구간은 0줄. 첫 줄 `pending=1 active=2 idle=0 total=2 max=2` |
| **D3** | `scripts/db/slow-query-report.{sql,sh}` + `docs/OPERATIONS-SLOW-QUERY.md` — 누적·평균·호출 top 10, 통계 창(`stats_reset`), 1분 넘은 트랜잭션, 서버 로그 락 대기 수 | 일회용 컨테이너에서 여섯 표 전부 출력. 임계값 없음 — 4주 분포 뒤 |
| **C2** | 같은 보고서의 [4] — `pg_stat_activity.wait_event_type/wait_event` + `pg_blocking_pids()` | `FOR UPDATE` 대기를 걸어 두고 뽑으니 `pid 95 · Lock · transactionid · blocked_by {87}`. 장애 #4가 이 줄로 보였어야 했다 |

**알림 인프라가 없다는 것을 그대로 적었다.** O2는 ❌에서 △ — 풀 고갈 하나만 WARN으로, 나머지 셋은 그대로 ❌. 로그 집계(O3)가 붙으면 그 WARN 문자열이 알림 조건이 된다. 지금 Slack을 붙이는 것은 순서가 아니다.

### 2026-09-15~16 — 순서 5: E2 · E3

**E2** `scripts/db/replication-lab.sh` (새 rig). primary 시드 30만 행(183MB) → `pg_basebackup`으로 standby → 유휴 프로브 20회 → 60초 부하 중 프로브 → 읽기 분리 → promote.
결과 전문·rig 함정 8개: `measure/replication/repl-20260915T164004/README.md`.

| 항목 | 값 (같은 호스트, 네트워크 없음 — 자릿수만 볼 것) |
|---|---|
| 반영 지연(유휴, n=20) | **p50 81 · p95 341 ms** |
| 반영 지연(부하 중, n=8) | **p50 382 · max 856 ms**, 밀린 WAL 최대 9MB |
| 부하 뒤 따라잡기 | 47s — 내역은 못 갈랐다(standby 로그 유실, rig 고침) |
| promote → 쓰기 가능 | 10.8s(docker exec 왕복 포함), 직후 split brain |

**ADR-0004 3-3에 대입하면 읽기 분리는 지금 답이 "없다"에 가깝다.** 채팅 `after?seq=`는 재접속 순간 primary에만 있는 메시지(340ms면 15건)를
따라잡기·실시간 양쪽에서 놓치고, `clockin` 직후 조회도 같은 read-after-write 꼴이다. 버스트 중 조회를 보호하려던 건데 버스트가 곧 쓰기 부하고 그때 지연이 가장 나쁘다.
복제의 첫 용도는 HA·백업 오프로드다. → ADR-0004 3-3에 한 줄.

**E3** `docs/db/online-ddl.md` + `OnlineDdlMigrationTest`. 첫 가설이 둘 다 틀렸다:

| 가설 | 실제 |
|---|---|
| `.sql.conf`의 `executeInTransaction=false`가 있어야 CONCURRENTLY가 돈다 | Flyway 11 PostgreSQL 파서가 알아서 트랜잭션 밖에서 돌린다 — 필요 없다 |
| 없으면 `cannot run inside a transaction block` | **`55P03 lock timeout`** — Flyway가 히스토리 advisory lock을 트랜잭션 수준으로 잡아 `idle in transaction`이고, CONCURRENTLY가 그 스냅샷이 끝나길 기다린다. **자기 자신을 기다린다.** `lock_timeout` 기본값(0) 서버였으면 기동이 멈춘다 |

해법 `spring.flyway.postgresql.transactional-lock=false`(`application.properties`). 테스트는 없으면 55P03 + invalid 인덱스, 있으면 유효한 인덱스를 둘 다 본다.
덤: 실패한 CONCURRENTLY는 invalid 인덱스를 남기고 재시도의 `IF NOT EXISTS`가 **그것을 보고 건너뛴다** — 히스토리엔 아무것도 안 남아(PostgreSQL에선 성공 뒤에만 쓴다, 확인) 기동은 성공하고 인덱스는 없다. 재시도 전 `DROP INDEX CONCURRENTLY`.
② 컬럼 추가(nullable → 배치 채움 → `CHECK NOT VALID` → `VALIDATE` → `SET NOT NULL`)와 ③ `SET LOCAL lock_timeout='2s'`는 절차로만 있다 — **운영 규모 실전은 D1 때**(5절 기준으론 그때 "했다").
V3는 안 고친다(체크섬, 그리고 인덱스는 이미 있다).

### 2026-09-16 — 순서 7: D1

`scripts/db/trgm-lab.sh` (새 rig). `work_logs` 꼴 100만 행(469MB, 코퍼스 생성기 어휘) → `gin_trgm_ops` 전후 A B A B.
검색 다섯(실제 `searchWorkLogs` SQL로 희귀/흔함 × 4자/3자/2자 + 벤치 ⑤ 원문), 빌드 시간(CONCURRENTLY·일반), 크기, 쓰기 3회.
결과 전문·rig 함정 7개: `measure/trgm/trgm-20260916T135051/README.md`.

| 검색 (실제 SQL, ms 중앙값) | A | B | A→B | B의 계획 |
|---|---|---|---|---|
| 희귀 4자 `'크랭크축'` | 241 / 300 | **1.7 / 1.7** | **160배** | GIN BitmapOr, 힙 98블록 |
| 흔함 3자 `'베어링'` | 278 / 231 | 261 / 122 | 1.3배 | 한 패스는 GIN 안 씀, 한 패스는 BitmapAnd — 통계 표본 따라 흔들린다 |
| 2자 `'균열'`·`'마모'` | 246 / 227 · 238 / 230 | 243 / 413 · 246 / 334 | 0.7~0.8배 | GIN **못 씀** — 2자 패턴엔 트라이그램이 없다 |

| 대가 | |
|---|---|
| 크기 | `title` 24MB + `log_text` 157MB = 힙의 **39%** |
| 빌드 | CONCURRENTLY 23.7s + **180.4s**, 일반 11.5s + 89.8s — 온라인이 2배 (E3 절차의 첫 운영 규모 실전, invalid 0건) |
| 쓰기 50,000행 × 3 중앙값 | 3.3 / 2.7s → 13.4 / 13.7s = **4.5배** |
| 캐시 | prewarm 498MB → 681MB, 512MB 컨테이너를 넘는다 — 작업 집합과 한도의 비교일 뿐, 힙 상주율·퇴출은 안 쟀다 `[추정]`(`pg_buffercache`로 확인) |

**느린 자리는 LIKE가 아니었다.** A의 네 검색이 키워드와 무관하게 전부 240ms고 계획이 같다 — `user_id` 인덱스로 구역 84명의 16만 8천 행을
찾는데 그 행들이 힙 6만 페이지 전체에 흩어져 있어 3만 페이지를 읽고, `ORDER BY`가 있어 `LIMIT 20`이 일찍 못 끝난다. 키워드는 읽은 뒤 거른다.
P2-15-5 ⑤가 "B-tree로 안 변한다"고 한 그 비용은 키워드 필터가 아니라 **구역 필터 + 정렬**이고, GIN은 그 자리의 답이 아니다.

**D1 판단(추천 → 2026-09-19 결정, 추천대로)**: 지금은 안 붙인다 → 4절. 느린 자리 자체를 고치는 선택지(구역 컬럼 비정규화)는 #91. 잰 것 중 계획이 바뀐 건 희귀 4자뿐이고(희귀 3자 `[실측 필요]`), 한국어 검색어는 2자가 흔하다(설비·부위·증상).
바뀌는 조건: 행이 수십만을 넘고 D3의 `pg_stat_statements`에 3자 이상(트라이그램이 생기는 길이) 희귀어 검색이 상위로 보일 때. 그때는 `title`만(24MB, 11.5s) 붙이는 선택지가 먼저다.
2자를 하려면 `pg_bigm`인데 이 이미지엔 없다.

**부수 발견 — `ANALYZE`가 행 수와 무관하게 50초.** 표본 30,000행을 en_US.utf8 `strcoll`로 정렬하는 값이다(`log_text` 42s·`title` 11s,
`COLLATE "C"`면 0.4s). 운영 `dockin-db`도 같은 로케일이라 **autovacuum의 auto-analyze가 `work_logs`에 올 때마다 CPU 50초**를 쓸 것이다
— 잠금은 읽기·쓰기를 안 막는다. `[미검증: 운영 실측 없음]`. 후보 처방은 `ALTER COLUMN log_text SET STATISTICS 10` — 본문 히스토그램은 아무 쿼리도 안 쓴다.

### 2026-09-17 — 순서 6: B1 (AWS 밤 14)

`scripts/e8-longrun-cause.sh`를 다시 짜서(PR #74) `m7i.2xlarge`에서 0 → 24만 청크를 연속 색인했다. 밤 4까지의 인스턴스·EBS가 계정에서 사라져 코퍼스를 다시 만들어야 했고, 그 3시간 반이 곧 밤 2 재현이다.
매 분 원본당 ms 옆에 DB가 센 값(`pg_stat_statements`의 INSERT/건, `pg_stat_checkpointer`, WAL, dead tuple·autovacuum, 힙·HNSW 크기, 컨테이너 CPU%)을 같은 줄에 적었다. 결과 전문: `AWS-MEASUREMENT-RESULTS.md` 밤 14, 산출물 `measure/e8cause-20260917T135138/`.

| 구간 | 원본당 | INSERT/건 | |
|---|---|---|---|
| 0 ~ 177k 청크 | 63ms | 0.7 → 2.7ms | 평평. 기준선 63.0 |
| 177k ~ 208k | 77 → 105ms | 10 → 70ms | 밤 2와 같은 자리, 같은 기울기 |
| 앱 재시작 뒤 12분 | 중앙값 105 | 41ms | **미회복** |
| DB 재시작 뒤 | **60.6** | **2.7ms** | **즉시 회복.** 인덱스는 계속 커지는데 그대로 빠르다 |

**갈린 것.** 원인은 DB 프로세스를 새로 띄우면 사라지는 상태다. 체크포인트(timed 41 / requested 0)와 autovacuum(18회, dead 0, 열화 구간에 진행 중 없음)은 아니다 → **B1의 "결과가 체크포인트 값을 정한다"의 답은 "안 바꾼다"**, #42 닫음.
`pg_stat_statements` 델타로 한 겹 더: 재시작 앞뒤로 INSERT 한 건의 `shared_blks_read`는 503 vs 518로 같고 시간만 40.7 vs 2.96ms — 버퍼 미스 수가 아니라 **미스가 디스크로 가느냐 커널 캐시에서 채워지느냐**가 달랐다. 남는 설명은 DB 컨테이너 512M이 페이지 캐시까지 세는 것 `[추측]`인데 cgroup 메모리를 안 적어 미확정 → #83. 확정되면 B2("컨테이너 512M 그대로")를 다시 쓴다.

**5절 기준으로는** 설정 변경이 없으므로 표는 "변경 전후"가 아니라 "재시작 전후"다 — 통제군은 재시작 뒤 14표본이 기준선 구간 122표본과 같은 값(61 vs 62)이라는 것.
남은 것: 완주 뒤 같은 인스턴스에서 창 A(구멍 재색인)·REINDEX·창 B — 결과는 #84로 밤 14 절에 덧붙인다. → **2026-09-19 유실**(인스턴스·EBS가 사라짐, #86). #83 재실험에 얹는다.

### 2026-09-19 — 순서 8: C3 · C4 · E4

테스트 둘은 컨테이너(pgvector pg17, `-c deadlock_timeout=1s -c lock_timeout=5s`)에서 돌렸다. 둘 다 CI에 남는다.

**C3 — `DeadlockDetectionTest`.** 행 A·B를 두 커넥션이 반대 순서로 `FOR UPDATE`. T1이 막힌 뒤 셋째 커넥션이 관측하고, 그 다음 T2가 사이클을 닫는다(순서 고정이라 시간 경합 없음).

| | |
|---|---|
| T1 대기 중 `pg_stat_activity` | `Lock` / `transactionid` / `pg_blocking_pids` = T2 — 장애 #4의 모양 |
| 희생자 | T1, **1,106ms**에 `40P01 deadlock detected` |
| 생존자 | T2, 970ms 만에 A를 얻고 커밋 |
| `55P03` (lock_timeout 5s) | 0건 |

설정이 먹는다. 교착 감지(1s)가 락 대기 타임아웃(5s)보다 먼저 오므로, 운영에서 교착은 "5초 뒤 둘 다 죽음"이 아니라 "1초 뒤 한쪽만 죽음"으로 보인다. 앱 코드에 `40P01` 재시도는 없다 — 연차 승인처럼 관리자가 간헐적으로 누르는 경로는 한 번 실패로 족하고, 지금 행 락을 잡는 경로는 둘(`MemberRepository.findByUserIdForUpdate`, `ChatJdbcRepository.nextRoomSeq`의 `UPDATE … RETURNING`)인데 한 트랜잭션이 둘 다 잡는 자리가 없어 사이클이 성립하지 않는다 — 그런 경로가 생기면 이 테스트가 기준이다.

**C4 — `LeaveBalanceConcurrencyTest` 셋째 조건(REPEATABLE READ, 락 없음).** 실험 카드 ⑤.

| 조건 | 승인 | 실패 | 잔액 | 진 쪽이 기다린 시간 |
|---|---|---|---|---|
| 락 없음 (RC) | 2건 | — | 2일 (**3일 증발**) | — |
| `FOR UPDATE` | 1건 | 검사에서 거절 | 2일 | 첫째 커밋까지 |
| **REPEATABLE READ** | **1건** | **`40001` 1건** | **2일** | **99ms** (첫째 커밋까지 막혔다가 죽음) |

가설대로 lost update는 막힌다. 그런데 ① 진 쪽이 "잔액 부족"이 아니라 예외라 **재시도해서 다시 읽어야** 사용자에게 맞는 답이 가고(낙관락과 같은 복잡도), ② 둘째 UPDATE는 어차피 첫째 커밋까지 **막힌다** — 비관락 대비 대기가 줄지 않는다. ADR-0001 4-1 대안 목록에 한 줄 추가, 선택(`FOR UPDATE`)은 그대로.

**E4 — `docs/db/incident-response.md`.** 장애 기록 13건 중 겪고 풀어 본 셋을 시나리오로: "DB가 안 뜬다"(#71·E3·#12), "삭제가 안 끝난다 / 안 줄어든다"(#1·#2·#3·#5), "느려졌는데 재시작으로 안 돌아온다"(#6·밤 14). 시나리오마다 앱 로그 첫 줄 또는 SQL 셋으로 원인을 가르는 표 + "이 저장소에서 있었던 일" 열 + 해 본 기록. 명령은 전부 pg17 컨테이너에서 한 번 돌려 확인했다(FK 무인덱스 질의는 인덱스 없는 자식만 잡는 것까지). 5절 기준 "한 번 해 본 기록"은 각 시나리오의 원 장애가 그것이다. O6는 △ — 번역 서버·디스크는 겪은 적이 없어 #89로.

**남은 것.** 카드 ③의 관측 스레드(기존 두 테스트에 붙여 산출물 파일로), #83 cgroup 재실험(+창 A·B), #89. 순서표는 다 지웠다 — A2·D1은 2026-09-19에 추천대로 결정했다(4절). 다음은 E1(H1 트리거)과 #91(트리거 대기)뿐이다. → #91은 2026-09-20에 트리거 전에 쟀다(아래·4절).

### 2026-09-20 — #91 + #118 후속: 구역 필터 자리 셋을 100만 행에서 나란히

#127(구역 조인 + V10)이 12만 행에서 잰 것을 100만 행·구역 크기 셋(17%·50%·1%)·컨테이너 512m과 1g에서 다시 재고, #91의 비정규화 컬럼(B)을 그 옆에 놓았다. rig `scripts/db/area-denorm-lab.sh`, 결과 전문 `measure/area/area-20260919T234301/README.md`(+ `area-1g-20260920T002435/`).

| 구역 | 쿼리 | O (#127 전) | A (#127) | B (비정규화) |
|---|---|---|---|---|
| 17% | 목록 첫 페이지 | 358 | 1.7 / 1.4 | 0.6 |
| 50% | 검색 흔함 | 405 | 1.8 / 5.2 | 1.1 |
| 17% | 검색 희귀(구역에 100건) | 256 | **2,576 / 1,284** (1g 722) | 547 / 297 (1g 192) |
| 50% | 검색 **없는 키워드** | 445 | **30,983 / 47,085** (1g **2,130**) | **52,803 / 45,696** (1g 1,475) |
| 1% | 목록 첫 페이지 | 10.8 | 6.1 / 7.2 | 0.6 |
| 1% | 검색 흔함 | 19.3 | 76 / 77 | 1.7 |

(ms, 512m A B A B 중앙값, 괄호는 1g. 같은 조건 두 패스의 폭이 2배까지 벌어진다 — 호스트에 다른 세션 컨테이너가 있었다.)

**#118 쪽.** 무릎을 만들던 목록 53ms×9,349블록은 A에서 1.7ms×471블록 — 100만 행에서도 그렇다. 남은 건 k6 사다리 재측정(AWS)뿐이고 이 로컬 판은 DB 쪽 답이다.

**#91 쪽 — 비정규화는 "지금은 안 한다" 추천(4절).** 큰 구역의 목록·흔한 검색은 A·B가 같다(1~3ms). B가 이기는 건 1% 소구역(10~46배지만 7ms·77ms에서)과 17% 구역 희귀 검색(A가 조인 때문에 `user_id` 비트맵을 잃고 전체 Seq Scan으로 떨어진 자리)뿐이다. 이관은 백필 100s + 힙 두 배 + `VACUUM FULL` 1분 잠금.

**새로 안 것 — #127이 검색의 최악 경로를 바꿨다.** 이전엔 키워드가 뭐든 구역 비트맵 한 번(0.25~0.55s)이었는데, 지금은 플래너가 V10을 최신순으로 걸으며 LIKE를 거르는 계획을 고른다. 흔한 키워드는 142행에서 끝나 100배 빠르지만 **구역에 없는(또는 드문) 키워드는 100만 행 끝까지 걷는다** — 1g에서 2.1s, 512m에선 힙이 캐시에 안 들어가 31~47s(#83과 같은 메커니즘, 같은 버퍼 수에 디스크냐 캐시냐). 비정규화도 이걸 구역 비율만큼만 줄인다. 후보는 검색 **기간 상한**(커서와 같은 꼴이라 Index Cond가 된다), `title`만 pg_trgm(D1 재검토 조건), 검색만 비트맵 계획으로 되돌리기. → #154.

**5절 기준으로는** 쿼리 변경(#127) 뒤의 `EXPLAIN (ANALYZE, BUFFERS)` 전후가 이 표다 — 목록·흔한 검색은 [있음], 없는 키워드는 전보다 나빠진 게 [있음].

### 2026-09-19 — 순서 6-2: B1 후속 #83 (AWS 밤 16) — 원인 확정

밤 14와 같은 조건에서 한 번 더, 이번엔 매 분 DB 컨테이너 cgroup(`memory.current`/`max`/`stat` file·anon·shmem/`events` max)을 rate.csv에 적고, 열화가 오면 **재시작 없이 `docker update --memory 1g`**만 했다. 결과 전문: `AWS-MEASUREMENT-RESULTS.md` 밤 16, 산출물 `measure/e8cause-20260919T051340/`.

| 구간 | 원본당 | INSERT/건 | cgroup file 캐시 | events max |
|---|---|---|---|---|
| 0 ~ 62k 청크 | 70~79 | 1.0 → 1.3 | 0 → 471MB(상한) | 0 |
| 62k ~ 177k | 70~80 | 1.3 → 3.0 | 471 고정 | 분당 +250 |
| 177k ~ 203k (열화) | 79 → **107** | 3 → **26** | 471 고정 | 분당 **+1,500** |
| **상한 1G (재시작 없음)** 1분 뒤 | 82 | 8.8 | 705 | — |
| 2분 뒤 ~ 완주 240k | **70~80** | **2.8** | ~965 | 거의 정지 |

**갈린 것.** 프로세스·`shared_buffers`·앱 전부 그대로 두고 cgroup 상한 하나만 바꿔서 돌아왔으니 원인은 상한이다. 밤 14의 "DB 재시작 회복"은 새 cgroup, 밤 3의 "4배에도 불변"은 조건마다 새 컨테이너 = 새 cgroup이었다. #83·#84 닫음. 창 A·B(끝난 DB에 다시 쓸 때, 1G)는 둘 다 기준선과 같다 — REINDEX는 아무것도 안 바꾼다.

**5절 기준으로는** 설정 변경(상한 512M → 1G) 전후를 **같은 프로세스·같은 코퍼스·같은 분**에서 잰 표다. 통제군은 밤 14(같은 자리에서 같은 모양으로 꺾임)와 창 A/B(1G에서 기준선 유지).

**B2를 다시 쓴다** — `shared_buffers`는 그대로, 컨테이너 상한은 "힙 + HNSW + `shared_buffers` + 여유". 운영 `compose.yaml`은 아직 512M — 코퍼스가 ~17만 청크를 넘으면 운영 색인이 같은 자리에서 느려진다. **2026-09-19 사용자 결정: (a) 1G로 올린다** — `compose.yaml` DB `memory: 1G`. 코퍼스 상한(b)은 안 묶는다; 코퍼스가 24만의 두 배가 되면 값을 다시 본다.
