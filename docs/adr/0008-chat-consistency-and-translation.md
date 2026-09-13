# ADR-0008: 채팅 — 저장이 먼저고, 번역은 그 밖에 있다

- 상태: **일부 구현.** M3(5-4)는 2026-09-13에 실측해 D7·D8을 확정했고, 같은 날 **V6 + 저장 경로(D6·D7·D8, D9의 제약)** 를 넣었다(10절). D1·D2·D3·D10과 읽음/따라잡기 API(11-1)는 전이다. M1·M2는 기준값 실측 전이다
- 대상 코드: `chat/controller/ChatController`, `chat/service/ChatService`, `chat/model/ChatMessages`, `chat/repository/*`(요약 컬럼 UPDATE는 `ChatJdbcRepository`가 JdbcClient로 친다 — 엔티티를 거치지 않는 SQL은 JPA 리포지토리에 두지 않는다), `global/config/{AsyncConfig, WebSocketConfig, StompHandler}`, `ai/service/FastApiService`, `db/migration/V6*`(예정)
- 관련 문서: `docs/WORK-BACKLOG.md` P2-12(정합성 진단)·P2-8-5(언어 컬럼)·P2-17-5(채팅 번역), `docs/SERVICE-SCALE-ASSUMPTIONS.md` 3-3(채팅 규모 가정), `docs/adr/0001`(근태 멱등성), `docs/adr/0004`(기준값 먼저 재는 관례)
- 작성 목적: 채팅은 이 서비스에서 사용자가 하루 종일 열어두는 유일한 화면인데(발표 자료 9P의 인터뷰 두 건이 전부 이걸 가리킨다), 정합성 진단만 있고(P2-12) 결정이 없다. 번역을 붙이기 전에 **저장·전파·번역의 순서**를 정해두지 않으면, 번역이 붙는 순간 지금의 결함 위에 지연이 하나 더 얹힌다. ADR-0001과 같이 **숫자를 지어내지 않는다** — 가정은 가정으로, 측정 전인 것은 [측정 필요]로 적는다.

---

## 1. 배경 — 지금 순서는 "보내고 나서 저장한다"

`ChatController#message`의 실제 순서다.

```java
messagingTemplate.convertAndSend("/sub/chat/room/" + roomId, message);   // ① 방 구독자에게 전파
for (String userId : memberIds)                                           // ② 멤버마다 방 목록 갱신 전파
    messagingTemplate.convertAndSend("/sub/user/" + userId + "/rooms", message);
chatService.saveMessage(message);                                         // ③ @Async @Transactional 저장
```

세 가지가 여기서 나온다.

| # | 사실 | 결과 |
|---|---|---|
| 1 | **전파가 저장보다 먼저다.** ③은 `@Async`라 ①②가 끝난 뒤 다른 스레드에서 돈다 | 저장이 실패해도 수신자는 이미 메시지를 봤다. 새로고침하면 사라진다. 실패는 로그에만 남는다 |
| 2 | **전파 페이로드가 요청 DTO다.** `message_id`도 `sent_at`도 없다 | 수신자는 중복을 걸러낼 키도, 재접속 커서로 삼을 값도 받지 못한다. P2-12-5(재연결 유실)의 뿌리가 여기다 |
| 3 | ②의 `memberIds`는 `getParticipantsIds`가 **메시지마다** `chat_members`를 읽고 행마다 `getMember().getUserId()`로 LAZY 역참조한다 | 메시지 1건 = 조회 1 + 멤버 수만큼의 조회. 10명 방이면 메시지당 쿼리 11개가 **STOMP 인바운드 스레드**에서 돈다 |

여기에 P2-12가 이미 적은 것 — 시계가 둘(`sentAt`은 앱, `last_*`는 DB `NOW()`), 읽음 기준이 시각, `last_message_content` 경합, heartbeat 없음 — 이 얹힌다. 그리고 번역은 어디에도 없다. `Member.language_code`는 있는데 `chat_messages`에 언어 컬럼이 없어 **어느 언어로 쓰였는지 소급이 불가능**하다(P2-8-5).

`AsyncConfig`의 `messageExecutor`는 core 10 / max 50 / queue 10,000이고 거부 정책이 기본값(`AbortPolicy`)이다. 큐가 차면 **`RejectedExecutionException`으로 저장이 조용히 유실**된다 — 이미 전파는 끝난 뒤라 아무도 모른다.

## 2. 결정 요약

| # | 결정 | 근거 |
|---|---|---|
| D1 | **저장이 먼저, 전파는 커밋 뒤** — `@TransactionalEventListener(AFTER_COMMIT)` | 보이는 것은 저장된 것이어야 한다 (3절) |
| D2 | 전파 페이로드에 **DB가 준 `message_id`·`room_seq`·`sent_at`** 을 싣는다 | 중복 제거와 커서의 축이 생긴다 (3절) |
| D3 | **번역은 저장 트랜잭션 밖.** 원문을 먼저 전파하고, 번역은 별도 이벤트로 **수신자 언어별 1회** 호출해 두 번째 전파로 붙인다 | 저장 지연을 번역 지연에 묶지 않는다 (4절) |
| D4 | 번역 결과는 `chat_message_translations(message_id, language_code)` **UNIQUE** | `work_log_translations`와 같은 꼴. 이미 있는 패턴을 따른다 (4-2절) |
| D5 | 번역 실패는 **원문만 보이는 상태로 열화**한다. 저장·전파를 막지 않는다 | 번역은 있으면 좋은 것, 메시지는 있어야 하는 것 (4-3절) |
| D6 | **시계는 DB 하나.** `sent_at`을 DB 기본값으로 옮긴다 | 시계가 둘이면 경계가 어긋난다 (5-1절) |
| D7 | 읽음·최신·커서의 축은 **방 단위 시퀀스 `room_seq`** (`message_id`가 아니다). `chat_rooms.last_message_seq`, `chat_members.last_read_seq` | 시각으로도, `IDENTITY`로도 상태를 판단하지 않는다 — M3가 후자를 반증했다 (5-2·5-3·5-4절) |
| D8 | `room_seq`는 **`saveMessage`가 이미 잡는 `chat_rooms` 행 락 안에서** `last_message_seq + 1 RETURNING`으로 발급 | 순서를 깨뜨린 락이 순서를 고치는 자리다. 새 락이 아니다. M3 실측으로 유실 0 (5-4절) |
| D9 | 클라이언트 발급 `client_msg_id`로 **재전송 멱등** | ADR-0001의 더블클릭과 같은 문제 (6절) |
| D10 | FCM은 **같은 AFTER_COMMIT 이벤트**에서 미접속자에게만 | 근태 이벤트와 반대 경계를 쓰는 기준은 "함께 실패해야 하는가" (8절) |
| — | 오프라인 동기화·메시지 편집/삭제·번역 품질은 **다루지 않는다** | 9절 |

## 3. 저장이 먼저다 — 보이는 것은 저장된 것이어야 한다

지금 순서(전파 → 저장)는 지연을 사려고 정합성을 판 것이다. 그런데 산 지연이 얼마인지 잰 적이 없다.
`SERVICE-SCALE-ASSUMPTIONS.md` 3-3의 가정으로 인바운드는 피크 30 msg/s이고, 단건 INSERT는 ms 단위다.
**ms를 아끼려고 "저장 안 된 메시지가 보이는" 상태를 두는 것은 거래가 안 맞는다.** 이 직관은 7절의 기준값 실측이 확인하거나 뒤집는다.

바뀐 순서:

```
STOMP 인바운드 스레드
  └─ saveMessage()  ── 트랜잭션 ──┐
       UPDATE chat_rooms          │   ← 행 락 + room_seq 발급 (5-3절)
       INSERT chat_messages       │   ← message_id·sent_at은 DB가 준다
       UPDATE chat_members        │
       publish(MessageSaved)      │
  ─────────────────── COMMIT ─────┘
AFTER_COMMIT 리스너
  ├─ 방 구독자 전파   (message_id·room_seq·sent_at 포함)
  ├─ 멤버 방 목록 전파
  ├─ publish(TranslationRequested)   → 4절
  └─ publish(PushRequested)          → 8절 (미접속자만)
```

**`@Async`를 저장에서 뗀다.** 저장이 인바운드 스레드에서 동기로 끝나야 실패가 호출자에게 돌아온다.
실패 시 STOMP `ERROR` 프레임(`StompExceptionHandler`가 이미 있다)으로 보낸 사람에게만 알린다 — 다른 사람은 애초에 못 봤으므로 알릴 것이 없다. 이것이 D1의 요점이다: **실패의 반경이 보낸 사람 하나로 준다.**

`getParticipantsIds`의 LAZY 역참조(1절 #3)는 리스너로 옮기면서 `user_id`만 뽑는 조회 하나로 바꾼다. 인바운드 스레드에서 쿼리 11개를 돌릴 이유가 없어진다.

> **전파가 커밋 뒤로 밀리는 만큼 지연이 붙는다.** 얼마인지는 [측정 필요]. 7절의 첫 항목이다.
> 결과가 "무시할 수준"이면 D1이 공짜인 것이고, 아니면 그 숫자를 두고 다시 정한다.

## 4. 번역은 저장 트랜잭션 밖이다

### 4-1. 왜 밖인가 — 지연의 지배항이 바뀐다

FastAPI 번역은 WebClient 응답 타임아웃이 **60초**다(`external-api.fastapi.response-timeout-ms`, P2-9-1).
건당 수백 ms라고 가정해도 30 msg/s 피크에서 번역을 저장 경로에 동기로 붙이면
**메시지 저장 p99가 DB가 아니라 FastAPI로 결정된다.** RAG에서 병목이 DB가 아니라 전송이었던 것(ADR-0006 8-2)과 같은 종류의 함정이고, 이번엔 붙이기 전에 안다.

그래서 두 번 전파한다.

| 전파 | 시점 | 내용 |
|---|---|---|
| 1차 | AFTER_COMMIT 직후 | 원문 + `message_id` + `sent_at` + `language_code` |
| 2차 | 번역 완료 후 (언어별) | `message_id` + `language_code` + 번역문 |

수신 앱은 1차로 원문을 그리고, 2차가 오면 같은 `message_id`에 번역을 붙인다. **번역이 늦어도 대화는 멈추지 않는다.** 이게 D3다.

### 4-2. 호출 수 — 수신자 수가 아니라 언어 수다

번역 대상 언어는 **방 멤버의 `language_code` 집합에서 발신자 언어를 뺀 것**이다. 10명 방에 한국어·베트남어·태국어가 섞여 있으면 메시지당 호출은 **2회**지 9회가 아니다. 결과는 언어별로 한 행:

```sql
chat_message_translations (
  message_id     BIGINT  REFERENCES chat_messages,
  language_code  VARCHAR(8),
  content        TEXT,
  model          VARCHAR(64),
  trace_id       VARCHAR(64),
  created_at     TIMESTAMP DEFAULT now(),
  UNIQUE (message_id, language_code)
)
```

`work_log_translations`의 `UNIQUE(log_id, language_code)`와 같은 꼴이고, `FastApiService.saveTranslateLog`가 이미 "무조건 save하면 제약 위반"을 처리하는 upsert 패턴을 갖고 있다. 새로 발명하지 않는다.

원문의 언어(`chat_messages.language_code`, P2-8-5)는 **발신자의 `Member.language_code`를 기본값**으로 채운다. FastAPI에 언어 감지가 있다면 나중에 덮어쓸 수 있지만, 지금은 발신자 설정이 가장 싼 근사치고 소급 불가능한 값이 남는 것이 먼저다.

피크 번역 호출은 `SERVICE-SCALE-ASSUMPTIONS.md` 3-3이 10~30 req/s로 잡았는데, 그 값은 "메시지의 10~30%"였다. 언어 집합 기준으로 다시 세면 **"혼합 언어 방의 메시지 × (언어 수 − 1)"** 이고, 이 분포는 실사용 전엔 모른다. [측정 필요]로 남긴다.

### 4-3. 실패는 열화지 장애가 아니다

| 상황 | 동작 |
|---|---|
| FastAPI 타임아웃·5xx | 그 언어의 2차 전파가 없다. 원문은 이미 가 있다 |
| `messageExecutor` 큐 포화 | 번역 작업이 거부된다 — **저장은 이미 끝났으므로 유실되는 것은 번역뿐** |
| 재시도 | **초안에서는 하지 않는다.** 대화는 흘러가고, 5분 뒤 도착한 번역은 가치가 낮다. 필요하면 "번역 다시 요청" 을 클라이언트 동작으로 두는 편이 맞다 |

1절의 "큐가 차면 저장이 유실된다"가 "큐가 차면 번역이 빠진다"로 바뀐다. **같은 executor, 같은 거부 정책인데 유실되는 것의 무게가 다르다** — 저장을 `@Async`에서 뺀 것(3절)의 두 번째 효과다.

## 5. 시계와 순서

### 5-1. 시계는 DB 하나 (D6)

`sentAt`을 `@PrePersist`의 `LocalDateTime.now()`에서 **DB `DEFAULT now()`** 로 옮긴다. `last_message_at`·`last_read_time`은 이미 네이티브 쿼리의 `NOW()`라 그쪽에 맞춘다.
근태에서 `Clock`을 주입한 것과 방향이 반대로 보이지만 같은 원칙이다 — **시각의 출처를 하나로.** 근태는 테스트 가능성 때문에 앱 시계를, 채팅은 네이티브 쿼리가 이미 DB 시계를 쓰고 있어 DB 시계를 고른다.

### 5-2. 읽음은 `room_seq` (D7) — 처음엔 `message_id`로 적었다가 M3로 고쳤다

`chat_members.last_read_time` → `last_read_seq`. 안읽음은 `last_message_seq − last_read_seq`, **두 정수의 차**다.
같은 초에 두 메시지가 와도 경계가 갈리고, 방마다 `COUNT(*)`를 날리던 P2-12-1의 N+1이 **구조적으로 사라진다** — 정합성을 고치니 성능 문제가 따라 없어지는 경우다.

이 문서의 첫 판은 축을 `message_id`로 잡았다. 5-4의 실측이 그것을 반증했다 — `last_read_message_id = 37`인 상태에서
id 20이 **뒤에** 커밋되면, 한 번도 화면에 뜬 적 없는 메시지가 읽음 처리된다. 커서만이 아니라 읽음 경계도 같은 구멍을 갖는다.

### 5-3. 최신은 락 안에서 (D7)

```sql
UPDATE chat_rooms
   SET last_message_seq = last_message_seq + 1, last_message_content = :content, last_message_at = now()
 WHERE room_id = :roomId
RETURNING last_message_seq
```

이 한 문장이 세 가지를 한 번에 한다 — 방 시퀀스 발급, 최신 메시지 갱신, 그리고 `saveMessage`가 **원래 잡던 행 락**.
가드가 필요 없다: 락 안에서 증가하므로 동시에 두 메시지가 와도 순서가 곧 seq다. P2-12-4의 경합이 사라지고,
P2-12-8(방 목록 정렬 키)이 `last_message_seq`를 안전하게 쓴다. `PageableSortDefaultTest`의 `KNOWN_UNSORTED` 한 건이 그때 빠진다.

### 5-4. 재접속 따라잡기의 구멍 — 실측으로 확정 (D8)

P2-12-7이 지적한 대로 `IDENTITY`는 시퀀스를 트랜잭션 밖에서 발급하므로 ID 순서와 커밋 순서가 다를 수 있다.
"실제로 나는가"를 `MessageIdCommitOrderMeasurementTest`로 쟀다 (2026-09-13, 조건은 7절 M3).

| 변형 | 발신자 10 / 30 / 60 | 역전 | **순진한 커서 유실** |
|---|---|---|---|
| INSERT만 → 커밋 | 500 / 1,500 / 3,000건 | 53~58 / 75~78 / 81% | **21 / 21 / 17~20%** |
| `saveMessage` 모양 (INSERT + UPDATE 2) | 〃 | 56~60 / 68~69 / 78% | **56~60 / 68~69 / 78%** |
| **방 시퀀스, 락 안에서 발급** | 〃 | **0 / 0 / 0** | **0 / 0 / 0** |

두 번 돌렸고 범위는 두 실행의 값이다. 읽는 법:

- **구멍은 실재하고 크다.** INSERT만으로도 5건 중 1건을 순진한 커서가 놓친다. `IDENTITY` 자체의 문제다.
- **실제 코드 모양에서는 행 락이 유실을 3배로 증폭한다.** `updateLastMessageNative`가 같은 방의 같은 행을 잠그므로
  동시 트랜잭션이 거기서 줄을 선다. ID는 줄 서기 **전에** 받았고, 락을 먼저 얻은 쪽이 먼저 커밋되면 커서가 그 ID로
  튀고, 기다리던 작은 ID 전부가 그 뒤에 커밋돼 사라진다. `saveMessage` 모양에서 역전 수와 유실 수가 같은 이유다.
- **같은 락 안에서 번호를 받으면 0이다.** 락 획득 순서 = 커밋 순서 = seq 순서. 새 락이 아니라 이미 내던 비용이다.

첫 판의 세 후보 중 "아무것도 안 함"은 탈락했고, watermark는 **폭을 정할 수 없어** 탈락했다 — 락 대기가
`lock_timeout` 5초까지 갈 수 있어 건수로도 시간으로도 안전 여유가 없다. 방 단위 시퀀스가 남았는데,
첫 판이 걱정한 "방 단위 직렬화의 경합 비용"은 **새로 생기는 비용이 아니다.** 지금 코드가 이미 그 행을
잠근다. 그래서 D8은 비용 없는 결정이 됐다.

> **측정의 한계.** 리더는 sleep 없이 폴링하는 최악 조건이다. 실제 재접속 따라잡기는 한 번의 조회이고,
> 그때 유실되는 것은 **그 순간 커밋 전인 트랜잭션 중 ID가 작은 것**뿐이다. 즉 이 표는 "재접속 한 번당
> 몇 %를 잃는가"가 아니라 **"ID 순서를 믿는 가정이 얼마나 자주 깨지는가"** 다. 한 방에 몰리는 버스트(TBM
> 직전)에서는 기전이 같으므로, 빈도가 낮아도 구멍은 같은 구멍이다.

## 6. 멱등 — 근태의 더블클릭과 같은 문제 (D9)

STOMP는 전달을 보장하지 않는다. 클라이언트가 응답을 못 받고 재전송하면 **같은 메시지가 두 번 저장**된다. ADR-0001이 출근 버튼에서 푼 것과 같은 문제고, 해법도 같다 — **클라이언트가 키를 발급하고 서버가 유일성을 강제한다.**

- `chat_messages.client_msg_id UUID`, `UNIQUE(room_id, client_msg_id)`
- 중복 INSERT는 제약 위반 → 기존 행을 돌려준다(재전송에 같은 `message_id`로 응답)
- 이 키는 5-4의 watermark 대응에서 **클라이언트 중복 제거의 키**로도 쓰인다

ADR-0001은 Redis 분산락 + DB 유니크였다. 여기서는 **DB 유니크만** 쓴다 — 중복 요청이 "같은 사용자의 연타"가 아니라 "네트워크 재전송"이라 시간 간격이 있고, 락으로 막을 경합이 아니다. 같은 문제에 다른 도구를 고른 이유를 남긴다.

## 7. 측정 계획 — 기준값이 먼저다

ADR-0004의 관례대로 **바꾸기 전의 값을 먼저 잰다.** 지금 코드(전파 → 비동기 저장)가 기준선이고, 3절이 그보다 얼마나 느려지는지가 첫 숫자다. k6 시나리오는 ADR-0001처럼 **정합성 케이스와 처리량 케이스를 분리**한다.

| # | 재는 것 | 답하는 질문 | 결정 |
|---|---|---|---|
| M1 | 메시지 수신 지연 p50/p99 — 현재 vs 저장 후 전파 | D1의 대가가 얼마인가 | D1 유지 여부 |
| M2 | 저장 p99 — 번역 동기 vs 비동기(D3) | 동기로 붙였으면 얼마나 나빴을 것인가 | D3의 근거 |
| M3 | 동시 전송 N건에서 커밋 순서 ↔ `message_id` 역전 빈도 | 5-4의 구멍이 실재하는가 | **완료 (2026-09-13)** — 실재한다. 5-4의 표. D7·D8 확정 |
| M4 | 동시 전송 후 `last_message_seq`가 실제 건수와 일치하는가 | P2-12-4가 재현되는가 | M3의 방 시퀀스 변형이 사실상 이것이다 — 3,000건에서 유실 0이면 seq도 빠짐없다. 별도 측정은 하지 않는다 |
| M5 | 세션 1,000개 · 10명 방에서 팬아웃 지연 | 300 push/s의 무릎이 어디인가 | 2차 전파 방식 |
| M6 | 연결을 끊고 재접속했을 때 유실 건수 — 커서 전/후 | P2-12-5 | D8·FCM 근거. M3의 "순진한 커서 유실"이 서버 쪽 절반을 이미 답했다 |

**M3 조건** (재현용): 로컬 Windows 10, Docker Desktop, Testcontainers `pgvector/pgvector:pg17`(PostgreSQL 17.11),
`lock_timeout=5s`. raw JDBC, 스크래치 DB에 운영 Flyway 마이그레이션 적용. 발신자 10/30/60 × 50건, 같은 방.
리더는 별도 커넥션에서 sleep 없이 `WHERE room_id=? AND key > cursor ORDER BY key` 폴링, `cursor = max(seen)`.
방 시퀀스 변형은 스크래치 DB에만 `chat_rooms.last_message_seq`·`chat_messages.room_seq`를 더해 돌렸다(V6 예정).

밀어 올리는 상한은 가정치의 10배(300 msg/s)다. "300에서도 멀쩡하다"가 나오면 그대로 적는다 — 근태에서 "3 TPS에 분산락이 필요한가"에 "처리량 때문이 아니다"로 답한 것과 같은 결론이 될 수 있고, 그것도 결론이다.

## 8. FCM은 같은 이벤트의 다른 소비자다 (D10)

3절의 AFTER_COMMIT 리스너에 소비자를 하나 더 둔다. `StompHandler`의 `onlineUsers`(sessionId → userId)가 이미 접속 상태를 들고 있으므로 **접속 중이 아닌 멤버에게만** 푸시한다. 접속 중인 사람은 WebSocket으로 받았다.

P2-12-6이 적은 기준이 여기서 코드가 된다:

| 이벤트 | 경계 | 이유 |
|---|---|---|
| 휴가 승인 → 근태 반영 (P2) | **같은 트랜잭션**, 동기 리스너 | 근태 반영이 실패하면 승인도 실패해야 한다 |
| 메시지 저장 → 전파·번역·푸시 (이 문서) | **AFTER_COMMIT** | 롤백된 메시지의 알림이 나가면 안 되고, 알림 실패가 저장을 되돌리면 안 된다 |

같은 `ApplicationEvent`를 반대 경계에서 쓰는 기준은 하나다 — **함께 실패해야 하는가.** 토큰 생명주기·다기기·야간 근무자 알림 시간대는 P2-12-6에 그대로 두고, 이 문서는 경계만 정한다.

## 9. 다루지 않는 것

| 항목 | 이유 |
|---|---|
| 오프라인 동기화 (P2-17-9) | 클라이언트 로컬 DB·충돌 정책이 함께 있어야 한다. 서버만으로 완주를 확인할 수 없다 |
| 메시지 편집·삭제 | 5-3의 "큰 ID가 이긴다"가 편집을 만나면 다시 정해야 한다. 지금 요구사항에 없다 |
| 번역 품질·언어 감지 | FastAPI(팀원 담당, `PROJECT-SCOPE.md`). 이 문서는 호출 시점과 실패 처리만 정한다 |
| 방 목록 N+1 (P2-12-1) | 이 문서와 독립이다. 따로 고친다 |

## 10. 스키마 변경 (V6 — 적용, 2026-09-13)

`V6__chat_room_seq_and_translations.sql`. `FlywayMigrationTest`(빈 DB 적용·멱등)와 `SchemaValidationTest`(엔티티 일치)를 통과했다.

| 테이블 | 변경 |
|---|---|
| `chat_messages` | `language_code VARCHAR(8)`, `client_msg_id UUID`, `UNIQUE(room_id, client_msg_id)`, `sent_at DEFAULT now()` |
| `chat_messages` | `room_seq BIGINT NOT NULL`, `UNIQUE(room_id, room_seq)` — 기존 `(room_id, sent_at)` 인덱스를 `(room_id, room_seq)`로 대체 |
| `chat_members` | `last_read_seq BIGINT NOT NULL DEFAULT 0` (기존 `last_read_time`은 한 릴리스 동안 병행 후 제거) |
| `chat_rooms` | `last_message_seq BIGINT NOT NULL DEFAULT 0` |
| `chat_message_translations` | 신규 (4-2절) |

`ddl-auto=validate`이므로 엔티티와 함께 바꾸지 않으면 기동이 실패한다. 그게 의도다.

기존 행의 `room_seq` 채우기는 `ROW_NUMBER() OVER (PARTITION BY room_id ORDER BY message_id)`로 한 번 한다 —
과거 메시지의 역전은 이미 일어난 일이라 되돌릴 수 없고, ID 순서가 그나마 가장 가까운 근사다.

### 10-1. V6와 함께 바뀐 코드

| 자리 | 변경 | 검증 |
|---|---|---|
| `ChatJdbcRepository.nextRoomSeq` | `updateLastMessage`를 대체. `last_message_seq + 1 ... RETURNING`이 락·번호·요약 갱신을 한 문장에 | `ChatServiceRoomSeqTest` 방_시퀀스 |
| `ChatService.saveMessage` | 순서가 **UPDATE(락+번호) → INSERT → 멤버 UPDATE**. 번호를 락 밖에서 받으면 5-4의 구멍이 그대로라 INSERT가 뒤로 갔다 | 〃 |
| `ChatMessages.sentAt` | `@Generated(INSERT)` + `insertable=false`. 앱이 넣지 않고 DB `DEFAULT now()`를 RETURNING으로 읽는다(D6) | 〃 시계와_언어 |
| `ChatMessages.languageCode` | 발신자 `users.language_code`로 채운다(P2-8-5). PK 조회 하나가 늘었다 | 〃 |
| `ChatMessages.clientMsgId` / DTO | 컬럼과 제약만. "기존 행을 돌려준다"(D9)는 D1과 함께 — 지금은 `@Async`가 유니크 위반 예외를 삼켜 호출자가 알 수 없다. 테스트가 그 사실을 그대로 적어뒀다 | 〃 재전송_멱등 |
| `MessageIdCommitOrderMeasurementTest` | V6 이전 경로를 흉내 내는 두 변형이 락 밖 시퀀스(`nextval`)로 번호를 받게 바꿨다. 결과는 같다(유실 21% / 56~78% / 0) | 자체 |

**V7로 미룬 것**: `chat_members.last_read_time` 제거 — 읽음 API가 `last_read_seq`를 쓰게 바뀐 뒤(11-1). 그전까지 `saveMessage`는 예전대로 발신자의 `last_read_time`만 갱신한다. `last_read_seq`는 읽음 API가 올리는 값이고, 발신자 본인의 것도 그 API가 함께 처리하는 편이 "자기가 보낸 것은 읽은 것"을 한 곳에 두는 길이다.

## 11. 계약과 비용

### 11-1. 입출력 — 바뀌는 것

| 경로 | 지금 | 설계 |
|---|---|---|
| STOMP `/pub/chat/message` (입력) | `{roomId, senderId, content, messageType, fileUrl}` | + `clientMsgId: UUID` (D9). `senderId`는 세션이 덮어쓰므로 입력에서 뺀다 |
| STOMP `/sub/chat/room/{id}` (1차 전파) | 요청 DTO 그대로 | `{messageId, roomSeq, clientMsgId, senderId, content, messageType, fileUrl, languageCode, sentAt}` — 전부 DB가 준 값 |
| STOMP 2차 전파 (번역) | 없음 | `{messageId, languageCode, translated}` — 수신자 언어별 1회 |
| STOMP `ERROR` (저장 실패) | 로그만 | 보낸 사람에게만 `{clientMsgId, code}` |
| `GET /room/{id}/messages` | `lastMessageId` 커서 | `afterSeq` 커서, 정렬 `roomSeq` |
| 읽음 처리 | 메시지 저장 시 `NOW()` | `PATCH /room/{id}/read {upToSeq}` → `last_read_seq = GREATEST(last_read_seq, :upToSeq)` |
| 방 목록 | 정렬 없음 (P2-12-8) | `ORDER BY last_message_seq DESC`, 안읽음 = `last_message_seq − last_read_seq` |

클라이언트가 지킬 것은 둘이다 — 재접속 시 `afterSeq = 마지막으로 받은 roomSeq`로 따라잡기 한 번, `clientMsgId`로 재전송 중복 제거.

### 11-2. 복잡도 — 어디가 O(1)이고 어디가 병목인가

| 연산 | 비용 | 비고 |
|---|---|---|
| 메시지 1건 저장 | **O(1)**: UPDATE(방 행 락 + seq) → INSERT → UPDATE(멤버) | 지금과 쿼리 수가 같다(3). 락도 같은 락. **추가 비용 0** |
| 방당 처리량 상한 | 락 보유 시간의 역수 | 방 단위 직렬화는 **이미 있던 것**(`updateLastMessageNative`). 방끼리 독립이라 전체는 방 수에 비례 |
| 따라잡기 `afterSeq` | **O(log n + k)** — `(room_id, room_seq)` 인덱스 | 기존 `(room_id, sent_at)`과 같은 모양 |
| 안읽음 수 | **O(1)** — 두 정수의 차 | 지금은 방마다 `COUNT(*)` (P2-12-1). 구조적으로 사라진다 |
| 방 목록 정렬 | O(m log m), m = 내 방 수 | `last_message_seq`는 락 안에서만 증가하므로 경합 없음 |
| 1차 전파 | **O(멤버 수)** | 지금과 같다. 300 push/s의 무릎이 있다면 여기다 (M5) |
| 번역 | O(언어 수 − 1) 외부 호출, 저장 경로 밖 | 수신자 수가 아니라 언어 수 (4-2절) |

한 줄로: **저장 경로에 새 비용이 없고, 안읽음이 O(n)에서 O(1)로 내려가며, 대가인 방 단위 직렬화는 이미 내고 있던 것이다.**
