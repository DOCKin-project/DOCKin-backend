# PostgreSQL 책 → DOCKin 적용 실험 패키지 (읽기 축)

- 작성: 2026-09-15
- 짝: ShadowFit `E:\init\docs\portfolio\realmysql-experiments.md` (RealMySQL 8.0 → **쓰기 축**). 이 문서는 그 **읽기 축** 판이다.
- 관계: ADR-0002 3절의 "Real MySQL 매핑"은 PostgreSQL 이관(ADR-0006)으로 **무효**가 됐다(`WORK-BACKLOG.md:150`이 그렇게 적어뒀다). 이 문서가 그 자리를 잇는다.
- 개선안: 이 문서의 카드가 낸 근거로 **무엇을 바꿀지**는 `DB-IMPROVEMENT-PLAN.md`에 있다.
- 표기: 🟢 실측 완료 / 🟡 관찰은 있으나 실험 없음 / ⬜ 미착수. 숫자는 전부 다른 문서에 이미 있는 것이고, 여기서 새로 만든 수치는 없다.

---

## 0. 두 프로젝트, 두 축, 두 책

| | ShadowFit | DOCKin |
|---|---|---|
| DB | MySQL 8.0 (InnoDB) | **PostgreSQL 17** + pgvector (`compose.yaml:145`) |
| 데이터가 무거운 쪽 | **쓰기** — `pose_data` 1억 행 합성 적재, gRPC 배치, 파티션 폐기 | **읽기** — 작업일지 목록·검색(100만 행 벤치), 채팅 따라잡기, RAG 벡터 검색(19만 청크) |
| 책 | RealMySQL 8.0 1·2권 | 아래 1절 |
| 포폴 헤드라인 | 세션 생명주기의 분산 정합성(낙관락·멱등) | **측정이 가설을 뒤집은 기록**(`PORTFOLIO-ROADMAP.md` 0절) |

두 문서가 같은 4단(개념 / 가설 / 설계 / 결과+회고)을 쓰되, **겹치는 실험은 하지 않는다.** 파티셔닝·버퍼풀 hit율·복제는 ShadowFit 것이고, 여기는 실행계획·MVCC 팽창·락·pgvector다. 같은 주제를 두 번 말하지 않게 하려고 ADR-0002가 처음 나눴던 경계를 그대로 지킨다.

---

## 1. 책 목록 — 무엇을 읽고, 무엇은 안 읽나

> 2026-09-15 정정: 처음엔 "디비안에는 PostgreSQL 책이 없다"고 적었는데 **틀렸다.** 서점 검색을 직접 뒤지니 디비안 세 권, 엑셈 두 권이 더 있었고, Rogov 『Internals』는 **한국어판 PDF가 있다.** 아래는 정정 뒤의 목록이다.

### 시중 목록 (2026-09 기준, 예스24 검색)

| 출판사 | 책 | 저자 | 출판 | 쪽 | 성격 |
|---|---|---|---|---|---|
| **엑셈** | **막힘없이 PostgreSQL** | 임경석·김철환·박관규·김규민 | 2025-01 | 397 | 아키텍처·MVCC·락·SQL Execution, 부록 격리수준·팽창·SQL 모니터링. PG14+ |
| 엑셈 | **PostgreSQL Wait Interface** — 내부 동작 메커니즘으로 이해하는 대기 이벤트 | 김민서·이은송 | 2026-03 | 368 | `wait_event` 중심. 목차는 서점 페이지가 비어 있어 못 봤다 |
| 엑셈 | Oracle, PostgreSQL, MySQL Core Architecture 1·2 | 권건우 외 | 2016·2017 | — | 세 DB 아키텍처 비교. ShadowFit(MySQL)과 DOCKin(PG)을 **같은 책에서** 대조할 수 있는 유일한 책 |
| **디비안** | **SQL TUNER for PostgreSQL (기본원리편)** | 유일환 (『SQL BOOSTER』 저자) | 2026-03 | 460 | 실행계획·인덱스·인덱스 스캔·정렬과 집계·조인 내부·**트랜잭션과 모니터링·VACUUM**. 응용편 예고 |
| **디비안** | **PostgreSQL DBA를 위한 Admin 이야기** | 김시연·최두원 | 2025-07 | 416 | 환경구축·오브젝트·아키텍처·파라미터·**복제·백업과 복구·모니터링**·트랜잭션과 대기이벤트·Vacuum. 튜닝은 안 다룬다고 명시 |
| 디비안 | 뚝딱 PostgreSQL 레시피 | 김익서 | 2024-07 | — | 실무 레시피집. 목차 미확인 |
| 디비안 | 친절한 SQL 튜닝 | 조시형 | 2018 | — | **Oracle 기준.** 원리 장만 |
| 유페이퍼 | PostgreSQL 튜닝 기술(상) | 김철민 | 2024-11 | 454 | 옵티마이저·인덱스·조인·흔한 실수·튜닝 기법. 전자책 |
| 부크크 | PostgreSQL 9.6 성능 이야기 | 김시연·최두원 | 2017 | — | 9.6 기준. 1·2장(아키텍처·Shared Buffer) PDF 공개 |
| 부크크 | 인공지능을 위한 PostgreSQL | 김기태 외 | 2025-11 | — | pgvector 쪽으로 보이나 목차 미확인 |
| 엑시엄 | 초보자를 위한 PostgreSQL (DBA편) | 권순용 외 | 2025-12 | — | 목차 미확인 |
| 지앤선 | PostgreSQL 성능 최적화 (Gregory Smith, *PostgreSQL 9.0 High Performance* 역) | 박동식·최원희 역 | 2014 | — | 오래됐지만 **pgbench·체크포인트·WAL 튜닝** 장은 지금도 유효한 고전 |
| 에이콘 | PostgreSQL 9.4 공식 가이드 Vol.1 서버 관리 / Vol.2 SQL 언어 | 비트나인 역 | 2015·2016 | — | 공식 문서 번역. postgresql.kr이 대체 |
| 비제이퍼블릭 | 모두를 위한 PostgreSQL | 정승호 외 | 2021 | — | 입문 |
| 심통 | 현장에서 바로 써먹는 SQL with PostgreSQL | 김임용 | 2024-01 | — | SQL 입문 |
| Postgres Pro | **PostgreSQL 14 Internals** (Egor Rogov) | **한국어판: 윤명식(MyoungSig Youn) 역, PDF 무료** | 2022~ | — | 격리와 MVCC·버퍼 캐시와 WAL·락·실행·인덱스 종류. postgrespro.com/community/books/internals |

### 고르기 — 이 프로젝트가 읽을 순서

| 자리 | 책 | 왜 이 책인가 |
|---|---|---|
| **주교재** (RealMySQL 1권 자리) | **막힘없이 PostgreSQL** (엑셈) | 2절 장별 맵의 기준. 부록 "테이블 팽창 모니터링"이 힙 780MB 사고(5절 #2)를 그대로 다룬다 |
| **튜닝** (RealMySQL 2권 자리) | **SQL TUNER for PostgreSQL 기본원리편** (디비안, 2026-03) | 『튜닝 기술(상)』보다 최신이고, 실행계획·인덱스에 **VACUUM·모니터링 장이 붙어 있어** 2절 Ch.2·Ch.4를 한 권이 덮는다. P2-15-5의 "플래너가 복합 인덱스를 안 고른" 자리를 이 책의 언어로 다시 설명할 수 있어야 한다. 『튜닝 기술(상)』은 대체재 |
| **원리** | **PostgreSQL 14 Internals 한국어판** (무료) | 밤 3의 `shared_buffers` 반증을 설명하려면 버퍼 캐시 장이 필요하다. 한국어판이 있으니 영문을 읽을 이유가 없다 |
| **대기 이벤트** | PostgreSQL Wait Interface (엑셈, 2026-03) | 3절 ③(락 실물)과 5절 #4(13분 44초 `transactionid` 대기)·#8(풀 고갈)의 교재. ShadowFit이 `performance_schema`로 한 일을 PG에서는 `wait_event`로 한다 |
| **DBA 축** | PostgreSQL DBA를 위한 Admin 이야기 (디비안, 2025-07) | 복제·백업과 복구·모니터링. 이 저장소는 백업(D1, `OPERATIONS-BACKUP.md`)까지 했고 **복제는 0건**이다. DBA 지원서에서 비어 보이는 자리(백업·복제·무중단 DDL)가 이 책의 5·6장이다 |
| 부분만 | Core Architecture 1·2 (엑셈) | ShadowFit·DOCKin을 한 책에서 대조하는 면접용 |
| 부분만 | PostgreSQL 성능 최적화 (Gregory Smith 역) | pgbench·체크포인트 장만 — 3절 ①(E8 열화)의 후보에 체크포인트·WAL이 있다 |
| 부분만 | 9.6 성능 이야기 1·2장 PDF, 친절한 SQL 튜닝 원리 장 | — |

### 이 단계에선 안 읽는 것

입문서(모두를 위한 PostgreSQL, 현장에서 바로 써먹는 SQL, 9.4 공식 가이드)는 이 저장소가 지나온 자리다. 『뚝딱 레시피』·『인공지능을 위한 PostgreSQL』·『초보자를 위한 PostgreSQL DBA편』은 **목차를 못 봐서 판단을 보류**한다 — 서점 페이지가 비어 있었다. 공식 한글 문서(postgresql.kr)와 pgvector README는 책이 아니라 참조로 곁에 둔다.

> **디비안·엑셈 두 회사를 놓고 보면** — 둘 다 Oracle에서 출발해 2024~2026년에 PostgreSQL 책을 잇달아 냈다. 디비안은 튜닝(SQL TUNER)·운영(Admin 이야기), 엑셈은 내부 구조(막힘없이)·대기 이벤트(Wait Interface)로 갈린다. RealMySQL 한 권이 하던 일을 PG에서는 이 넷이 나눠 한다.

---

## 2. 장별 적합도 맵 — 『막힘없이 PostgreSQL』 기준

| 장 | 주제 | DOCKin substrate | 이미 잰 것 | 남은 것 | 우선 |
|---|---|---|---|---|---|
| **Ch.1** | 아키텍처 — 프로세스 모델·`shared_buffers`·WAL·체크포인트·`work_mem` | 밤 3 E8 후속, HNSW 빌드, P2-15-5 캐시 통제 | 🟢 `shared_buffers` 8배 ↑ 해도 처리량 불변(밤 3, **가설 반증**) · 🟢 `pg_prewarm('read')`로 두 패스의 캐시 대칭 · 🟢 `maintenance_work_mem` 64MB → HNSW 디스크 빌드 전환 | ⬜ **E8 열화 1.8배의 원인** — 체크포인트·WAL·autovacuum·JVM 넷 중 무엇인가. `scripts/e8-longrun-cause.sh`가 **이미 있고 미실행**이다 | ① |
| **Ch.2** | 트랜잭션·MVCC·**Vacuum**·팽창 | `work_logs` 대량 삭제, `document_chunks` 대량 삽입, `Page<>`의 COUNT | 🟡 20,362행에 힙 780MB · 🟡 0행에 26MB · 🟡 VACUUM truncate 거부 · 🟡 VACUUM 40분 · 🟢 COUNT 4초(MVCC라 전부 세야 한다) | ⬜ **autovacuum 관측 0건** — `n_dead_tup`·`pgstattuple`·`pg_stat_progress_vacuum`을 이 저장소가 읽은 적이 없다(`grep` 결과). 관찰은 다섯인데 실험은 없다 | **②** |
| **Ch.3** | 락 — 행 락·`lock_timeout`·교착 | ADR-0001(출근), 4-1(연차), ADR-0008 5-3(`nextRoomSeq`) | 🟢 `FOR UPDATE` 없으면 3일치 증발(lost update 재현) · 🟢 방 행 락 직렬 23ms/건 → 단일 방 43 msg/s · 🟢 `lock_timeout=0`이면 13분 44초 hang | ⬜ `pg_locks`·`pg_stat_activity.wait_event`를 **실물로 본 기록이 없다**(ShadowFit은 `data_locks`로 GRANTED/WAITING을 봤다) · ⬜ `deadlock_timeout=1s`가 설정돼 있는데 교착을 재현한 적이 없다 | ③ |
| **Ch.4** | SQL Execution — 실행계획·인덱스·조인·페이징 | 작업일지 목록·검색 6쿼리(P2-15-5), FK 인덱스(V3·V4), 채팅 N+1, 색인 커서 | 🟢 FK 인덱스 893배·22배·110.7배 · 🟢 복합 인덱스를 플래너가 **안 고른다**(선택도 16% → Parallel Seq Scan) · 🟢 N+1 41개 → 3개(P2-12-1) · 🟢 OFFSET → keyset(P1-13) · 🟢 권한 `OR` 분리 14%(P1-12) | ⬜ **`pg_trgm`** — "B-tree로 안 변한다"까지만 확인, GIN을 붙여 본 적 없음 · ⬜ 목록 COUNT → `Slice` · ⬜ 목록 OFFSET 500페이지 → keyset · ⬜ `pg_stat_statements` top-N — 확장은 로드돼 있는데(`compose.yaml:160`) **배치 검증에만 쓴다** | ④ |
| **부록** | 격리 수준 | 연차 lost update | 🟢 READ COMMITTED + `FOR UPDATE`만 | ⬜ 같은 시나리오를 REPEATABLE READ로 — PG의 RR은 first-updater-wins라 **락 없이도 두 번째가 실패한다**. 그게 답이 되는지, 재시도 비용이 얼마인지 | ⑤ |
| **부록** | 테이블 팽창 모니터링 | Ch.2와 같다 | — | Ch.2 ②에 흡수 | — |
| **부록** | SQL 모니터링 | `pg_stat_statements` | 🟡 시퀀스 호출 횟수 세기만 | Ch.4 ④에 흡수 | — |
| (책 밖) | pgvector HNSW | ADR-0006 | 🟢 브루트포스 → pgvector 70배, 병목은 클라이언트 전송 81% · 🟢 `iterative_scan` recall · 🟢 코퍼스 19만 무릎 | ADR-0006·0007이 관리한다. 여기 안 둔다 | — |

**읽는 법.** Ch.4가 제일 두껍고 제일 많이 끝나 있다 — 이 저장소가 지금까지 한 DB 작업은 거의 전부 실행계획이었다. 반대로 **Ch.2는 관찰이 다섯인데 실험이 0이다.** 780MB 힙, 26MB 빈 테이블, 거부된 truncate, 40분 VACUUM, 원인 미상 열화 — 전부 같은 장의 이야기인데 한 번도 그 장의 도구로 본 적이 없다. 그래서 다음 실험은 Ch.2다.

---

## 3. 실험 카드

### ⓪ 측정 백본 — `pg_stat_statements` + `pg_stat_user_tables` 🟡

- **개념** (『막힘없이』Ch.4·부록, 『Internals』): 쿼리 다이제스트별 누적 시간, 테이블별 `n_live_tup`/`n_dead_tup`/`last_autovacuum`.
- **현황**: `pg_stat_statements`는 `shared_preload_libraries`에 있고 `CREATE EXTENSION` 실패를 예외로 막아뒀다(`WORK-BACKLOG.md:1962`). 그런데 읽는 곳은 `HibernateBatchInsertVerificationTest` 하나 — 시퀀스 호출 횟수를 셀 때뿐이다.
- **설계**: 벤치 한 판 뒤 `pg_stat_statements` `total_exec_time DESC LIMIT 10`과 `pg_stat_user_tables`를 같은 산출물 폴더에 스냅샷으로 남긴다. ShadowFit이 모든 카드의 증거를 `performance_schema`에서 댔듯 여기는 이 둘이 댄다.
- **결과**: ⬜

### ① Ch.1 — E8 열화의 원인은 DB인가 앱인가 ⬜ (스크립트 있음)

- **개념**: 장시간 쓰기에서 체크포인트·WAL·autovacuum이 쌓이는 것과, 앱 쪽 JVM·커넥션 풀이 열화하는 것은 재시작에 대한 반응이 다르다.
- **가설**: 밤 2의 108.7ms(19만 청크 자리)와 밤 3의 60~62ms는 같은 코퍼스·같은 상한이다. 차이는 "얼마나 오래 연속으로 돌렸나"다.
- **설계**: `scripts/e8-longrun-cause.sh` — 밤 2를 재현하되 중간에 **앱만** 재시작. 돌아오면 앱, 안 돌아오면 DB. 한 시간짜리다(`AWS-MEASUREMENT-RESULTS.md:380`).
- **결과**: ⬜ — 밤 4가 두 번 시도해 두 번 다 데이터를 못 냈다(`:493`). 실험이 아니라 rig의 문제였고, 그 뒤로 안 돌렸다.

### ② Ch.2 — 팽창을 세고, autovacuum이 언제 왔는지 본다 ⬜ (다음 실험)

- **개념**: MVCC는 UPDATE/DELETE를 "새 버전 추가"로 처리한다. 죽은 튜플은 autovacuum이 회수하고, 파일 끝의 빈 페이지만 truncate로 돌려준다. truncate에는 `ACCESS EXCLUSIVE`가 필요하다.
- **가설(이미 관찰된 것을 실험으로 바꾼다)**:
  - (a) 98만 행 DELETE 직후 `work_logs`는 `n_dead_tup ≈ 98만`이고 `pgstattuple.dead_tuple_percent`가 90%를 넘는다.
  - (b) autovacuum은 `50 + 0.2 × reltuples` 기본 임계라 98만 삭제 뒤엔 바로 깨어난다. 문제는 오는가가 아니라 **얼마나 걸리는가**다 — 기본 `autovacuum_vacuum_cost_delay=2ms`·`cost_limit=200`은 I/O를 일부러 늦추는 값이라, 780MB 힙을 훑는 데 수동 `VACUUM`(지연 0)보다 몇 배 오래 걸릴 것이다. 512M 컨테이너에서 그 몇 배가 얼마인지가 이 실험의 숫자다.
  - (c) ~~0행 26MB(`work_log_images`)는 autovacuum이 왔어도 truncate가 안 됐을 수 있다 — 벤치 트랜잭션이 잠금을 쥐고 있었다.~~ → **고쳐 묻는다** (2026-09-15, `DB-IMPROVEMENT-PLAN.md` 6절): VACUUM은 파일 끝의 빈 페이지만, 그것도 1,000페이지/1/16 이상일 때만 잘라낸다. 780MB 힙은 **앞쪽에 구멍이 난** 경우라 autovacuum이 왔어도 안 줄었을 것이다. 물을 것은 "truncate가 됐나"가 아니라 **"죽은 공간이 어디에 있나"**(`pgstattuple_approx.free_pct`)다.
- **설계**: 100만 벤치 시드 → 98만 DELETE → 30초 간격으로 `pg_stat_user_tables`(n_dead_tup, last_autovacuum, autovacuum_count)·`pg_stat_progress_vacuum`·`pg_relation_size` 스냅샷. 조건 둘: `autovacuum_vacuum_cost_delay` 2ms(기본) vs 0. 통제군: 같은 조건을 두 번(밤 3의 D=A 반복 방식).
- **지표**: 회수 완료까지 시간, 패스 수(`index_vacuum_count`), 힙 크기가 실제로 줄었는가(truncate 여부).
- **왜 이게 다음인가**: 이 저장소에서 가장 큰 "고친 결과가 다음 병목을 드러낸" 사례(`WORK-BACKLOG.md:1417`)가 팽창인데, 팽창을 잰 도구가 `pg_relation_size` 하나였다. 부록 "테이블 팽창 모니터링"이 이 실험의 교재다.
- **결과**: ⬜ — 관측 도구(A1 `scripts/db/bloat-snapshot.sh`)와 절차(A3 `docs/db/after-bulk-delete.sql`)는 2026-09-15에 준비됐다. 실험 자체는 아직이다.

### ③ Ch.3 — 락을 실물로 본다 ⬜

- **개념**: `pg_locks`의 `granted`, `pg_stat_activity`의 `wait_event_type='Lock'`, `pg_blocking_pids()`.
- **가설**: `LeaveBalanceConcurrencyTest`의 두 번째 트랜잭션은 `FOR UPDATE` 대기 중 `wait_event='transactionid'`로 보인다 — 13분 44초 hang 때 `pg_stat_activity`에서 본 그것이다. `nextRoomSeq`도 같은 모양이다.
- **설계**: 기존 두 테스트에 관측 스레드 하나를 붙여 대기 중 `pg_locks`/`pg_stat_activity`를 찍어 산출물로 남긴다. 추가로 `deadlock_timeout=1s`가 실제로 동작하는지 — 두 트랜잭션이 서로 반대 순서로 `FOR UPDATE`를 잡는 테스트 하나.
- **결과**: ⬜

### ④ Ch.4 — 남은 세 개: `pg_trgm`, `Slice`, keyset ⬜

- **`pg_trgm`**: P2-15-5 ⑤는 "B-tree로 안 변한다"의 증거이지 GIN이 얼마나 돕는지의 증거가 아니다. 같은 100만 조건에서 `gin_trgm_ops` 인덱스 전후. 인덱스 크기와 쓰기 비용도 같이 — 작업일지는 쓰기도 있다.
- **`Slice`**: ② COUNT 12.8ms(인덱스 후)를 0으로. 화면이 전체 페이지 수를 쓰는지부터 확인.
- **keyset**: ③ 500페이지 150ms → `(created_at, log_id)` 커서. 색인은 이미 했고(P1-13) 목록은 안 했다.
- **결과**: ⬜

### ⑤ 부록 — 격리 수준 ⬜

- **가설**: 연차 시나리오를 `REPEATABLE READ`로 돌리면 `FOR UPDATE` 없이도 두 번째 승인이 `could not serialize access`로 실패한다. 즉 lost update는 막히지만 재시도가 필요해진다 — ADR-0001 4-1이 낙관락을 거절한 이유(재시도 복잡도)가 그대로 돌아온다.
- **설계**: `LeaveBalanceConcurrencyTest`에 조건 하나 추가. 결과가 "막힌다"면 ADR-0001 4-1의 대안 표에 한 줄이 는다.
- **결과**: ⬜

---

## 4. ShadowFit 카드와의 대응 — 겹치지 않게

| ShadowFit (RealMySQL) | DOCKin (이 문서) | 관계 |
|---|---|---|
| ⓪ Performance Schema | ⓪ `pg_stat_statements` + `pg_stat_user_tables` | 같은 역할, 다른 도구 |
| ① 인덱스 & EXPLAIN — "이미 최적임을 발견", 9,000배 대조 | Ch.4 — "플래너가 인덱스를 **안 고른** 것이 맞았다"(선택도 16%) | **반대 방향의 같은 교훈.** 한쪽은 인덱스가 있어서, 한쪽은 안 써서 맞았다 |
| ② 파티션 DROP 1.8초 vs DELETE 18.6분 | (안 함) — 대신 FK 인덱스로 DELETE 24분 → 49초 | 같은 "대량 삭제" 문제를 **다른 축**으로 풀었다. 면접에서 나란히 쓸 수 있다 |
| ④ 버퍼풀 — hit율 공식의 함정(read-ahead) | Ch.1 — `shared_buffers` 8배 ↑ 해도 불변(반증) | 둘 다 "크기 계산이 맞아도 원인이 아니다" |
| ③ 락 — `data_locks` GRANTED/WAITING | Ch.3 — ⬜ | **DOCKin이 비어 있는 자리** |
| (없음) | Ch.2 — 팽창·autovacuum | **MySQL엔 없는 주제.** PostgreSQL 축의 고유 실험이라 우선순위가 높다 |

---

## 5. 장애 기록

이 저장소에서 실제로 일어난 사고를 모았다. "무엇이 났나"보다 **"어느 방어선이 왜 통과됐나"**와 **"책의 어느 장이 이걸 미리 말해 주는가"**를 적는다. 숫자와 출처는 전부 기존 문서다.

| # | 언제 | 무엇이 났나 | 원인 | 조치 | 상태 | 책 |
|---|---|---|---|---|---|---|
| 1 | 2026-08-08 | 벤치 98만 행 `DELETE`가 **24분을 넘겨도 안 끝났다.** 4일 뒤 979,984행이 그대로 남아 있는 것이 발견됐다 | 자식 테이블 셋(`log_images`·`work_log_comments`·`work_log_images`)의 FK 컬럼에 인덱스가 없어 부모 행마다 자식을 **순차 스캔** | V3 FK 인덱스. 같은 문장이 **49초** | 해소 (`fa16373`) | 막힘없이 Ch.4 · SQL TUNER 인덱스 |
| 2 | 2026-08-08 | 사용자 500명 `DELETE`가 **5분** | 위와 같은 결손(`users` FK 9개 중 8개 무인덱스) **×** `work_logs` 힙이 20,362행에 **780MB**(방금 지운 98만 행의 공간 미회수). 두 원인이 곱해졌다 | V4 FK 인덱스 8개. 13.6초 → 0.12초(**110.7배**) | 해소 (`df3e737`) — **팽창 쪽은 손 안 댐** | 막힘없이 Ch.2+Ch.4 |
| 3 | 2026-08-08 | `work_log_images`가 **0행인데 26MB.** `VACUUM`으로 잘라내려 하자 **truncate 거부** | 죽은 튜플 33만 건 + 실행 중인 삭제 트랜잭션이 잠금을 쥐고 있어 `ACCESS EXCLUSIVE`를 못 얻음 | 없음 — 삭제가 끝난 뒤 자연 회수 | **관찰만** → 3절 ② | 막힘없이 Ch.2 · 부록 팽창 |
| 4 | 2026-08-07 | `LockTimeoutVerificationTest`가 **실패가 아니라 멈췄다.** 10분 타임아웃 뒤 `pg_stat_activity`에 **13분 44초째 `transactionid` 대기** | Testcontainers에 `compose.yaml`의 서버 인자를 안 넘겨 `lock_timeout=0`(무한) | `withCommand("-c lock_timeout=5s")` → 7.7초 통과 | 해소 (`1bc16cb`) | 막힘없이 Ch.3 · Wait Interface |
| 5 | 2026-08-13 밤 3 | `VACUUM (ANALYZE)` 하나가 **40분+**. 첫 시도가 여기서 죽었다 | 19만 청크 HNSW 인덱스를 `maintenance_work_mem` 64MB / 컨테이너 512M로 통째로 훑음 | `INDEX_CLEANUP OFF`로 우회. **대가**: 죽은 인덱스 항목이 조건을 넘어 쌓임 → 통제군 D(=A 반복)로 검증 | 우회 | 막힘없이 Ch.2 · pgvector |
| 6 | 2026-08-13 밤 2 | 3시간 연속 색인에서 처리량 **1.8배 열화**(60 → 108.7ms/원본). 밤 3이 "코퍼스 크기 때문"을 반증 | **미확정** — 체크포인트·WAL·autovacuum·JVM 넷 중 하나 | `e8-longrun-cause.sh` 작성. 밤 4가 두 번 돌려 두 번 다 데이터 못 냄 | **미해결** → 3절 ① | 막힘없이 Ch.1·Ch.2 · Internals · Smith 체크포인트 장 |
| 7 | 2026-08 (ADR-0006) | HNSW 빌드가 10만 건 중 28,363건에서 **디스크 빌드로 전환**(4분 56초). 병렬 빌드는 **실패** | `maintenance_work_mem` 64MB 초과 / Docker `/dev/shm` 64MB에 DSM이 안 들어감 | 256MB + 병렬 끔 → 3분 09초. `shm_size`는 OOM 위험으로 택하지 않음. 절차를 `docs/db/rebuild-hnsw-index.sql`로 | 해소 | 막힘없이 Ch.1 |
| 8 | 2026-09-14 (M1) | 채팅 수신 지연 **p50 726~2,764ms**, 실행마다 4배씩 흔들림 | `@Async`에 실행기 이름이 없어 `SimpleAsyncTaskExecutor`가 **저장 631건에 스레드 631개**. 각자 `@Transactional`로 커넥션을 잡아 **풀 10개 고갈** → 인바운드 스레드가 커넥션을 기다린 시간이 곧 지연 | D1(저장을 커밋 뒤로, `@Async` 제거). 풀 100으로 늘리면 22ms가 되는 것이 진단의 증거 | 해소 (`5e0ae99`) — "풀을 늘리는 것"이 답이 아닌 이유는 Ch.1 프로세스 모델(커넥션 = 백엔드 프로세스) | 막힘없이 Ch.1 |
| 9 | 2026-08-12 밤 1 | 색인기 둘이 같은 행을 집어 `duplicate key "uk_chunk_source"`. 무인 rig가 이 로그를 **완주로 오판**하고 인스턴스를 껐다 — 13%에서 밤이 끝남 | 앱 기동(02:59:57) 3초 뒤 cron(03:00:00). 해시 멱등은 두 트랜잭션이 동시에 "없다"를 읽는 것을 못 막는다 | 유니크 제약이 최후 방어선 역할을 했다(ADR-0001과 같은 구조). 동시 실행 자체는 **미수정** | **부분** | 막힘없이 Ch.2·Ch.3 |
| 10 | 2026-08-08 | 100만 "인덱스 있음" 열이 **0.6~0.7배로 역전.** 인덱스와 무관한 ⑤까지 같이 느려졌다 | 없음 → 있음을 연달아 재서 **캐시 조건이 체계적으로 달랐다.** 힙 780MB > `shared_buffers` 128MB | 각 패스 앞 `pg_prewarm(rel, 'read')`. 통제군 ⑥이 1.0배로 복귀 | 해소 (2026-08-12) | 막힘없이 Ch.1 · Internals 버퍼 캐시 |
| 11 | 2026-08-05 | 작업일지를 **JPA로 읽는 모든 경로**가 `Bad value for type long`. 테스트 100여 개·`validate`·`SchemaValidationTest` **셋 다 통과** | `@Lob` → PgJDBC가 CLOB을 Large Object OID로 취급. 컬럼은 `text`. 세 방어선이 모두 **"행이 없어서"** 통과 | `@Lob` 제거 + 시드 데이터 | 해소 (`f792058`) | 책 밖 (드라이버) |
| 12 | 2026-08 이전 | `ddl-auto=update`가 DDL 실패를 **로그만 남기고 기동**. 테이블 없이 정상처럼 보였다 | Hibernate `update`의 설계 | `validate` + Flyway | 해소 | 책 밖 (운영 절차) |
| 13 | 2026-08-07 | `pg_stat_statements`가 없어도 배치 검증 테스트가 **초록불** | 시퀀스 호출 횟수를 못 세면 0을 받고 넘어감 | `CREATE EXTENSION` 실패를 예외로 | 해소 | 막힘없이 부록 SQL 모니터링 |

**이 표에서 읽을 것 셋.**

1. **13건 중 팽창(Ch.2)이 직접·간접으로 걸린 것이 #2·#3·#5·#6·#10 다섯.** 그런데 이 중 팽창 자체를 고친 것은 없다 — 전부 우회했거나 옆 원인을 고쳤다. 3절 ②의 근거다.
2. **"방어선이 셋인데 셋 다 통과"(#11)와 "판정 문자열 하나로 완주 오판"(#9)은 같은 종류다.** 검증이 실제 행·실제 완료를 보지 않았다. 책이 아니라 rig 설계의 문제고, ShadowFit의 "버림판 + 라틴 방격" 교훈과 같은 자리다.
3. **#8은 DB 사고처럼 보이는 앱 사고다.** 풀 10이 고갈된 증상만 보면 "풀을 100으로"가 답 같지만, PostgreSQL은 커넥션마다 백엔드 프로세스를 띄우므로(『막힘없이』Ch.1) 그 답은 DB 쪽 비용으로 돌아온다. 실제 답은 스레드 631개를 만든 `@Async`였다.

---

## 6. 다음에 할 것 (순서)

| 순서 | 무엇 | 왜 이 순서인가 | 준비물 |
|---|---|---|---|
| 1 | **3절 ② 팽창·autovacuum** | 관찰 5건·실험 0건. PostgreSQL 축의 고유 주제라 ShadowFit과 안 겹친다. 로컬에서 된다 | 100만 벤치 시드(있음), `pgstattuple` 확장 |
| 2 | 3절 ① E8 열화 원인 | 스크립트가 있다. 한 시간(밤 3이 30분에 약 $0.6이었다) | AWS 한 시간 |
| 3 | 3절 ⓪ 백본 | 1·2를 하면서 스냅샷 습관을 같이 만든다 | — |
| 4 | 3절 ③ 락 실물 | 기존 테스트에 관측만 붙인다 | — |
| 5 | 3절 ④·⑤ | 각각 반나절 | — |

읽기 순서는 『막힘없이』 2장 → 부록 "테이블 팽창 모니터링" → 『SQL TUNER』 10장(VACUUM) → 『Internals』 버퍼 캐시·WAL → 『막힘없이』 1장·3장 → 『Wait Interface』 → 『SQL TUNER』 나머지. 실험 1·2가 요구하는 장부터다.
