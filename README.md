# DOCKin — 조선소 현장 작업 관리 백엔드

> **다국어 현장의 작업일지를 언어에 상관없이 검색할 수 있게 만들고,
> 그 과정에서 내린 결정을 실측으로 뒷받침한 프로젝트.**

조선소에는 한국어를 읽지 못하는 근로자가 있다. 그런데 작업일지는 한국어로 쌓인다.
**베트남어로 물어도 한국어 작업일지에서 답을 찾아주는 것**이 이 서비스의 중심이다.

근태·휴가·작업일지·안전교육·체크리스트·실시간 채팅이 그 위에 얹힌다.

---

## 핵심 — 권한이 걸린 교차언어 RAG 검색

`work_logs` → 청킹 → 임베딩 → `document_chunks`(pgvector) → 유사도 검색 → 챗봇 근거 주입.

다국어 임베딩 모델(`multilingual-e5-small`)을 쓰므로 **언어별 analyzer 없이** 교차언어가 성립한다.
한국어 코퍼스만 두고 영어 질의 5/5, 베트남어 질의 4/5로 정답을 찾는다.

**설계에서 중요하게 본 것은 접근 제어다.**

- 벡터 유사도만으로는 접근 제어가 되지 않는다. 검색 결과를 그대로 프롬프트에 넣으면
  **권한 없는 사용자에게 남의 작업일지가 노출된다.**
- 그래서 **후필터가 아니라 선필터**로 구현했다. 후필터는 요청한 k보다 적게 반환되어
  "내가 못 보는 문서가 존재한다"는 사실이 유출되고, 검색어를 바꿔가며 반복하면
  문서의 존재 윤곽을 그릴 수 있다.
- **폴백도 같은 권한 모델을 쓴다.** 임베딩 서버가 죽으면 키워드 검색으로 떨어지는데,
  기존 검색을 그대로 갖다 썼다면 **장애 상황에서만 남의 일지가 새어 나갔을 것이다.**

> 현재 상태: **검색(R)까지 동작 확인.** 답변 생성(G)은 팀원 FastAPI 서버가 필요하다.
> HNSW recall은 측정 중이며, 결과가 나오기 전까지 "인덱스를 켜도 되는가"에 결론을 내지 않는다.

---

## 기술 스택

| 영역 | 사용 |
|---|---|
| 언어 · 프레임워크 | Java 21, Spring Boot 4.0.1 |
| 데이터베이스 | PostgreSQL 17 + **pgvector** (HNSW) |
| 스키마 관리 | Flyway (`ddl-auto=validate`) |
| ORM | Spring Data JPA / Hibernate 7 (`hibernate-vector`) |
| 임베딩 추론 | HuggingFace **TEI** + `intfloat/multilingual-e5-small` (384차원) |
| 캐시 · 분산락 | Redis + Redisson |
| 인증 | Spring Security + JWT |
| 실시간 | WebSocket / STOMP |
| 외부 연동 | WebClient(WebFlux), AWS S3 |
| 인프라 | Docker Compose, Nginx |
| API 문서 | springdoc-openapi (`/swagger-ui.html`) |

---

## 시스템 구성

```
                  ┌───────────────┐
   클라이언트 ───▶ │ dockin-nginx  │ (리버스 프록시)
                  └───────┬───────┘
                          ▼
                  ┌───────────────┐      ┌──────────────────┐
                  │  dockin-app   │ ───▶ │ dockin-embedding │  텍스트 → 벡터
                  │ (Spring Boot) │      │      (TEI)       │
                  └───┬───────┬───┘      └──────────────────┘
                      │       │
                      ▼       ▼
          ┌───────────────┐  ┌──────────────┐
          │  DOCKin-DB    │  │ dockin-redis │  분산락
          │ PostgreSQL 17 │  └──────────────┘
          │  + pgvector   │
          └───────────────┘
                      │
                      ▼
          팀원 FastAPI (STT · 번역 · 챗봇 생성) — 별도 서비스
```

`dockin-app`은 `dockin-embedding`에 **기동 의존성을 걸지 않는다.**
임베딩 서버가 죽어도 검색이 키워드 폴백으로 동작해야 하기 때문이다.

---

## 실행

```bash
# 1) 환경변수 준비
cp .env.example .env      # DB_PASSWORD, JWT_SECRET, AWS_*, AI_SERVER_URL 등을 채운다

# 2) 전체 기동
docker compose up -d

# 3) 표본 데이터 + 기동 직후 색인 (로컬 전용)
SPRING_PROFILES_ACTIVE=seed ./gradlew bootRun --args='--rag.indexing.on-startup=true'
```

`seed` 프로파일에서만 Flyway가 `db/seed`를 읽는다. **운영·CI 경로에서는 시드 SQL을 열어보지도 않는다.**

---

## 디렉터리 구조

```
DOCKin-spring/
├── .github/workflows/          # CI — 테스트 자동 실행
├── docs/                       # 설계 결정 기록 (아래 "문서" 참고)
│   ├── adr/                    # ADR 0001~0007
│   └── db/                     # 스키마 스냅샷, HNSW 재생성 SQL
├── nginx/conf.d/               # 리버스 프록시 설정
├── src/main/java/com/DOCKin/
│   ├── absence/                # 휴가 신청·승인 → 근태 자동 반영 (동기 이벤트)
│   ├── ai/                     # FastAPI 연동 (STT · 번역 · 챗봇)
│   ├── attendance/             # 근태 — 분산락 + 비관적 락 + 멱등성
│   ├── chat/                   # 실시간 채팅 (WebSocket/STOMP)
│   ├── checklist/              # 작업 체크리스트
│   ├── global/                 # config · security · error · file · util
│   ├── member/                 # 회원 · 인증 (JWT)
│   ├── rag/                    # ── 벡터 검색 파이프라인 ──
│   │   ├── chunking/           #    문장 경계 기반 청킹
│   │   ├── model/              #    DocumentChunk · SourceType · Visibility
│   │   ├── repository/
│   │   └── service/            #    Indexing · Embedding · Retrieval · RagChat
│   ├── safetyCourse/           # 안전교육
│   └── worklog/                # 작업일지 + 댓글
├── src/main/resources/
│   ├── db/migration/           # Flyway — 스키마 전체가 여기 있다
│   ├── db/seed/                # 표본 데이터 (seed 프로파일 전용)
│   ├── application.properties
│   └── application-seed.properties
├── compose.yaml
└── Dockerfile
```

---

## API

전체 명세는 기동 후 `/swagger-ui.html`에서 볼 수 있다.

<details>
<summary><b>인증 · 회원</b></summary>

| Method | Endpoint | 설명 |
|:---|:---|:---|
| `POST` | `/member/signup` | 회원가입 |
| `POST` | `/member/login` | 로그인 · JWT 발급 |
| `POST` | `/member/logout` | 로그아웃 (토큰 무효화) |
| `DELETE` | `/member/{userId}` | 회원 탈퇴 |

</details>

<details>
<summary><b>근태 · 휴가</b></summary>

| Method | Endpoint | 설명 |
|:---|:---|:---|
| `POST` | `/api/attendance/in` | 출근 — 분산락 + 비관적 락 |
| `POST` | `/api/attendance/out` | 퇴근 |
| `GET` | `/api/attendance` | 개인 근태 기록 조회 |
| `POST` | `/api/absence/requests` | 휴가 신청 (증빙 파일 첨부) |
| `GET` | `/api/absence/requests` | 내 휴가 신청 목록 |
| `GET` | `/api/absence/admin/requests` | 전체 신청 목록 (관리자) |
| `PATCH` | `/api/absence/admin/requests/{requestId}/approve` | 승인 → **근태 자동 반영** |
| `PATCH` | `/api/absence/admin/requests/{requestId}/reject` | 반려 |

</details>

<details>
<summary><b>작업일지 · 댓글</b></summary>

| Method | Endpoint | 설명 |
|:---|:---|:---|
| `GET` | `/api/work-logs` | 목록 조회 (페이징) |
| `POST` | `/api/work-logs` | 작성 (이미지 첨부) |
| `POST` | `/api/work-logs/stt` | **음성 파일 기반 작성 (STT)** |
| `GET` | `/api/work-logs/search` | 키워드 검색 |
| `GET` | `/api/work-logs/others/{targetUserId}` | 다른 사용자의 일지 조회 |
| `PUT` | `/api/work-logs/{logId}` | 수정 |
| `DELETE` | `/api/work-logs/{logId}` | 삭제 |
| `GET` | `/api/work-logs/{logId}/comments` | 댓글 목록 |
| `POST` | `/api/work-logs/{logId}/comments` | 관리자 피드백 작성 |
| `PUT` | `/api/work-logs/{logId}/comments/{commentId}` | 댓글 수정 |
| `DELETE` | `/api/work-logs/{logId}/comments/{commentId}` | 댓글 삭제 |

</details>

<details>
<summary><b>AI 연동</b></summary>

| Method | Endpoint | 설명 |
|:---|:---|:---|
| `POST` | `/api/ai/chatbot` | **RAG 챗봇** — 벡터 검색 결과를 근거로 주입 |
| `POST` | `/api/ai/translate/{logId}` | 작업일지 다국어 번역 |
| `POST` | `/api/ai/rt-translate` | 실시간 음성 번역 |

</details>

<details>
<summary><b>실시간 채팅</b></summary>

| 구분 | Endpoint | 설명 |
|:---|:---|:---|
| `STOMP` | `/ws` | WebSocket 연결 — `CONNECT`에서 JWT 검증 |
| `STOMP` | `/chat/message` | 메시지 전송 |
| `POST` | `/api/chat/room` | 채팅방 생성 |
| `GET` | `/api/chat/rooms` | 참여 중인 방 목록 (안읽음 수 포함) |
| `GET` | `/api/chat/room/{roomId}` | 방 상세 |
| `PUT` | `/api/chat/room/{roomId}` | 방 정보 수정 |
| `GET` | `/api/chat/room/{roomId}/messages` | 대화 내역 (무한 스크롤) |
| `GET` | `/api/chat/room/{roomId}/messages/search` | 대화 내 검색 |
| `DELETE` | `/api/chat/room/leave/{roomId}` | 나가기 |
| `DELETE` | `/api/chat/room/{roomId}` | 방 삭제 |

</details>

<details>
<summary><b>안전교육 · 체크리스트</b></summary>

| Method | Endpoint | 설명 |
|:---|:---|:---|
| `GET` | `/api/safety/user/courses` | 교육 과정 목록 |
| `GET` | `/api/safety/user/courses/search` | 과정 검색 |
| `GET` | `/api/safety/user/training/uncompleted` | 미이수 항목 |
| `PATCH` | `/api/safety/user/training/complete` | 이수 처리 |
| `POST` · `PUT` · `DELETE` | `/api/safety/admin/courses...` | 과정 관리 (관리자) |
| `GET` | `/api/checklist/user/checklists` | 내 체크리스트 |
| `PATCH` | `/api/checklist/user/checklists/{checklistId}/items/{itemId}/check` | 항목 체크 |
| `POST` · `PUT` · `DELETE` | `/api/checklist/admin/checklists...` | 체크리스트 관리 (관리자) |

</details>

<details>
<summary><b>운영 · 관측</b></summary>

| Method | Endpoint | 접근 | 설명 |
|:---|:---|:---|:---|
| `GET` | `/actuator/health` | **익명** | UP/DOWN. 로드밸런서·compose 헬스체크용. 세부 항목은 인증 시에만 |
| `GET` | `/actuator/metrics` | 관리자 | 힙·GC·HikariCP 커넥션 풀 |
| `GET` | `/actuator/info` | 관리자 | 빌드 버전과 **커밋 해시** — 지금 도는 코드가 무엇인지 |

모든 응답에 `X-Trace-Id`가 실린다. 요청에 같은 헤더를 보내면 그 값을 이어 쓰고,
AI 경로는 본문의 `traceId`가 우선한다 — `chat_history.trace_id`와 로그를 같은 ID로 묶기 위해서다.

</details>

---

## 문서 — 이 저장소에서 가장 중요한 부분

기능 코드보다 **왜 그렇게 만들었는가**의 기록이 더 많다.

| 문서 | 내용 |
|---|---|
| [`docs/adr/`](docs/adr) | 결정 기록 — 동시성, 성능 백로그, 검색 도메인, 스케일링, pgvector, 코퍼스 보존 |
| [`docs/SERVICE-SCALE-ASSUMPTIONS.md`](docs/SERVICE-SCALE-ASSUMPTIONS.md) | 규모 가정과 **실측값**. 가정이 틀렸을 때 그 기록도 남긴다 |
| [`docs/WORK-BACKLOG.md`](docs/WORK-BACKLOG.md) | 발견된 결함과 우선순위 |
| [`docs/PROJECT-SCOPE.md`](docs/PROJECT-SCOPE.md) | 범위와 경계 |

### 측정이 가설을 뒤집은 기록

이 프로젝트에서 반복적으로 확인한 것은 **"설정은 있는데 안 먹는다"** 는 부류의 결함이다.

| 무엇 | 어떻게 드러났나 |
|---|---|
| **병목이 DB가 아니었다** | 브루트포스 10만 청크 5,165ms. `EXPLAIN`으로 보니 병목의 **81%가 클라이언트 전송**이었다. 인덱스를 만들기 전에 **계산 위치만 DB로 옮겨 약 70배**(5,165ms → 57ms). HNSW는 그다음이었다 |
| **Elasticsearch를 도입하지 않았다** | ADR-0003이 언어별 analyzer로 다국어 검색을 계획했으나, 다국어 임베딩이 같은 요구를 먼저 충족해 **도입 근거가 사라졌다.** 취소 이유를 근거와 함께 남겼다 |
| **JDBC 배치가 안 먹고 있었다** | 설정은 켜져 있었는데 `IDENTITY` 전략이 막고 있었다. DB가 센 값으로 확인 |
| **`ddl-auto=update`가 실패를 삼켰다** | DDL 실패를 로그만 남기고 기동을 막지 않아 **테이블 없이 정상 기동한 것처럼** 보였다 → Flyway + `validate`로 전환 |
| **검증 장치 셋을 통과한 버그** | `@Lob`이 PgJDBC에서 작업일지 읽기를 **통째로** 깨뜨리고 있었다. `validate`도, 스키마 테스트도, 테스트 106개도 전부 통과했다 — **데이터가 없어서** 아무도 몰랐다 |
| **`JAVA_OPTS`가 적용된 적이 없었다** | `ENTRYPOINT`가 exec 형식이라 변수를 참조하지 않는다. 메모리 산정 문서 전체가 틀린 전제 위에 있었다 |
| **문서의 처리량이 4~13배 틀렸다** | "12ms/건"이 실제 코퍼스에서는 46~153ms/건. **청크 길이가 처리량을 지배한다** |

> 값어치 있는 것은 "무엇을 만들었나"가 아니라 **"무엇이 틀렸다는 걸 어떻게 알았나"** 라고 보고,
> 그 과정을 지우지 않고 문서에 남긴다. 측정이 자기 가설을 뒤집은 기록이 여러 건 있다.

---

## 알려진 제약

정직하게 남긴다. 자세한 내용과 우선순위는 [`docs/WORK-BACKLOG.md`](docs/WORK-BACKLOG.md)에 있다.

- **HNSW 설정이 코드에 없다** — recall을 재고 `ef_search=100` / `iterative_scan=relaxed_order`로
  결정했으나(ADR-0006 8-4), 세션 변수로 잰 값이라 조회 경로에 `SET`이 아직 들어가지 않았다
- **recall 수치는 하한선이다** — 생성 코퍼스는 문형이 여섯 개뿐이라 실제보다 조밀하다.
  "켜도 되는가"의 판단에는 쓸 수 있어도 "recall이 몇 퍼센트인가"의 답으로 인용하면 안 된다
- **CD 없음** — 테스트 CI만 있고 배포 자동화는 복구하지 않았다. 이미지 태그도 `latest` 고정이라
  **무엇이 올라가 있는지 태그로는 알 수 없다**(`/actuator/info`로 물어볼 수는 있다)
- **채팅 정합성** — 읽음 판정이 시각 기준이라 단조 증가 ID 기준으로 옮겨야 한다
- **관측이 "물어볼 수 있는" 단계까지다** — Actuator로 헬스·메트릭·빌드 정보를 답하고 로그에
  `traceId`가 붙지만, **시계열 수집(Prometheus)과 구조적 로깅은 없다.** 알람도 없다
- **추적 ID가 리액티브 경로에서 끊긴다** — MDC가 `ThreadLocal`이라 `/api/ai/rt-translate`의
  WebClient 호출 이후 로그에는 추적 ID가 없다
