# 작업 백로그 (우선순위 순)

- 기준일: 2026-08-04
- 작성 목적: ADR이 다섯 개 쌓였으나 구현까지 간 것은 ADR-0001과 ADR-0002 2-1뿐이다. **"설계는 많은데 완주가 적다"** 는 상태를 벗어나는 것이 이 백로그의 목표다. 새 도메인을 늘리는 항목은 의도적으로 뒤로 뺐다.
- 전제: `docs/SERVICE-SCALE-ASSUMPTIONS.md`의 규모 가정을 공유한다.

---

## P0 — 코드 위생 (반나절, 최우선)

새 기능보다 먼저 한다. 저장소를 처음 여는 사람 눈에 가장 먼저 띄는 것들이고, 작업량 대비 효과가 가장 크다.

> **중요 — `schema.sql`은 실행되지 않는다.** `application.properties`가 `spring.sql.init.mode=never`,
> `spring.jpa.hibernate.ddl-auto=update`이므로 테이블은 Hibernate가 **JPA 엔티티에서** 생성한다.
> 즉 **소스 오브 트루스는 엔티티이고 `schema.sql`은 참조 문서**다. 아래 P0-1이 지금까지 드러나지 않은 이유이기도 하다.
> 스키마를 바꿀 때는 **엔티티와 `schema.sql` 양쪽을 함께** 고쳐야 한다.

| # | 항목 | 근거 |
|---|---|---|
| ~~P0-1~~ | ~~`schema.sql`의 `work_log_translations`에 `translated_title` 컬럼이 2회 선언됨~~ | **완료** — 중복 제거. 수동 실행 시 `Duplicate column name`으로 실패하던 상태였다 |
| ~~P0-2~~ | ~~`ChatHistory`와 `ChatLog` 두 엔티티가 같은 테이블(`chat_history`)에 매핑됨~~ | **완료** — `ChatHistory`는 참조하는 코드가 한 곳도 없는 사용하지 않는 엔티티였다. 삭제했다 |
| P0-3 | 클래스명 자바 컨벤션 위반 — `fastApiService`, `onlineTranslateDomain` | 파일을 여는 즉시 보인다. `FastApiService`, `OnlineTranslateDomain`으로 변경 |
| P0-4 | `Work_logs` / `Work_logsRepository` 스네이크 케이스 엔티티명 | P0-3과 함께. 변경 범위가 넓으므로 별도 커밋 |
| P0-5 | `Work_logsRepository.logId(Long logId)` 죽은 메서드 | 의미 불명. 제거 |

---

## P1 — RAG Phase 1 (자소서 근거 확보)

자기소개서에 "RAG와 벡터 검색으로 정확한 근거를 찾아 주는 기능을 직접 구현"이라고 **과거형으로** 기재되어 있다. 실제로 동작하는 코드가 있어야 한다. 설계 근거는 `docs/SERVICE-SCALE-ASSUMPTIONS.md` 4절.

| # | 항목 | 상태 |
|---|---|---|
| P1-1 | `compose.yaml`에 임베딩 컨테이너(TEI, `multilingual-e5-small`) 추가 | **완료** |
| P1-2 | 임베딩 서버 실측 — 차원 / 단건 지연 / 배치 처리량 | **완료** (384차원 / 배치 12ms per doc. `SERVICE-SCALE-ASSUMPTIONS.md` 6절) |
| P1-3 | `document_chunks` 테이블 설계 — 엔티티 + `schema.sql` | **완료** — `rag/model/{DocumentChunk, SourceType, Visibility}.java`. 멱등 재색인용 `content_hash`, 권한 선필터용 `visibility`/`owner_user_id` 비정규화, 모델 교체 추적용 `embedding_model` 포함 |
| P1-4 | `EmbeddingClient` — WebClient로 `/embed` 호출. e5 계열 **`query:` / `passage:` 프리픽스 규칙 준수** | **완료** — 프리픽스를 호출자가 다루지 않도록 `embedQuery` / `embedPassages`로 메서드를 분리해 강제. float↔byte 변환(리틀엔디언) 포함 |
| P1-5 | `IndexingService` — 청킹 → 임베딩 → 저장. `@Scheduled` 배치. **중단 후 재시작 가능(멱등)하게** 설계 | **완료** — `IndexingService`(페이지 순회) + `ChunkIndexWriter`(트랜잭션 경계)로 분리 |

**P1-4 / P1-5에서 잡은 함정 세 가지** (면접에서 설명 가능한 지점)

1. **자기호출로 인한 `@Transactional` 무효화** — 페이지 단위 커밋이 재시작 가능성의 핵심인데,
   같은 클래스 안에서 호출하면 프록시를 거치지 않아 트랜잭션이 걸리지 않는다. **에러도 나지 않는다.**
   `ChunkIndexWriter`를 별도 빈으로 분리해 트랜잭션 경계를 클래스 경계와 일치시켰다.
2. **배치에는 OSIV가 없다** — `workLog.getMember()`가 지연 로딩이라 트랜잭션 밖에서 접근하면
   `LazyInitializationException`이 난다. 트랜잭션 안이었어도 작업일지마다 쿼리가 더 나가 N+1이 된다.
   `findAllForIndexing()`에 페치 조인을 추가해 둘 다 해결.
3. **e5 프리픽스 누락은 조용히 품질만 떨어뜨린다** — 에러가 나지 않아 추적이 어렵다.
   호출자가 프리픽스를 직접 다루지 못하게 API를 나눴다.

> **미포함:** `work_log_translations`(교차언어 검색용)는 아직 색인하지 않는다.
> `TranslateLog` 엔티티가 `translate_logs` 테이블에 매핑되어 있어 `schema.sql`의
> `work_log_translations`와 어긋나 있다. **이 불일치 정리가 교차언어 검색의 선결 과제다** (신규 P0-6).
| P1-6 | `RetrievalService` — 코사인 유사도 top-k (브루트포스 = 정확 최근접) | **완료** — 원본 문서당 최대 2청크 제한 포함 |
| P1-7 | 권한 필터 — 검색 대상 제한 | **완료** — **선필터**로 구현(아래) |

**P1-7은 over-fetch 후 필터링이 아니라 선필터로 구현했다.** 후필터는 요청한 k보다 적게 반환되어
"내가 못 보는 문서가 존재한다"는 사실이 유출되고, 검색어를 바꿔가며 반복하면 문서의 존재 윤곽을
그릴 수 있다. 선필터는 SQL 단계에서 후보에 아예 넣지 않으므로 존재 여부도 새지 않고, over-fetch도 불필요하다.

**메모리 관련 설계 하나** — 브루트포스는 후보 전체를 메모리에 올린다. 엔티티를 통째로 읽으면
청크당 벡터 1.5KB + 본문 약 1.5KB로 10만 청크에서 300MB에 달해 `Xmx400M`에서 위험하다.
`ChunkVector` 투영으로 **벡터만 읽어 점수를 매기고, 본문은 최종 top-k에 대해서만 조회**한다.
| P1-8 | 챗봇에 검색 결과 주입 + 출처를 `chat_history`에 기록 | **완료** — `RagChatService` |
| P1-9 | 임베딩 서버 장애 시 키워드 검색으로 폴백 | **완료** — `RetrievalService.retrieve()` |

**P1-8 / P1-9 설계 판단**

- **팀원 FastAPI 계약을 바꾸지 않았다.** 근거를 별도 필드로 보내려면 `/api/chatbot` 스펙을 고쳐야 하고
  그건 담당 범위 밖이다. 대신 **user 메시지 `content` 안에 근거를 녹여서** 보낸다.
- **프롬프트에 "자료에 없으면 모른다고 답하라"를 명시했다.** 이 문장이 없으면 모델이 근거를 무시하고
  자체 지식으로 답해버려 근거를 붙인 의미가 사라진다.
- **폴백도 같은 권한 선필터를 쓴다.** 기존 `Work_logsRepository.searchWorkLogs`는 소유자 필터가 없어
  그대로 폴백에 쓰면 **장애 상황에서만 남의 작업일지가 새어 나간다.** 같은 `document_chunks`에
  LIKE를 걸어 벡터 경로와 권한 모델을 공유시켰다.
- **폴백은 조용히 일어나지 않는다.** `chat_history.retrieval_mode`에 `VECTOR`/`KEYWORD`/`NONE`을 남겨
  "요즘 답변이 이상하다"가 아니라 "언제부터 폴백이었다"로 추적할 수 있게 했다.
| P1-10 | 1만 / 10만 청크 합성 적재 후 검색 지연시간 실측 | **완료** — `BruteForceSearchBenchmarkTest`. 결과는 `SERVICE-SCALE-ASSUMPTIONS.md` 6-2 |

**P1-10 핵심 결과** — 10만 청크 관리자 경로 **5,165ms**. 챗봇에 얹기 어려운 수준이며 Phase 2 전환 근거가 확보됐다.
그리고 **진짜 병목은 DB 내부가 아니라 클라이언트 전송(81%)** 이라는 것이 확인됐다
(서버 실행 925ms vs JDBC 왕복 4,882ms). 쿼리 튜닝으로는 건드릴 수 없는 구간이며,
유사도 계산을 DB 안으로 옮겨야 한다.

| # | 파생 작업 | 상태 |
|---|---|---|
| P1-12 | 권한 조건 `OR` → 두 조회 분리 (`EXPLAIN`으로 인덱스 미사용 확인 후) | **완료** — 770 → 659ms(14%). 인덱스는 탔으나 개선폭이 작은 이유까지 기록 |
| P1-13 | 인덱싱 순회 OFFSET → 커서(keyset) | **완료** — 성능뿐 아니라 **행 중복·누락** 문제도 함께 해소 |
| P1-14 | JDBC 배치 INSERT 적용 여부 검증 | **완료 — 미적용으로 확인.** IDENTITY 전략이 배치를 막는다. `HibernateBatchInsertVerificationTest` |

> **P1-14는 "고치지 않기로" 결정한 항목이다.** JdbcTemplate 벌크 INSERT로 우회 가능하지만,
> PostgreSQL 이전 후 `GenerationType.SEQUENCE`로 근본 해결되므로 임시 부채를 만들지 않는다.
> 검증 테스트를 남겨 **이전 후 같은 방법으로 배치가 켜졌는지 확인**한다.

**Phase 1에 넣지 않는 것** (인지하고 있으나 의도적으로 제외 — 근거는 ADR에 남긴다)

- 리랭커(cross-encoder) — i3-6100에서 지연시간 부담. Phase 1 실측 후 판단
- 하이브리드 검색(FULLTEXT + 벡터) — ADR-0003 3-1과 합류하는 Phase 3 항목
- 질의 재작성, multi-hop, agentic RAG
- **Redis 캐시 — 챗봇 0.02 TPS에서 캐시 히트가 나지 않는다.** ADR-0002 2-4의 판단을 유지한다
- 규정 문서(PDF/HWP) 적재 — 오프라인 파싱 단계가 추가되므로 Phase 1.5로 분리.
  **2026-08-04 보류.** 파싱(Python)·조항 단위 청킹·시행일 관리가 한 덩어리로 딸려와 작업 단위가 크다

---

### P1-11 — Phase 2 전환 판단 (실측 이후)

**Phase 1 완주 + 브루트포스 한계 실측 전에는 착수하지 않는다.** 근거 없이 벡터DB를 도입하면
ADR-0002가 스스로 경계한 "실측 없이 튜닝했다" 패턴이 된다.

실측 후 선택지는 둘이며, **현재로서는 PostgreSQL 전면 이전이 유력하다.**

| 선택지 | 장점 | 단점 |
|---|---|---|
| pgvector **사이드카** (MySQL 원본 + PG 벡터) | 기존 스택 유지 | **두 저장소 정합성 문제를 새로 만든다** — 동기화 지연, 실패 시 재색인, 장애 폴백 (ADR-0003 3-4) |
| PostgreSQL **전면 이전** | 원본과 벡터가 한 트랜잭션 → **정합성 문제 자체가 소멸**. HNSW 인덱스 | 스택 변경. Real MySQL 매핑(ADR-0002 3절) 무의미해짐, ngram FULLTEXT 계획은 pg_trgm/tsvector로 대체 |

> **이전 비용은 당초 예상보다 훨씬 작다.** `spring.sql.init.mode=never`라 `schema.sql`은 실행되지 않고
> Hibernate가 엔티티에서 방언에 맞는 DDL을 생성한다. JPQL은 방언 독립이다.
> 실제로 손볼 지점은 **`DocumentChunk.embedding`의 `columnDefinition = "VARBINARY(4096)"` → `BYTEA`** 한 줄이다.
> (MySQL에서 VARBINARY를 고른 것은 브루트포스 전체 스캔에서 오프페이지 저장을 피하기 위한 의도적 선택이므로 Phase 1에서는 그대로 둔다.)

#### 이전 시 함께 검증할 항목 — **2a 완료, 실측으로 채움**

| 항목 | MySQL 실측 | PostgreSQL 실측 | 예상과의 차이 |
|---|---|---|---|
| 배치 INSERT | ❌ `IDENTITY`가 막음 (문장 10,000개) | ✅ `SEQUENCE`로 해결. `nextval` **200회** (`increment_by=50`) | 예상대로 |
| 권한 `OR` 인덱스 | ❌ 미사용, 10만 행 전부 읽음 (770ms) | ✅ **BitmapOr 사용** (41.5ms). 선필터 경로 1,485ms → **424ms** | 예상대로 |
| 락 타임아웃 조정 | 서버/세션 단위까지 | ✅ `lock_timeout` 파라미터. **`SET LOCAL`로 트랜잭션 단위 가능** | 예상대로 |
| 락 타임아웃 힌트 | ❌ 무시 (50,850ms) | 미검증 — 서버 파라미터로 해결해 확인 불필요 | — |
| 벡터 컬럼 | `VARBINARY(4096)` | `BYTEA` (2b에서 pgvector `vector`로) | 애노테이션 한 줄 |

**예상 밖이었던 것** — `SafetyCourse.createdAt`의 `columnDefinition = "DATETIME"`이 MySQL 전용 타입이라
`type "datetime" does not exist`로 **테이블 생성이 실패했다.** 그런데 `ddl-auto=update`가 DDL 오류를
로그만 남기고 기동을 막지 않아 **앱이 정상 기동한 것처럼 보였다.** 전수 조사로 잡았다.

> **`OR` 분리는 되돌리지 않는다.** BitmapOr가 확인됐지만 단일 쿼리 41.5ms 대 분리 18.5ms로
> 분리가 여전히 조금 빠르다(힙 접근량은 동일, 차이는 BitmapOr 오버헤드).
> 코드 단순화와 성능을 맞바꾸는 셈이라 현 구조를 유지한다.

#### 2b — pgvector 도입 (남은 작업)

**이관의 본래 목적이다.** 브루트포스는 여전히 벡터를 애플리케이션으로 전부 가져와 계산한다
(10만 청크 관리자 경로 4,059ms). 유사도 계산을 DB 안으로 옮겨야 6-3의 전송 병목이 사라진다.

| 작업 | 난이도 | 상태 |
|---|---|---|
| `vector` 타입 매핑 | ~~높음~~ **낮았음** | **완료** (아래) |
| HNSW 인덱스 생성 | 보통 | **완료** — `docs/migration/2b-hnsw-index.sql` |
| `RetrievalService`의 코사인 계산을 DB 쿼리로 이동 | 높음 | **다음** — 아래 이유로 우선순위 1위 |
| **ANN recall 측정** — Phase 1 브루트포스 결과를 정답 기준선으로 비교 | 보통 | 실데이터 색인 후 |
| 권한 선필터와 ANN 결합 (알려진 난제 — ADR-0006 8절) | 높음 | **실측으로 재현됨** (아래) |

**인덱스보다 계산 위치를 옮기는 것이 먼저다 — 실측이 순서를 바꿨다.**
10만 청크 top-5 기준(ADR-0006 8-2):

| 경로 | 지연 |
|---|---|
| Phase 1 — 애플리케이션에서 계산 | 4,059 ms |
| **인덱스 없이** DB 안에서 정확 최근접 | **약 57 ms** |
| HNSW (워밍) | 약 1.7 ms |

인덱스를 하나도 안 만든 상태에서 이미 **약 70배**다. 전송이 병목의 81%라던 진단이 확인된 셈이고,
HNSW의 추가 이득은 그 위에 얹히는 두 번째 층이다. 그래서 다음 작업은 인덱스 튜닝이 아니라
`RetrievalService`를 `ORDER BY embedding <=> :q`로 옮기는 것이다.

**권한 선필터를 걸면 HNSW가 무너진다.** 일반 사용자 경로(후보의 약 10%)에서 top-5 요청 시:

| 방식 | 반환 | 지연 |
|---|---|---|
| HNSW 기본 | **1 / 5** | 1.6~2.3 ms |
| HNSW + `iterative_scan` | 5 / 5 | 3.9~5.4 ms |
| 정확 최근접 + 선필터 | 5 / 5 | 15.2~22.6 ms |

기밀성이 아니라 **완전성**이 깨진다(필터는 여전히 SQL 안에서 걸린다). 그리고 필터가 붙으면
HNSW의 우위가 32배에서 약 4배로 줄어든다 — **일반 사용자 경로에서는 인덱스를 안 쓰는 게
나을 수도 있다.** 실데이터 recall 측정 전에는 결론 내지 않는다.

| 빌드에서 걸린 것 | 내용 |
|---|---|
| `maintenance_work_mem` 64MB | 10만 건 중 **28,363건**에서 넘쳐 디스크 빌드로 전환(NOTICE로 알려준다). 4분 56초 |
| 256MB + 병렬 끔 | **3분 09초** |
| 256MB + 병렬 켬 | **실패** — Docker `/dev/shm` 기본 64MB에 DSM이 안 들어감. `shm_size`를 키우는 대안은 512MB 컨테이너 OOM 위험 때문에 택하지 않음 |
| 인덱스 크기 | **195MB** — 테이블(195MB)과 맞먹는다. `shared_buffers` 128MB에 둘 다 상주 불가 |

> **잊으면 조용히 잘못되는 것이 둘로 늘었다.** `CREATE EXTENSION`과 HNSW 인덱스 모두
> Hibernate 관리 밖이고 `db/init/`으로도 해결되지 않는다(컨테이너 최초 기동 시점에는
> 테이블이 아직 없다). 새 환경 구축 절차에 `docs/migration/` 실행이 반드시 들어가야 한다.

**타입 매핑 — "커스텀 `UserType`이 필요하다"는 전제가 틀렸다.**
Hibernate 6.4부터 `hibernate-vector` 모듈이 `SqlTypes.VECTOR`를 제공하고 PostgreSQL 방언에서
pgvector의 `vector`로 내보낸다. 현재 Hibernate 7.2.0.Final이라 애노테이션 두 줄로 끝났다.
버전도 Spring Boot BOM이 관리해 명시할 필요가 없다.

```java
@JdbcTypeCode(SqlTypes.VECTOR)
@Array(length = EMBEDDING_DIM)   // 이 값이 곧 컬럼의 vector(384)
private float[] embedding;
```

| 발견 | 내용 |
|---|---|
| **차원이 스키마가 됐다** | HNSW는 고정 차원 컬럼에만 걸린다. 그 대가로 차원이 다른 모델의 청크가 공존할 수 없다 — 가변 길이 `BYTEA` 시절에는 가능했던 일이다. 모델을 바꾸면 컬럼 마이그레이션이 따라온다 |
| **`ALTER`에 `USING`이 필요하다** | `bytea → vector` 자동 변환 규칙이 없어 PostgreSQL이 거부한다. **행이 0건이어도** DDL 시점에 검사한다. `USING NULL::vector(384)`로 명시했고, 행이 남아 있으면 `NOT NULL` 위반으로 실패해 실수로 임베딩을 날리지 못한다 |
| **확장 등록은 이미지와 별개** | `pgvector/pgvector:pg17`에 확장 파일은 있으나 `CREATE EXTENSION`은 따로 실행해야 한다. `db/init/`을 마운트했지만 **데이터 디렉터리가 비어 있을 때만 돌므로** 기존 볼륨에는 `docs/migration/2b-pgvector.sql`을 직접 실행한다 |
| **벡터가 TOAST로 나간다** | `vector(384)` = 1544 bytes, `attstorage=EXTERNAL`. 본문과 합쳐 행이 약 2KB를 넘으면 벡터가 행 밖으로 빠진다(실측: TOAST에 들어간 값이 1540 bytes로 `content`가 아닌 벡터였다). **ADR-0006이 `VARBINARY`를 고르며 피하려던 InnoDB 오프페이지 저장과 같은 문제가 PostgreSQL에서 재현된다.** `SET STORAGE PLAIN`이 대응 수단이며, HNSW 도입 후에는 인덱스가 벡터 사본을 갖기 때문에 영향이 줄어든다 — 인덱스 단계에서 함께 판단한다 |

검증: `VectorTypeMappingTest`(엔티티 왕복 / `ChunkVector` 투영 왕복 / 차원 불일치 거부).
**투영 왕복을 따로 보는 이유**는 검색 경로가 엔티티가 아니라 생성자 투영으로 벡터만 뽑아 오기 때문이다.

**MySQL 9.0 업그레이드는 선택지가 아니다** — `VECTOR` 타입은 있으나 ANN 인덱스가 HeatWave(유료) 전용이라
커뮤니티 에디션에서는 8.0 + BLOB 브루트포스와 성능이 같다. innovation 릴리스라 지원 주기도 짧다.

---

## P2 — 휴가↔근태 연동 (반나절)

`AbsenceRequestService.approveRequest()`가 상태만 `APPROVED`로 바꾸고 끝난다. `absence` 패키지 전체에 `Attendance` 참조가 **0건**이며, `AttendanceStatus.VACATION`은 **정의만 되고 아무도 사용하지 않는다.** 즉 휴가를 승인받아도 근태에 반영되지 않는다.

| # | 항목 | 동시에 닫히는 ADR-0005 항목 |
|---|---|---|
| ~~P2-1~~ | ~~휴가 승인 시 해당 기간 근태를 `VACATION`/`SICK`으로 반영~~ | **완료** — "휴가 관리 절반만 충족" 해소 |
| ~~P2-2~~ | ~~승인 이벤트를 `ApplicationEvent`로 발행 → 근태 모듈이 수신~~ | **완료** — "이벤트 기반 설계 없음" 해소 |
| ~~P2-3~~ | ~~자정 배치로 미체크 인원 `ABSENT` 처리~~ | **완료** — "배치/스케줄링 없음" 해소 |
| ~~P2-4~~ | ~~연차 잔여일수 차감~~ | **이미 구현되어 있었음** — `Member.useLeaveDays()` + `INSUFFICIENT_LEAVE_DAYS` 검증. 백로그 작성 시 오기 |

**설계 판단**

- **이벤트로 분리하되 트랜잭션은 분리하지 않았다.** 비동기나 `AFTER_COMMIT`으로 두면 근태 반영이
  실패했을 때 승인만 남아 **승인된 휴가인데 결근 처리**되는 상태가 된다. `@EventListener`(동기)라
  같은 트랜잭션에서 실행되고 실패 시 함께 롤백된다. 결합도만 낮추고 원자성은 유지한다.
- **`Attendance.clockInTime`을 nullable로 바꿨다.** `NOT NULL`은 "출근한 날"만 상정한 제약이라
  휴가·결근을 담을 수 없었다. 더미 시각을 넣으면 "0시에 출근한 기록"이 되어 근무시간 집계를 오염시킨다.
- **소급 승인 시 기존 기록을 덮어쓰지 않는다.** 이미 출근한 날에 휴가가 승인되면 실제 출퇴근 기록이
  사라지므로, 덮어쓰기는 관리자의 명시적 수정으로 다룰 문제다.
- **배치가 `Clock`을 주입받는다.** `LocalDate.now()`를 직접 부르면 "어제"가 실행 시각에 따라 달라져
  테스트가 불가능하다. 근태는 날짜 경계가 곧 비즈니스 규칙이다.

### P2-6 — 근무일 캘린더

**1단계 완료.** `work_calendar` 테이블 + `WorkCalendarService`로 결근 배치가 근무일을 판단한다.
"공휴일에 전원 결근" 사고는 막혔다.

| 설계 | 근거 |
|---|---|
| **날짜를 PK로 (자연키)** | 같은 날이 두 번 등록될 수 없어야 한다. 대리키 + 유니크 제약보다 의도가 직접 드러난다 |
| **미등록 = 기본 규칙** (평일 근무, 주말 휴무) | 캘린더를 비워둬도 기존 동작이 유지되어 점진 도입이 가능하다. 반대로 "미등록=휴무"로 잡으면 캘린더를 채우기 전까지 **결근 배치가 조용히 무력화**된다 |
| **양방향 예외 표현** | 평일인데 쉬는 날(공휴일)과 주말인데 일하는 날(특근)이 둘 다 존재한다. `DayType.WORKDAY`를 주말에 등록하면 특근일이 된다 |
| **근무일 판단을 한 곳으로** | 초과근무 계산·월말 집계도 같은 판단이 필요해진다. 각자 요일을 보게 두면 곧 어긋난다 |

**남은 것 — 등록 수단이 없다.** `WorkCalendarService.register()` / `registerAll()`은 있으나
**HTTP 엔드포인트가 없어 현재는 SQL로만 넣을 수 있다.** 관리자 컨트롤러가 필요하고,
공공데이터 API(한국천문연구원 특일 정보) 연동으로 법정공휴일을 연 1회 자동 적재하면 손이 덜 간다.

**2·3단계는 P3(근무 정책 엔진)** — 교대조별 휴무 패턴, 개인별 예외.
이 캘린더는 **전사 공통 휴무일만** 다룬다.

작업량 대비 닫히는 항목이 많다. P1 완주 후 착수한다.

### P2-5 — `JwtBlacklist` Redis 이관

`JwtBlacklist`가 `ConcurrentHashMap` **인메모리**다(`JwtBlacklist.java:11`). 단일 인스턴스에서도 문제가 된다:
**재배포하면 블랙리스트가 통째로 사라져, 로그아웃했던 토큰이 만료 전까지 되살아난다.**

Redis는 이미 분산락으로 도입되어 있으므로 저장소를 추가할 필요는 없다. 다중화 시에도 선결 과제이므로
스케일아웃 로드맵(ADR-0004)보다 앞선다.

> 참고 — 인스턴스를 2대 이상으로 늘릴 경우 함께 깨지는 것:
> - `JwtBlacklist` 인메모리 → 인스턴스별로 갈림 (보안)
> - `WebSocketConfig.enableSimpleBroker` → 프로세스 내부 브로커라 인스턴스 간 메시지 전달 불가 (기능)
> - 로컬 캐시 도입 시 정합성
>
> 다만 **MAU 5,000 기준 피크가 약 10 TPS라 성능 목적의 스케일아웃 근거는 없다**(`SERVICE-SCALE-ASSUMPTIONS.md`).
> 늘린다면 이유는 처리량이 아니라 가용성(무중단 배포)이며, 그 전에 ADR-0002 2-2의 채팅 N+1을 먼저 고쳐야 한다
> — N+1이 남은 채로 인스턴스를 늘리면 DB 쿼리만 2배가 된다.

---

## P3 — 이후 (하지 않아도 무방)

우선순위가 낮다. P0~P2를 끝낸 뒤에만 손댄다.

- 근무 정책 엔진 (`WorkShift` 하드코딩 enum → DB 관리)
- 초과근무/야간/휴일 계산 (근로기준법 기준)
- 다단계 결재 (`absence_requests.processed_by` 단일 컬럼이라 스키마부터 변경 필요)
- 월말 집계 API
- 감사 로그 (`@CreatedBy` / `@LastModifiedBy` — 현재 `JpaAuditingConfig` 선언만 존재)
- 채팅 목록 N+1 조치 (ADR-0002 2-2, 진단만 완료)
- ngram FULLTEXT (ADR-0003 3-1)

---

## 문서 정합성 (구현과 함께 반드시 갱신)

| 문서 | 갱신 사유 |
|---|---|
| `docs/PROJECT-SCOPE.md` | **"임베딩/벡터 검색은 없음", "벡터 임베딩 기반 교차언어 검색은 이 프로젝트에 존재하지 않는다"** 고 명시되어 있다. RAG 구현 후 이 문장을 고치지 않으면 문서와 진술이 정면으로 충돌한다 |
| `docs/adr/0005` | 휴가 도메인 미구현·테스트 1건으로 기록되어 있으나 둘 다 해소됨. 낡음 |
| `docs/adr/0003` | 벡터 검색이 3-3(Elasticsearch)을 대체하는지 보완하는지 관계 정리 필요 |
| 신규 `docs/adr/0006` | RAG/벡터 검색 결정 기록 (MySQL 8.0 유지 사유, 9.0·PostgreSQL 이전을 택하지 않은 근거, Phase 1→2 전환 트리거) |
