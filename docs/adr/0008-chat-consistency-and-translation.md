# ADR-0008: 채팅 — 저장이 먼저고, 번역은 그 밖에 있다

- 상태: **일부 구현.** M3(5-4)는 2026-09-13에 실측해 D7·D8을 확정했고, 같은 날 **V6 + 저장 경로(D6·D7·D8)** 를 넣었다(10절). 2026-09-14에 **D1·D2·D9**(저장 → 커밋 → 전파, 실패는 발신자에게만, 재전송은 같은 행)와 **읽음/따라잡기 API**(11-1)를 넣었다. 같은 날 **V7**이 `last_read_time`을 내렸다 — 읽는 곳이 0이 된 뒤. D3·D10이 남았다. **M1·M6은 2026-09-14 실측했다(7-1·7-2절)** — D1의 대가는 p99 +70~90ms(대신 이전 코드에서 1절의 진단이 틀렸음을 발견했다), 따라잡기 프로토콜은 유실 0. `chat_test.html`이 클라이언트 몫을 하고 heartbeat 10초가 붙었다. **M7(2026-09-16)이 FastAPI 번역을 처음 잰 것이다 — 로컬 i3 2초, AWS 8 vCPU 0.28초, CTranslate2 int8 34ms(7-3). D3는 팀원 서버의 엔진 교체가 선결이고 그때까지는 수동 번역**
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

`AsyncConfig`의 `messageExecutor`는 core 10 / max 50 / queue 10,000이고 거부 정책이 기본값(`AbortPolicy`)이다. 처음 이 문서는 "큐가 차면 `RejectedExecutionException`으로 저장이 조용히 유실된다"고 적었다. **틀렸다 — M1(7-1절, 2026-09-14)에서 확인했다.** ③의 `@Async`에는 실행기 이름이 없고 `messageExecutor` 빈의 선언 타입은 `TaskExecutor`가 아니라 `Executor`라, Spring은 그 빈을 찾지 못하고 `SimpleAsyncTaskExecutor`로 떨어진다. **작업마다 스레드 하나, 상한 없음.** 실제 결함은 큐 거부가 아니라 그 반대다 — 저장 스레드가 수십 개씩 각자 `@Transactional`로 커넥션(풀 10개)을 잡고, 인바운드 스레드의 멤버십 검사·참가자 조회가 커넥션을 **기다리느라 전파가 초 단위로 밀린다.** 30 msg/s에서 수신 p50이 2.7초였다(7-1절).

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
실패는 보낸 사람에게만 알린다 — 다른 사람은 애초에 못 봤으므로 알릴 것이 없다. 이것이 D1의 요점이다: **실패의 반경이 보낸 사람 하나로 준다.**

첫 판은 그 통지를 STOMP `ERROR` 프레임으로 적었다. 구현하며 뺐다 — **`ERROR`는 프로토콜상 연결 종료**이고 Spring도 보낸 뒤 세션을 닫는다. 메시지 한 건이 실패했다고 연결을 끊으면 그 뒤 도착할 다른 방의 메시지까지 잃는다. 실패의 반경을 "보낸 사람 하나"로 줄이려다 "그 사람의 모든 방"으로 키우는 셈이다. 그래서 본인만 구독하는 `/sub/user/{userId}/errors` 큐로 `{clientMsgId, code, message}`를 보낸다. `clientMsgId`가 유일한 짝맞춤 키다 — 서버 ID는 저장이 안 됐으니 없다.

`getParticipantsIds`의 LAZY 역참조(1절 #3)는 리스너로 옮기면서 `user_id`만 뽑는 조회 하나로 바꾼다. 인바운드 스레드에서 쿼리 11개를 돌릴 이유가 없어진다.

> **전파가 커밋 뒤로 밀리는 만큼 지연이 붙는다.** 얼마인지는 [측정 필요]. 7절의 첫 항목이다.
> 결과가 "무시할 수준"이면 D1이 공짜인 것이고, 아니면 그 숫자를 두고 다시 정한다.

### 3-1. 구현 (2026-09-14)

| 자리 | 내용 |
|---|---|
| `ChatService.saveMessage` | `@Async` 제거. `@Transactional` 대신 `TransactionTemplate`으로 경계를 직접 긋는다 — D9의 동시 재전송에서 유니크 위반은 **중단된 트랜잭션 안에서 복구할 수 없어** 밖에서 잡아 다시 찾아야 하기 때문. 반환값은 DB가 채운 `ChatMessageResponseDto`(D2) |
| `ChatMessageSaved` + `ChatBroadcaster` | 트랜잭션 안에서 발행, `@TransactionalEventListener(AFTER_COMMIT)`에서 방·멤버에게 전파. 멤버는 `user_id`만 뽑는 SQL 하나(`ChatJdbcRepository.memberIds`) — 1절 #3의 쿼리 11개가 1개로 |
| `ChatController` | 전파 코드 삭제. 멤버십 검사 + 저장을 `try`로 감싸 실패를 `/sub/user/{id}/errors`로. `StompHandler`의 구독 허용 패턴에 `errors` 추가 |
| D9 | `clientMsgId`가 있으면 먼저 찾고, 있으면 저장·전파 없이 그 행을 돌려준다. 동시 재전송의 진 쪽은 유니크 위반 뒤 다시 찾아 이긴 쪽의 행을 돌려준다 |

검증: `ChatBroadcasterTest`(커밋 후 전파 페이로드에 `messageId`·`roomSeq`·`sentAt` / 롤백 시 침묵 / 재전송은 재전파 없음), `ChatControllerMembershipTest`(컨트롤러는 전파하지 않는다, 실패는 발신자 큐로), `ChatServiceRoomSeqTest`(재전송이 같은 `messageId`).

**부수 효과 하나.** `AsyncConfig.messageExecutor`(core 10 / max 50 / queue 10,000)는 여전히 아무도 쓰지 않는다 — 1절이 정정한 대로 **전에도 쓰인 적이 없다.** `@Async`를 뗀 것이 곧 상한 없는 저장 스레드를 없앤 것이라, 7-1절의 "출하 상태 대비 100배"는 D1의 공이 아니라 이 결함이 사라진 값이다. D3(번역)가 붙을 때 이 실행기를 **이름으로** 지정해 쓴다 — 그때 거부되는 것은 번역뿐이다(4-3절).

## 4. 번역은 저장 트랜잭션 밖이다

### 4-1. 왜 밖인가 — 지연의 지배항이 바뀐다

FastAPI 번역은 WebClient 응답 타임아웃이 **60초**다(`external-api.fastapi.response-timeout-ms`, P2-9-1).
건당 수백 ms라고 가정해도(**실측 7-3절: 서버급 CPU에서 280ms, 로컬 i3에선 2초**) 30 msg/s 피크에서 번역을 저장 경로에 동기로 붙이면
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

**언어쌍은 서버가 가진 것만 된다 (2026-09-16 확인).** 팀원 FastAPI(`DOCKin-aiserver`)의 번역은 CPU MarianMT(Helsinki-NLP opus-mt)이고 모델이 있는 쌍은 **ko↔en, en↔vi, en↔zh, en↔th** 여덟 개다. `ko→vi`를 보내면 400 — 한국어와 베트남어 사이는 **영어를 거쳐 두 번** 번역해야 하고, `en→th`는 매핑에는 있지만 HuggingFace에 그 모델이 **존재하지 않아** 502다. 즉 오늘 태국어는 어느 경로로도 안 된다. 4-2의 "언어별 1회"는 실제로는 **비영어 언어별 2회(피벗)** 이고, 그 비용이 7-3절의 숫자다.

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
| M1 | 메시지 수신 지연 p50/p99 — D1 이전(`5aeab94`) vs 이후 | D1의 대가가 얼마인가 | **완료 (2026-09-14)** — 7-1절. 정속 30 msg/s에서 **p50 +5~7ms, p99 +70~90ms**. D1 유지. 이전 코드의 실제 결함은 1절이 적은 큐 유실이 아니라 **상한 없는 저장 스레드의 커넥션 풀 고갈**이었다 |
| M2 | 저장 p99 — 번역 동기 vs 비동기(D3) | 동기로 붙였으면 얼마나 나빴을 것인가 | D3의 근거 |
| M3 | 동시 전송 N건에서 커밋 순서 ↔ `message_id` 역전 빈도 | 5-4의 구멍이 실재하는가 | **완료 (2026-09-13)** — 실재한다. 5-4의 표. D7·D8 확정 |
| M4 | 동시 전송 후 `last_message_seq`가 실제 건수와 일치하는가 | P2-12-4가 재현되는가 | M3의 방 시퀀스 변형이 사실상 이것이다 — 3,000건에서 유실 0이면 seq도 빠짐없다. 별도 측정은 하지 않는다 |
| M5 | 세션 1,000개 · 10명 방에서 팬아웃 지연 | 300 push/s의 무릎이 어디인가 | 2차 전파 방식 |
| M6 | 연결을 끊고 재접속했을 때 유실 건수 — 커서 전/후 | P2-12-5 | **완료 (2026-09-14)** — 7-2절. 끊긴 사이 40건: 따라잡기 없는 수신자 **40건 유실**, `after?seq=` + `messageId` 중복 제거 **0건**. 겹침 창의 중복 11건이 실제로 생겨 걸러졌다 |
| M7 | FastAPI 번역 **건당 지연과 동시 처리량** — D3를 붙이기 전에 그 서버가 무엇인지 | 4-2의 10~30 req/s를 받을 수 있는 서버인가 | **완료 (2026-09-16)** — 7-3절. i3 2코어 **1.9초·0.7 req/s** → `m7i.2xlarge` **0.28초·4.6 req/s** → 같은 모델을 CTranslate2 int8로 **34ms·31 req/s**(7-3-1). 병목은 코어가 아니라 엔진. 팀원 서버가 엔진을 바꾸면 한 대로 피크를 받는다 |

**M3 조건** (재현용): 로컬 Windows 10, Docker Desktop, Testcontainers `pgvector/pgvector:pg17`(PostgreSQL 17.11),
`lock_timeout=5s`. raw JDBC, 스크래치 DB에 운영 Flyway 마이그레이션 적용. 발신자 10/30/60 × 50건, 같은 방.
리더는 별도 커넥션에서 sleep 없이 `WHERE room_id=? AND key > cursor ORDER BY key` 폴링, `cursor = max(seen)`.
방 시퀀스 변형은 스크래치 DB에만 `chat_rooms.last_message_seq`·`chat_messages.room_seq`를 더해 돌렸다(V6 예정).

밀어 올리는 상한은 가정치의 10배(300 msg/s)다. "300에서도 멀쩡하다"가 나오면 그대로 적는다 — 근태에서 "3 TPS에 분산락이 필요한가"에 "처리량 때문이 아니다"로 답한 것과 같은 결론이 될 수 있고, 그것도 결론이다.

### 7-1. M1 결과 — D1의 대가는 p99 +70~90ms, 그리고 이전 코드에서 발견한 것 (2026-09-14)

`ChatLatencyMeasurementTest`. 발신자 1, 수신자 9, 같은 방. 지연 = 수신자 STOMP 핸들러의 `nanoTime` − 발신 직전 `nanoTime`(같은 JVM). 표본 = 9 × 300. 워밍업 30건은 버린다.
같은 파일을 D1 이전 커밋 `5aeab94`의 워크트리에 복사해 돌린 것이 "이전"이다.

| 조건 | 코드 | 풀 | p50 | p95 | p99 | max | 300건 소요 |
|---|---|---|---|---|---|---|---|
| 정속 30/s | 이전 (출하 상태) | 10 | 726 ~ 2,764 | 1,038 ~ 6,459 | 1,119 ~ 6,630 | 1,297 ~ 6,788 | 11.4 ~ 20.8s |
| 정속 30/s | 이전 | **100** | 22 | 108 | 197 | 291 | 13.2s |
| 정속 30/s | **D1 이후** | 10 | 27 (콜드 72) | 166 (307) | 289 (404) | 468 (710) | 10.5s |
| 정속 30/s | D1 이후 | 100 | 29 | 141 | 269 | 283 | 10.6s |
| 버스트 300 | 이전 (출하 상태) | 10 | 4,359 ~ 6,807 | 8,964 ~ 11,271 | 9,464 ~ 11,615 | 9,524 ~ 11,668 | 9.6 ~ 11.8s |
| 버스트 300 | 이전 | 100 | 9,502 | 14,861 | 15,268 | 15,373 | 15.7s |
| 버스트 300 | **D1 이후** | 10 | 3,529 ~ 3,769 | 6,455 ~ 6,685 | 6,755 ~ 6,890 | 6,824 ~ 6,963 | 6.9 ~ 7.1s |
| 버스트 300 | D1 이후 | 100 | 3,287 | 6,290 | 6,533 | 6,573 | 6.6s |

단위 ms. "~"는 두 번 실행의 범위. "콜드"는 Docker 재시작 직후 첫 실행값 — 그 뒤는 안정됐다. 풀은 `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE`로만 바꿨다.

**읽는 법.**

1. **D1의 대가는 작다.** 비교는 "이전 · 풀 100" 대 "이후"다 — 이전 코드의 풀 고갈(아래 3)을 걷어내야 순서를 뒤집은 값만 남는다. 정속 30 msg/s에서 p50 22 → 27~29ms, p99 197 → 269~289ms. **커밋을 기다리는 값이 p99 기준 70~90ms다.** 정합성(저장 안 된 것은 보이지 않는다)의 값으로 받아들인다. D1 유지.

2. **버스트에서는 D1이 더 빠르다.** 300건을 간격 없이 넣으면 이후 코드가 6.6~7.1초, 이전 코드는 9.6~15.7초다. 방 행 락(`nextRoomSeq`)이 저장을 직렬화하는 것이 저장 트랜잭션 300개가 한꺼번에 DB에 부딪히는 것보다 낫다. 직렬 구간은 약 23ms/건 — **한 방의 상한이 약 43 msg/s**다. 가정표 3-3의 피크 30 msg/s는 전체 인바운드이고 방 하나가 아니므로 여유는 있지만, 이 값이 단일 방의 무릎이다.

3. **이전 코드에서 발견한 것 — 1절의 진단이 틀렸다.** "출하 상태" 행이 초 단위인 이유는 전파가 늦어서가 아니다. `@Async saveMessage`가 `messageExecutor`를 쓰지 않았다 — 로그의 저장 스레드 이름이 `SimpleAsyncTaskExecutor-1 … -631`이고 `AsyncMsg-`는 0건이다. 저장 631건에 스레드 631개. 각 스레드가 `@Transactional`로 커넥션을 잡으니 풀 10개가 곧 고갈되고, **인바운드 스레드의 `validChatRoomMember`·`getParticipantsIds`가 커넥션을 기다리는 시간이 곧 수신 지연**이 됐다. 풀을 100으로 늘리자 726~2,764ms가 22ms로 떨어진 것이 증거다. 실행마다 값이 4배씩 흔들린 것도 같은 원인이다 — 상한 없는 스레드끼리의 경합은 재현되지 않는다.
   원인은 두 줄이다: `@Async`에 실행기 이름이 없고, `messageExecutor` 빈의 선언 타입이 `Executor`라 Spring이 `TaskExecutor` 후보로 잡지 못했다. 1절이 걱정한 "큐 10,000이 차면 유실"은 **일어날 수 없는 결함**이었다 — 그 큐에 아무것도 들어간 적이 없다. D1이 `@Async`를 뗀 것이 이 결함도 함께 없앴고, 그래서 출하 상태 대비 100배는 D1의 공이 아니다. D3(번역)에서 `@Async("messageExecutor")`처럼 **이름으로** 묶어야 같은 일이 반복되지 않는다.

**조건** (재현용): 로컬 Windows 10, i3-6100(4스레드), JDK 21(toolchain), Docker Desktop, Testcontainers `pgvector/pgvector:pg17` + Redis. 클라이언트와 서버가 같은 JVM·같은 머신이라 네트워크 왕복은 0에 가깝다 — **절대값이 아니라 전/후의 차이**를 보는 측정이다. `@SpringBootTest(RANDOM_PORT)`, 내장 simple broker. 구독 완료는 RECEIPT 대신 프로브 메시지 도착으로 확인한다(simple broker는 RECEIPT를 만들지 않는다).

### 7-2. M6 결과 — 따라잡기 프로토콜은 유실 0, 그리고 클라이언트를 실제로 만들었다 (2026-09-14)

`ChatReconnectCatchUpMeasurementTest`. D8이 서버에 `after?seq=`를 줬다는 것과, 클라이언트 프로토콜이 유실 0을 **만든다**는 것은 다른 말이라 후자를 잰다. 시나리오: 수신자가 구독한 채 5건 → 수신자 끊김 → 그 사이 40건(DB에 40건 커밋될 때까지 기다린다) → 재접속·구독 → **구독 직후** 10건을 더 보내 따라잡기 응답과 실시간 전파가 겹치는 창을 만든다 → `after?seq=커서`를 `limit=15`로 끝까지 이어 부른다.

| 수신자 | 받은 것 / 보낸 것 | 유실 |
|---|---|---|
| 따라잡기 없음 (실시간 전파만) | 15 / 55 | **40 — 끊긴 사이 전부** |
| 커서 따라잡기 + `messageId` 중복 제거 | 55 / 55 | **0**, `roomSeq` 1..57 구멍 없음 |

따라잡기 응답 4페이지(`hasNext` 이어 부르기가 실제로 돌았다), 새로 40건 + 이미 있던 것 11건 — 그 11건이 겹침 창에서 실시간으로 먼저 도착한 것이다. **중복이 0이면 겹침 창을 못 만든 것이지 프로토콜이 좋은 것이 아니다.** 겹치게 만들고 걸러지는 것을 봐야 한다.

**따라오는 것 셋.**

1. **`Slice`의 JSON에 `hasNext`가 없다.** Jackson은 `is*`/`get*`만 내보내서 `last`(= `!hasNext`)가 온다. 11-1 표는 "`hasNext`로 이어 부른다"고 적었고 컨트롤러 설명도 그렇다 — 클라이언트가 그 이름을 보면 이어 부르기가 **영원히 안 돈다.** 계약은 `last === false`다. `chat_test.html`은 둘 다 본다.
2. **첫 판은 6건이 어긋났다.** 끊긴 사이 40건을 보내고 300ms 잔 뒤 재접속했더니 순진한 수신자의 유실이 34였다 — 마지막 6건은 재접속 뒤 전파됐다. 7-1이 잰 대로 한 방의 저장은 약 23ms/건으로 직렬화되니 40건이면 900ms다. 시간을 추측하는 대신 DB에 40건이 보일 때까지 폴링하게 고쳤다. M1의 숫자가 M6의 조건을 정했다.
3. **`chat_test.html`이 프로토콜의 첫 클라이언트다.** 방별 `lastSeq`(localStorage, 새로고침에도 이어진다), 재접속 시 `after?seq=` 끝까지, `messageId`로 중복 제거, `clientMsgId` 낙관적 전송 + `/sub/user/{id}/errors` 실패 표시 + 재접속 뒤 같은 키로 재전송, 화면에 보인 최대 seq를 2초 디바운스·방 이탈·창 닫기에 `PATCH .../read`. 재접속은 첫 접속과 **같은 함수**다 — 재접속이 특별한 경로가 되면 그 경로만 안 테스트된다. "끊김" 버튼은 소켓만 닫아 Wi-Fi가 죽은 것과 같은 조건을 만든다.

**heartbeat (N5, P2-12-5의 남은 몫).** 없었다 — `enableSimpleBroker`만 부르면 `0,0`으로 협상되어 어느 쪽도 보내지 않는다. 그러면 Wi-Fi가 조용히 죽었을 때 TCP는 살아 있는 것처럼 보이고 클라이언트는 `onclose`를 받지 못한다. **끊긴 줄을 모르면 따라잡기가 시작될 일이 없다** — `after?seq=`는 부를 계기가 있어야 한다. 서버 10초/10초(`messageBrokerTaskScheduler`), stomp.js 기본값과 같아 `10,10`으로 협상된다. 3주기(30초) 안에 양쪽이 알아채고 닫는다. nginx `/ws`의 `proxy_read_timeout`은 그 덕에 3600초 → 60초.

### 7-3. M7 결과 — 로컬 i3에선 건당 2초·0.7 req/s, AWS 8 vCPU에선 0.28초·4.6 req/s, CTranslate2면 34ms·31 req/s (2026-09-16)

D3를 설계하기 전에 4-1이 "건당 수백 ms라고 가정해도"라고 적은 그 서버를 실제로 재봤다. 팀원 저장소 `DOCKin-project/DOCKin-aiserver`(`eaf9a28`)를 이 머신에 그대로 띄우고 `/api/translate`를 Spring이 부르는 꼴로 불렀다. **이 서버를 잰 것은 처음이다** — 지금까지 FastAPI는 사용자가 버튼을 누를 때만 불렸고(챗봇 800건/일 ≈ 0.02 req/s), 그 지연을 누가 잰 적이 없다.

**무엇이 돌고 있나.** 외부 API가 아니다. `transformers` MarianMT(`Helsinki-NLP/opus-mt-*`)를 **CPU에서** `num_beams=4`, `max_length=256`으로 돌린다. 요청은 `asyncio.to_thread`로 스레드풀에 넘어가고, torch는 물리 코어 수(여기서 2)만큼 intra-op 스레드를 쓴다. 첫 호출은 모델을 내려받는다(쌍당 약 300MB, 30초). `/api/translate`는 `title`이 **필수**라 본문만 번역하고 싶어도 제목까지 `generate`가 두 번 돈다.

| 재는 것 | 조건 | p50 | p95 | p99 |
|---|---|---|---|---|
| ko→en 직접 | 순차 40건, 채팅 길이(9~28자) | **1,868 ms** | 4,330 | 5,683 |
| ko→en→vi 피벗 (2호출) | 순차 20건 | **3,892 ms** | 7,155 | 7,773 |

| 동시성 (ko→en, 총 40건) | p50 | p99 | 처리량 |
|---|---|---|---|
| 1 | 1,303 ms | 3,133 | **0.70 req/s** |
| 2 | 7,773 | 50,101 | 0.18 |
| 4 | 5,343 | 11,384 | 0.68 |
| 8 | 15,709 | 27,855 | 0.52 |

**처리량이 동시성과 무관하게 0.7 req/s에 묶여 있다.** 코어 2개를 요청 하나가 다 쓰니 둘을 동시에 넣으면 서로 밀어내고(동시 2에서 p99 50초는 그 스래싱이다) 총량은 같다. 추론 서버의 처리량은 코어 수로 결정되고, 커넥션을 늘려서 되는 것이 아니다. 4-2가 잡은 피크 **10~30 req/s는 이 값의 15~40배**다. 팀원 배포 머신이 이 i3보다 빠르더라도 코어 4~8개 CPU면 자릿수가 하나 오르는 데 그친다 — 30 req/s는 GPU나 서버 수십 대의 숫자다.

**비용을 분해하면** (모델 직접 호출, `bench_inprocess.py`):

| 항목 | p50 | 뜻 |
|---|---|---|
| 제목 `"-"` 하나 번역 (beam 4) | **980 ms** | 채팅은 제목이 없는데 계약이 강제해 **호출의 40%가 버려진다.** `"-"`는 `"- I'm sorry."`로 번역된다 |
| 본문 beam 4 (현재) | 1,412 ms | |
| 본문 beam 1 (greedy) | **795 ms** | 절반. 그러나 "3번 도크 용접"이 "third door"가 된다 — 품질을 내주고 얻는 속도다 |
| 8문장 배치 1회 | 건당 1,628 ms | 코어가 2개라 배치가 병렬이 안 된다. **배치는 이 서버에선 이득이 없다** |

**품질 표본 — 피벗이 뜻을 바꾼다.** "오늘 3번 도크 용접 작업 오전 10시에 시작합니다" → en "Today's third dock welding starts at 10:00 a.m." → vi "Hôm nay là chuyến bay thứ ba lúc 10 giờ sáng" (**오늘 세 번째 비행기는 10시**). "점심 후에 블록 조립 이어서 하겠습니다" → vi "…tổ chức hội nghị sau bữa trưa" (**점심 후 회의를 연다**). "크레인 작업 중이니 아래로 지나가지 마세요"는 두 단계에서 "크레인" 자체가 사라졌다. 안전 지시가 이렇게 바뀌면 번역이 없는 것보다 나쁘다 — 4절이 원문을 지우지 않고 **덧붙이기만** 하는 것이 기능이 아니라 안전 요건인 이유가 숫자 밖에서도 나왔다.

**조건** (재현용): 로컬 Windows 10, **i3-6100(2코어 4스레드)**, 34GB RAM, Python 3.12, torch 2.14 CPU(`torch.get_num_threads()=2`), transformers 5.17, uvicorn 단일 프로세스. 모델 로드 뒤 워밍업 5건. 클라이언트는 같은 머신의 `httpx`라 네트워크 왕복은 0에 가깝다 — **절대값은 이 머신의 것이고, 보는 것은 자릿수와 "동시성으로 늘지 않는다"는 모양**이다. 스크립트와 원 출력은 `measure/fastapi-translate/`.

**D3에 미치는 것.**

1. **지금 서버에 4-2의 자동 번역을 그대로 붙이면 안 된다.** 피크 30 msg/s에서 비영어 수신자가 있는 메시지가 3분의 1이라 쳐도 초당 10건, 피벗이면 20호출이 들어오는데 서버는 0.7건을 처리한다. 큐가 1분에 1,000건씩 쌓이고 60초 타임아웃으로 대부분 죽는다. 4-3의 "실패는 열화"가 "거의 전부 실패"가 된다.
2. **물러날 자리는 셋이고 값이 다르다.** ① 요청한 사람에게만(수동 번역 버튼) — 호출이 사용자 행동 수로 떨어지고 지금 작업일지 번역과 같은 모델, `AiQuota`에 종류 하나 더하면 끝. ② 방 설정으로 켠 방에만 자동 — 혼합 언어 방이 실제로 몇 개인지에 달렸다[측정 필요]. ③ 서버를 바꾼다 — GPU, 또는 `title` 선택화(즉시 40%)와 greedy(품질 대가로 절반) 같은 팀원 쪽 수정. ③은 우리 저장소의 일이 아니라 **요청**이다.
3. **어느 쪽이든 먼저 팀원에게 전할 것 넷**: `title` 필수 해제, `ko↔vi`·`ko↔zh` 직접 모델 또는 서버 안 피벗, `en→th` 모델 부재, 워커 수와 배포 머신 코어 수. 이 넷의 답이 오기 전엔 D3 구현을 시작하지 않는다 — 4-2의 호출 수 모델 자체가 "서버 안에서 피벗하는가"에 따라 두 배 차이 난다.

#### 7-3-1. 같은 날 AWS에서 다시 잰다 — 결론이 바뀐다: 문제는 코어가 아니라 엔진이었다

i3 결과를 보고 "코어를 4배 주면 4배인가"를 확인하러 이전 밤들과 같은 **`m7i.2xlarge`(8 vCPU / 4물리코어, Xeon Platinum 8488C Sapphire Rapids, AVX-512·AMX)** 에 팀원 서버를 **그대로** 올렸다(같은 커밋, 같은 uvicorn 단일 프로세스, torch가 고른 스레드 4). 스크립트는 같다.

| `/api/translate` (transformers, 팀원 코드 그대로) | i3 2코어 | m7i.2xlarge | 배 |
|---|---|---|---|
| ko→en p50 | 1,868 ms | **279 ms** | 6.7 |
| ko→en→vi 피벗 p50 | 3,892 ms | **513 ms** | 7.6 |
| 처리량 최대 (동시 4) | 0.70 req/s | **4.63 req/s** | 6.6 |
| 동시 8 p99 | 27,855 ms | 2,207 ms | |

코어 2배에 6~7배가 나왔다 — 코어 수만이 아니라 세대 차이(AVX-512, 메모리 대역)가 같이 들어간 값이다. **처리량은 동시 4(물리 코어 수)에서 포화**하고 8을 넣으면 지연만 두 배가 된다 — 모양은 i3와 같고 위치만 다르다. 이 값이면 4-2의 피크 10~30 req/s에 **3~7대**(10/4.6 = 2.2, 30/4.6 = 6.5를 올림)가 필요하다. 아직 부족하지만 자릿수는 맞아졌다.

**그리고 엔진을 바꿔 봤다.** 팀원 서버는 `faster-whisper` 때문에 **CTranslate2를 이미 의존성으로 갖고 있다.** 같은 `opus-mt-ko-en`을 CTranslate2 int8로 변환해(`ct2-transformers-converter`, 한 줄) 같은 문장을 넣었다.

| 같은 모델, 같은 문장, 같은 m7i.2xlarge | 건당 p50 | 처리량 |
|---|---|---|
| transformers beam 4 (지금) | 178 ms | 동시 4에서 ~4.6 req/s (API 기준) |
| transformers greedy | 96 ms | — "도크"가 "door"가 된다 |
| **CTranslate2 int8 beam 4** | **34 ms** | **`inter_threads=2`에서 31 req/s**, 1에서 18, 4에서 27 |
| CTranslate2 int8 greedy | 23 ms | |

**5배 빠르고 출력은 표본 4문장 전부 글자 하나까지 같다.** int8 양자화가 품질을 바꾸지 않았다는 뜻은 아니고(표본 4개다), 이 문장들에선 같았다는 것이다. 처리량은 **한 대에서 피크 가정 30 req/s에 닿는다 — 다만 이 31 req/s는 엔진에 문장을 직접 넣은 값이고, FastAPI 요청 처리·직렬화·워커 구성을 거친 `/api/translate` 경로는 엔진을 바꾼 뒤 다시 재야 한다 [실측 필요].** 배치도 이 코어 수에선 산다 — 8문장 한 번에 건당 63ms(transformers 기준), 4-2의 "언어별 1회"를 방 단위 배치로 묶으면 더 내려간다.

**조건** (재현용): `ap-northeast-2c`, `m7i.2xlarge`, Amazon Linux 2023, Python 3.12(uv), torch 2.14 CPU, transformers 5.17, ctranslate2 4.8.2, uvicorn 단일 프로세스, 팀원 저장소 `eaf9a28`. 클라이언트는 같은 인스턴스의 `httpx`. 인스턴스 가동 약 35분 ≈ $0.3. 원 출력 `measure/fastapi-translate/m7-20260916-aws/`, 설치 절차 `remote_setup.sh`, 엔진 비교 `bench_ct2.py`.

**7-3의 결론을 이렇게 고친다.**

1. ~~"D3는 이 서버로는 못 붙인다"~~ → **"이 코드 그대로는 못 붙이고, 엔진을 CTranslate2로 바꾸면 한 대로 된다."** i3 숫자는 호스트가 만든 것이었고, 서버급 CPU에서 진짜 병목은 추론 엔진이었다. 로컬 절대값으로 결론을 내리면 안 된다는 이 저장소의 규칙(AWS-MEASUREMENT-RESULTS 서문)이 여기서도 맞았다.
2. **팀원에게 보낼 요청의 우선순위가 바뀐다.** ① CTranslate2 int8로 추론(5배, 의존성 추가 없음, 코드 수십 줄) ② `title` 선택화(호출의 34~40%) ③ ko↔vi·zh 직접 모델 또는 서버 안 피벗 ④ en→th 부재. ①②만 해도 `/api/translate`는 건당 ~40ms, 한 대 30 req/s다.
3. **물러날 자리는 그대로 유효하다.** 팀원 서버가 바뀌기 전까지는 수동 번역(사용자 행동 수)이 맞고, 바뀐 뒤 자동(4-2)으로 간다. 관리형 API는 데이터 외부 전송 결정이 필요해 이 결과로는 굳이 갈 이유가 줄었다.
4. **오역은 엔진과 무관하다.** 피벗의 "용접→비행기", "조립→회의"는 m7i에서도 같은 출력이다. 속도가 해결하는 문제가 아니고, 원문 보존(4절)과 직접 모델(③)이 답이다.

#### 7-3-2. HTTP로도 30 req/s다 — 그리고 오역을 줄이는 모델은 그 1/7이다 (T2, 2026-09-16, 밤 6)

7-3-1의 31 req/s는 모델 직접 호출이었다. 그 엔진으로 만든 서버(`Khyojae/pylab` `translate/`, 같은 계약)를 같은 `m7i.2xlarge`에서 HTTP로 치면 **30.3 req/s, 건당 p50 73ms** — HTTP 계층은 처리량을 안 깎는다. ①의 근거는 그대로 선다.

같은 자리에서 ③의 후보를 하나 재봤다. 피벗 오역("용접→비행기", "조립→회의")을 절반 넘게 없애는 NLLB-600M(pylab T3, ko→vi 뜻 보존 31 vs 16)은 **건당 291ms, 한 대 4.2 req/s** — opus 피벗 ko→vi(144ms, 12.5~17 req/s)의 1/3~1/4. 4-2의 피크 30 req/s를 NLLB로 받으려면 7대다. 그래서 4-2의 "30 req/s"가 가정인 채로는 모델을 못 고른다 — **자동 번역이 실제로 초당 몇 건인지**(비영어 수신자가 있는 메시지 비율 × 피크)가 D3의 다음 숫자다. 모델 결정(opus+사전 / NLLB / 쌍별 섞기)은 pylab ADR-0001 7·8절에서, 이 ADR은 그 결과를 받는다.

> **M2는 이 숫자로 답이 나왔다.** "동기로 붙였으면 얼마나 나빴을 것인가" — 저장 p99 40ms 위에 2~4초가 얹힌다. 재지 않아도 되는 크기라 M2는 별도로 돌리지 않는다.

## 8. FCM은 같은 이벤트의 다른 소비자다 (D10)

3절의 AFTER_COMMIT 리스너에 소비자를 하나 더 둔다. 접속 상태는 `Presence`(Redis `presence:{userId}`, 2026-09-16 — 전에는 `StompHandler`의 static Map이었고 아무도 읽지 않았다)가 들고 있으므로 `presence.offlineAmong(방 멤버)`로 **접속 중이 아닌 멤버에게만** 푸시한다. 접속 중인 사람은 WebSocket으로 받았다. Redis가 죽어 있으면 전원이 오프라인으로 나와 전원에게 푸시된다 — 중복이지 유실은 아니다(ADR-0009 2절).

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

**V7 (2026-09-14)**: `chat_members.last_read_time` 제거. 11-1의 읽음 API가 들어가 읽는 곳이 0이 됐고, 쓰는 곳은 `markRead`의 습관성 `NOW()` 하나였다. 쓰기만 남은 컬럼은 아무도 값을 확인하지 않는 컬럼이라 내렸다. 참조 객체가 있으면 실패하도록 `DO` 블록을 앞에 뒀다. P2-12-2·3이 스키마 차원에서 닫혔다.

## 11. 계약과 비용

### 11-1. 입출력 — 바뀌는 것 (2026-09-14 구현. 아래 표의 '설계' 열이 현재 계약이다)

| 경로 | 지금 | 설계 |
|---|---|---|
| STOMP `/pub/chat/message` (입력) | `{roomId, senderId, content, messageType, fileUrl}` | + `clientMsgId: UUID` (D9). `senderId`는 세션이 덮어쓰므로 입력에서 뺀다 |
| STOMP `/sub/chat/room/{id}` (1차 전파) | 요청 DTO 그대로 | `{messageId, roomSeq, clientMsgId, senderId, content, messageType, fileUrl, languageCode, sentAt}` — 전부 DB가 준 값 |
| STOMP 2차 전파 (번역) | 없음 | `{messageId, languageCode, translated}` — 수신자 언어별 1회 |
| `/sub/user/{id}/errors` (발신 실패) | 로그만 | 보낸 사람에게만 `{clientMsgId, code, message}`. `ERROR` 프레임이 아닌 이유는 3절 |
| `GET /room/{id}/messages` (위로 스크롤) | `lastMessageId` 커서, `messageId DESC` | `beforeSeq` 커서, `roomSeq DESC`. `lastMessageId`는 옛 커서로 받아 `roomSeq`로 옮겨 쓴다(deprecated) |
| `GET /room/{id}/messages/after?seq=&limit=` (따라잡기) | 없음 — P2-12-5의 뿌리 | `roomSeq > seq ASC`, `Slice`의 **`last === false`** 이면 이어 부른다(JSON에 `hasNext`는 없다 — 7-2절). limit 1~500, 기본 100 |
| 읽음 처리 | `GET /room/{id}`의 **부수효과**로 `last_read_time = now()`, 저장 시 발신자 `NOW()` | `PATCH /room/{id}/read {upToSeq}` → `last_read_seq = GREATEST(last_read_seq, :upToSeq)`, 204. 발신자는 저장이 방금 발급한 seq로 같은 메서드를 부른다. 상세 조회는 아무것도 바꾸지 않는다 |
| 방 목록 | 정렬 없음 (P2-12-8), 방마다 멤버 조회 + COUNT (P2-12-1) | 조인 한 번: 안읽음 = `last_message_seq − last_read_seq`. 정렬은 **`last_message_at DESC`** — seq는 방 *안*의 번호라 방끼리 비교할 수 없다. 그 시각은 seq 발급과 같은 UPDATE·같은 락에서 찍히므로 P2-12-4의 경합이 없다. 참가자는 `IN` 한 번. 쿼리 3개 고정 |

클라이언트가 지킬 것은 셋이다 — 재접속 시 `after?seq=마지막으로 받은 roomSeq`로 따라잡기(`last === false`면 이어서), `clientMsgId`로 재전송 중복 제거, 화면에 보인 최대 `roomSeq`로 읽음 보고(메시지마다가 아니라 방을 벗어날 때나 몇 초 디바운스). **`chat_test.html`이 셋을 전부 한다(2026-09-14, 7-2절)** — 프론트가 참고할 첫 구현이다.

**11-1의 첫 판과 달라진 것 하나.** 방 목록 정렬을 `last_message_seq DESC`로 적었는데 틀렸다. seq는 방마다 1부터 시작하는 번호라 메시지가 많은 방이 항상 위로 온다. 방 사이의 "최근"은 시각이어야 하고, 그 시각이 믿을 만해진 이유가 D8이다 — 같은 락 안에서만 바뀐다. 검증: `ChatReadCatchUpTest`.

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
