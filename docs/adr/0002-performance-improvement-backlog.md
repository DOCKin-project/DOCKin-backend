# ADR-0002: 백엔드 성능 개선 후보 검토 (JVM / 네트워크 / 캐시 / 쿼리)

- 상태: 2-1(비동기 병렬 외부 호출) 구현 및 실측 완료, 나머지 후보는 검토만 완료(구현 전)
- 작성 목적: "포폴에 넣을 만한 성능 개선 소재"를 코드 근거 없이 갖다 붙이지 않기 위해, 실제 코드에서 확인되는 문제와 아직 근거가 없는 항목을 구분해서 기록한다. ADR-0001과 동일하게, 숫자를 지어내지 않는다는 원칙을 따른다.

## 1. 검토 배경

JVM GC/힙 튜닝, tcpdump, Redis 캐시, 비동기/동기 외부 호출, 로컬 캐시, MySQL EXPLAIN 쿼리 개선, 대용량 트래픽 대응이 백엔드 포트폴리오 소재 후보로 거론되었다. 이 중 실제로 코드/인프라에 문제의 흔적이 있는 것과, 지금 붙이면 "실측 없이 튜닝했다"는 인상만 주는 것을 나눈다.

## 2. 후보별 검토

### 2-1. 비동기 병렬 외부 호출 — 구현 및 실측 완료

`fastApiService.saveTranslateLog()`가 제목/본문 번역을 순차적으로 `.block()` 두 번 호출하던 것을, `Mono.zip()`으로 동시에 보내고 한 번만 block하도록 수정했다.

```java
// 변경 전
var titleMap = fastApiWebClient.post()...bodyToMono(Map.class).block();
var contentMap = fastApiWebClient.post()...bodyToMono(Map.class).block();

// 변경 후
var zipped = Mono.zip(titleMono, contentMono).block();
```

**실측 결과** (`TranslateParallelBenchmarkTest`, JDK `com.sun.net.httpserver.HttpServer`로 300ms 인위 지연 스텁 서버를 로컬에 띄워 측정):

첫 측정에서는 순차 2540ms vs 병렬 613ms(약 4.1배)로 나왔으나, 이는 대부분 Reactor Netty 커넥션 초기화 비용이 순차 버전에서 두 번 발생한 콜드 스타트 효과였다. 스텁 서버에 커넥션 풀을 미리 데우는 워밍업 호출 5회를 추가하고 재측정하니:

| 방식 | 소요 시간(워밍업 후) |
|---|---|
| 순차 `.block()` 2회 (변경 전) | 622ms |
| `Mono.zip()` 병렬화 (변경 후) | 320ms |

약 1.9배(300ms 지연 두 개를 겹치느냐 아니냐 차이) — 이론적으로 기대한 수치와 일치한다. 참고로 첫 측정 때는 스텁 서버(`HttpServer`)에 `setExecutor()`를 지정하지 않아 서버가 요청을 한 번에 하나씩만 처리하고 있었고(기본 동작), 이 상태에서는 클라이언트가 아무리 병렬로 요청을 보내도 서버 쪽에서 직렬화되어 병렬화 효과가 사라졌다 — `Executors.newFixedThreadPool(4)`로 서버를 동시 처리 가능하게 고친 뒤에야 위 수치가 나왔다. 이 수치는 로컬 스텁 서버 조건의 결과이며, 실제 FastAPI 서버 환경에서는 차이가 다를 수 있다(ADR-0001과 동일하게 측정 조건을 명시해둔다).

### 2-2. MySQL 쿼리/EXPLAIN 개선 — 근거 있음, 우선순위 2

- **N+1**: `ChatRoomService.getChatRooms()`가 페이지 안의 채팅방 하나마다 `findByChatRoomsRoomIdAndMemberUserId`, `countByChatRoomsRoomIdAndCreatedAtAfter` 두 쿼리를 추가로 던진다. 방 20개면 최대 41개 쿼리. `EXPLAIN`으로 실행계획을 보여주기 전에, 페치 조인이나 배치 쿼리로 먼저 줄여야 하는 실제 문제.
- **인덱스 못 타는 LIKE 검색**: `Work_logsRepository.searchWorkLogs`가 `title LIKE %:keyword% OR logText LIKE %:keyword%`로 양쪽 와일드카드를 쓴다. B-Tree 인덱스는 앞쪽이 고정되지 않으면 못 타므로 `EXPLAIN` 시 `type=ALL`(풀스캔) 가능성이 높다. 실제로 `EXPLAIN` 찍어서 확인하고, 규모가 커지면 Full-Text 인덱스 전환 여부를 판단하는 흐름이 자연스럽다.

### 2-3. JVM GC/힙 튜닝 — 근거는 있으나 순서상 보류, 우선순위 3

`compose.yaml`에 이미 `JAVA_OPTS=-Xmx400M -Xms256M`, 컨테이너 메모리 512M 제한이 걸려 있다. 즉 "메모리 제약이 있는 환경에서 GC를 어떻게 골랐는가"라는 소재 자체는 근거가 있다. 다만 지금은 GC 로그나 `jstat` 같은 실측 도구가 붙어있지 않은 상태라, 이 상태에서 바로 "GC 튜닝했다"고 쓰면 ADR-0001에서 경계한 "숫자 없이 결론부터 낸" 패턴이 된다. 순서: 먼저 GC 로그/모니터링을 붙이고 실측 → 그 다음 튜닝 여부를 판단.

### 2-4. 캐시 (로컬 vs Redis) — 조건부, 우선순위 3

`equipment`, `checklists`, `safety_courses`처럼 자주 안 바뀌는 참조 데이터를 매 요청마다 DB에서 읽는 지점(`createWorklog`의 `equipmentRepository.findById` 등)이 있어, 캐시 자체는 근거가 있다. 다만 `compose.yaml`을 보면 `dockin-app`이 단일 인스턴스(`container_name: dockin-app-1`, 인스턴스 1개)라 **다중 인스턴스 캐시 정합성 문제를 다루는 Redis 캐시는 아직 정당화가 안 된다.** 지금 규모에서는 Caffeine 같은 로컬 캐시가 더 맞는 선택이고, Redis는 현재 출근 분산락 전용으로 메모리 100M까지 제한해둔 상태라 캐시 용도로 확장하려면 그 결정부터 다시 검토해야 한다.

### 2-5. tcpdump — 근거 없음, 보류

현재 구체적으로 관찰된 네트워크 레벨 장애(타임아웃, 커넥션 리셋, 재전송)가 없다. tcpdump는 "이런 증상이 있었는데 패킷 레벨에서 원인을 봤다"는 실제 사건이 있어야 설득력이 생기는 도구라, 지금 상태에서 미리 붙이면 소재를 위한 소재가 된다. 실제 이슈가 생기면 그때 쓴다.

### 2-6. 대용량 트래픽 대응 — ADR-0001에 이미 포함, 신규 소재 아님

k6 부하 테스트는 ADR-0001에서 이미 계획된 항목(3번 표, 아직 실측 전)이다. 여기서 별도로 다루지 않고 ADR-0001 실행으로 흡수한다.

## 3. Real MySQL 책 매핑

| Real MySQL 주제 | DOCKin 내 대응 | 상태 |
|---|---|---|
| 잠금(락) / MVCC / 트랜잭션 격리수준 | 출근 체크 동시성 (ADR-0001) | 설계 완료, 실측 대기 |
| 인덱스와 실행계획(EXPLAIN) | 채팅 목록 N+1, work_logs LIKE 검색 | 미착수 (2-2) |
| 파티셔닝 / 버퍼풀 | 없음 — 이건 Shadowfit(운동 기록 시계열) 쪽 소재이지 DOCKin 소재가 아님 | 해당 없음 |
| 복제 / 샤딩 | 없음 | 현재 규모에서 범위 밖 |

DOCKin과 Shadowfit의 Real MySQL 소재가 겹치지 않는다는 점을 확인해뒀다 — 두 프로젝트를 같이 쓸 때 "같은 얘기 반복"으로 보이지 않는다.

## 4. 결론 및 우선순위

1. **비동기 병렬 호출** (`saveTranslateLog` `Mono.zip` 병렬화) — 구현 및 실측 완료(2-1 참고)
2. **쿼리 개선** — 채팅 목록 N+1 해결 + `work_logs` 검색 `EXPLAIN` 확인 후 조치
3. **JVM GC** — 모니터링부터 붙이고 실측 후 튜닝 여부 판단
4. **로컬 캐시(Caffeine)** — 참조 데이터 대상으로 검토
5. 보류: tcpdump, Redis 캐시 확장, 대용량 트래픽(ADR-0001로 흡수)
