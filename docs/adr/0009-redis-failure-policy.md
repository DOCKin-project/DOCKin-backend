# ADR-0009: Redis — 용도 여섯, 장애 시 정책은 셋, 고르는 질문은 하나

- 상태: **결정.** 2026-09-16. 새 결정은 없고 이미 코드에 흩어져 있던 셋을 한 표로 모았다. 6절(다음 용도)만 미리 정한 것이었고, 그중 presence와 로그인 시도 제한은 같은 날 코드가 됐다(1·2절에 다섯째·여섯째 행)
- 대상 코드: `global/config/RedissonConfig`(측정: `RedisOpenPathLatencyMeasurementTest`), `attendance/service/AttendanceService`, `rag/service/IndexingService`, `global/security/jwt/JwtBlacklist`, `ai/quota/AiQuota`, `chat/presence/Presence`, `member/login/LoginAttempts`, `compose.yaml`(`dockin-redis` — AOF·maxmemory·`restart`)
- 관련 문서: `docs/adr/0001`(분산락 폴백), `docs/WORK-BACKLOG.md` P2-5(블랙리스트 이관)·P2-19(AI 한도, Redis 영속성), `docs/adr/0004` 3절(다중 인스턴스 시 Redis Pub/Sub), `docs/PRODUCTION-READINESS.md` F2(폴백)
- 작성 목적: Redis가 죽었을 때 무엇이 어떻게 되는지가 **클래스 넷의 주석에 하나씩** 있고, 서로 반대 결정(열림/닫힘)을 했다. 이유는 다 있지만 나란히 놓인 적이 없어서, 다섯 번째 용도가 붙을 때 처음부터 다시 따지게 된다. 표 하나와 고르는 질문 하나를 남긴다. 숫자는 P2-19와 4절에서 실측한 것만 적는다.

---

## 1. Redis는 이 서비스에서 무엇인가

ADR-0001 시점엔 "출근 분산락 전용"이었다. 지금은 여섯이다.

| 용도 | 어디 | 키 | 산다 | 들어온 때 |
|---|---|---|---|---|
| 출근 분산락 | `AttendanceService` | `attendance:lock:{userId}:{날짜}` | 트랜잭션 동안(대기 3초·리스 3초) | ADR-0001 |
| 색인 단일 실행 락 | `IndexingService` | `rag:indexing:lock` | 색인 동안(워치독) | 밤 1 사고 뒤 |
| JWT 블랙리스트 | `JwtBlacklist` | `jwt:blacklist:{sha256}` | 토큰 만료까지 | P2-5, 2026-09-14 |
| AI 호출 한도 | `AiQuota` | `quota:ai:{종류}:{userId}:{날짜}` | 자정까지 | P2-19, 2026-09-16 |
| 채팅 접속 상태 | `Presence` | `presence:{userId}` = 세션 ID SET | TTL 30초, 인스턴스가 10초마다 갱신 | 2026-09-16 (6절에서 올라옴) |
| 로그인 시도 제한 | `LoginAttempts` | `login:fail:{사원번호}` | 첫 실패부터 10분(고정 창), 성공하면 삭제 | P2-18-12, 2026-09-16 (6절에서 올라옴) |

공통점 하나: **여섯 다 "트래픽 때문"이 아니다.** 락은 멱등성, 블랙리스트는 로그아웃의 실효성, 한도는 비용 상한, 접속 상태는 다중 인스턴스에서의 정확성(인스턴스별 Map은 남의 인스턴스 사용자를 모른다), 시도 제한은 재배포에 풀리면 안 되는 카운터(인메모리면 재배포가 곧 잠금 해제다)이다. `SERVICE-SCALE-ASSUMPTIONS.md`가 ADR-0001의 락을 "트래픽이 많아서"라고 설명하면 안 된다고 한 것이 나머지 셋에도 그대로다. 캐시는 없다 — 작업일지 목록은 사용자별·커서별이라 히트가 안 나고, 참조 데이터는 PK 조회라 캐시해도 줄 것이 없다(ADR-0002 2-4, P2-19).

**기동은 하드 의존성이다.** `RedissonConfig`가 빈 생성 시점에 실제로 접속하므로 Redis가 없으면 앱이 뜨지 않는다. `compose.yaml`의 `depends_on`이 `service_healthy`(`redis-cli ping`)인 것이 그래서다. 아래 2절은 전부 **뜬 뒤에** Redis가 죽는 경우다.

## 2. 장애 시 정책 — 한 표

| 용도 | Redis가 죽으면 | 열림/닫힘 | 열면(또는 닫으면) 무엇이 깨지나 | 마지막 방어선 | 검증 |
|---|---|---|---|---|---|
| 출근 분산락 | WARN 남기고 **락 없이 진행** | 열림 | 같은 사용자 연타가 DB까지 감 — `UNIQUE(user_id, work_date)`가 막는다. 응답이 예외 경로라 느려질 뿐 중복은 없다 | DB 유니크 | `AttendanceServiceTest` "Redis 락 사용이 불가능하면 DB 제약으로 폴백" |
| 색인 락 | WARN 남기고 **상호배제 없이 진행** | 열림 | 두 색인이 겹칠 수 있다 — 평시엔 단일 실행(cron)이라 겹침은 "Redis 장애 + 수동 실행"이 동시에 있을 때만 | 없음(겹치면 같은 청크를 두 번 훑는다, 해시로 건너뛰므로 낭비지 오염은 아님) | `IndexingLockRedisTest` "Redis가 죽으면 상호배제 없이 진행하고 결과를 돌려준다" — 전용 Redis를 죽여서. **이 테스트가 첫 실행에서 버그를 잡았다**(아래) |
| JWT 블랙리스트 | 예외 → `JwtAuthFilter`가 인증을 세우지 않음 → **401** | **닫힘** | 열면 **로그아웃한 토큰이 통한다.** 이 서비스는 액세스 토큰이 곧 세션이고 블랙리스트가 유일한 폐기 수단이라, 열어 두면 로그아웃이 없는 기능이 된다 | 없음 — 그래서 닫는다 | 둘로 나눠서: `JwtBlacklistRedisTest` "Redis가 죽으면 '없다'가 아니라 예외다"(전용 Redis를 죽여서, 없던 토큰도 예외) + `JwtAuthFilterTest` "블랙리스트 조회가 예외로 끝나면 인증을 세우지 않는다"(체인은 이어져 permitAll 경로는 산다) |
| AI 호출 한도 | WARN 남기고 **검사 없이 통과** | 열림 | 한도 없이 FastAPI로 나간다 — 비용. 닫으면 번역·챗봇이 멎는데 그건 "있으면 좋은 것"(ADR-0008 D5)이라 한도 검사 실패로 막을 이유가 없다 | 없음(비용은 나중에 청구서로 보인다) | `AiQuotaRedisTest` "Redis가 죽으면 검사 없이 통과" — 전용 Redis를 실제로 죽여서 |
| 로그인 시도 제한 | 예외 → `MemberService.login`이 **503** (`LOGIN_UNAVAILABLE`) | **닫힘** | 열면 장애 중 브루트포스가 통하고, 그때 얻은 토큰은 복구 뒤에 유효하다. 닫아서 잃는 것은 **없다** — 블랙리스트가 닫힘이라 Redis가 죽으면 이미 전 요청이 401이고, 로그인을 열어 봐야 받은 토큰을 쓸 데가 없다. 가용성 이득 0, 보안 손실 양수 → 닫는다. **블랙리스트 정책이 바뀌면 이 줄도 같이 본다** | 없음(DB 카운터는 6절이 검토했으나 닫힘이 공짜인 지금은 과함) | `LoginAttemptsRedisTest` "Redis가 죽으면 503이다" — check·failed·succeeded 셋 다 |
| 채팅 접속 상태 | 쓰기는 WARN 남기고 삼킴, 읽기는 **"전부 오프라인"** | 열림 | CONNECT는 막히지 않는다. 읽는 쪽(FCM 예정)이 전원을 오프라인으로 보므로 접속 중인 사람도 푸시를 받아 **중복**된다 — "전부 온라인"이면 장애 동안 오프라인 사용자가 알림을 **못 받는다.** 중복이 유실보다 낫다. Redis가 돌아오면 다음 갱신(≤10초)에 인스턴스가 자기 세션을 다시 써서 복구 | 없음(알림 중복은 청구서도 아니고 보안도 아니다) | `PresenceRedisTest` "Redis가 죽으면 쓰기는 삼키고 읽기는 전부 오프라인" |

넷이 열리고 둘이 닫힌다. 반대 결정을 한 게 아니라 **같은 질문에 다른 답이 나온 것**이다. 닫힌 둘 중 시도 제한은 3절 첫째 줄이 아니라 **"닫아도 잃을 게 없다"**로 닫혔다 — 이미 닫힌 것(블랙리스트)에 기대는 결정이라 3절 표에 없는 넷째 답이다: *다른 닫힘 때문에 이미 죽어 있는 기능이면, 여는 것은 보안만 내주고 가용성은 못 얻는다.*

**색인 락의 열림은 반만 열려 있었다 (2026-09-16, `IndexingLockRedisTest`가 잡음).** `tryLock` 예외는 잡아서 진행했는데, 끝나고 락을 놓는 `finally`가 `lock.isHeldByCurrentThread()`로 물어봤고 그것도 Redis에 가는 명령(`HEXISTS`)이다. Redis가 죽어 있으면 코퍼스를 다 훑은 **뒤에** `finally`에서 `WriteRedisConnectionException`이 나서 완주 결과(`IndexRun`)가 통째로 사라졌다 — 로그엔 "완주"가 찍히고 호출자는 예외를 받는다. 색인은 시간 단위로 걸리므로 "시작할 때 살아 있다가 중간에 죽은" 경우도 같은 자리에서 터진다. 잡았는지를 자기 변수로 기억하고, `unlock` 실패는 WARN으로 삼킨다(워치독 갱신도 같이 끊겼으니 키는 30초 뒤 스스로 사라진다). 3절 규칙 1("Redis 접근 한 줄만 감싼다")의 반례가 아니라 보강이다 — **한 줄이 아니라 두 줄이었다.** 열림 정책은 들어가는 길과 나오는 길을 둘 다 봐야 한다.

## 3. 고르는 질문 — "Redis 없이 통과시키면 무엇이 깨지는가"

| 깨지는 것 | 답 | 근거 |
|---|---|---|
| **정합성이나 보안** — 중복 행, 무효여야 할 토큰이 통함 | 닫는다. 단, **DB에 마지막 방어선이 있으면 연다** | 락은 DB 유니크가 받아주니 열고, 블랙리스트는 받아줄 데가 없으니 닫는다 |
| **기능 품질만** — 번역이 빠짐, 온라인 표시가 빠짐, 색인이 두 번 돔 | 연다 | ADR-0008 D5 "번역은 있으면 좋은 것, 메시지는 있어야 하는 것"의 일반형 |
| **돈** | 연다. 대신 WARN이 **반드시** 남아야 한다 — 열린 채로 지나간 시간이 로그에 보이지 않으면 청구서가 첫 신호가 된다 | AI 한도 |

열 때의 규칙 둘:

1. **잡는 예외는 Redis 접근 한 줄만 감싼다.** `AiQuota.consume`이 `RuntimeException`을 잡는 범위가 `getAtomicLong → incrementAndGet → expire`뿐이고 상한 비교는 밖에 있는 것이 그래서다. 넓게 잡으면 한도 초과 예외까지 삼킨다.
2. **WARN에 `cause`를 넣는다.** 열림은 조용한 실패다. 로그가 없으면 "Redis가 30분 죽어 있었다"를 아무도 모른다.

닫을 때의 규칙 하나: **닫는다는 것은 그 기능이 Redis와 같이 죽는다는 뜻이다.** 블랙리스트가 닫히면 인증 전체가 401이다 — 로그아웃한 토큰만 막히는 게 아니라 **모든** 요청이 막힌다. 그래서 5절의 "죽지 않게 하기"가 닫힘 정책의 짝이다.

## 4. "연다"는 몇 초 뒤인가 — 정지 4.8초, 멈춤 3.1초 (실측 2026-09-16)

Redisson 기본값은 `retryAttempts` 3 · `retryInterval` 1.5초 · `timeout` 3초다. 열림 정책은 "결국 통과한다"이지 "바로 통과한다"가 아니라, 예외가 나기까지 그 요청이 얼마나 붙잡히는지를 운영 설정 그대로 재었다 — `RedisOpenPathLatencyMeasurementTest`, 클라이언트는 `RedissonConfig.redissonClient()`를 그대로 호출해서 만들고 Redis는 전용 컨테이너를 두 방식으로 죽였다. 두 번 돌려 같은 자릿수가 나왔다.

| 조건 | `AiQuota.consume` (INCR+EXPIRE) | `RLock.tryLock(3s, 3s)` (출근 락) | 동시 16 (`consume`) | 나온 예외 |
|---|---|---|---|---|
| A. 살아 있을 때 | p50 15~56 ms | p50 40~56 ms | — | — |
| **B. 정지** — 컨테이너 제거, 포트 닫힘 | **4.73~4.82 s** | **4.77~4.93 s** | 각 요청 4.7~6.1 s, 전체 4.9~6.4 s | `WriteRedisConnectionException: Unable to write command into connection!` |
| **C. 멈춤** — `docker pause`, 연결은 살아 있고 응답만 없음 | **3.05~3.10 s** | **3.07~3.11 s** | 각 요청 p50 3.05~3.10 s, max 4.65~4.70 s, 전체 4.7~5.4 s | `RedisResponseTimeoutException: response timeout (3000 ms) occured after 0 retry attempts, is non-idempotent command: true` |
| D. 멈춤 해제 직후 | 첫 호출 15~39 ms, 이미 세어짐(닫힘) | — | — | — |

A의 수십 ms는 Docker Desktop(Windows) 포트 포워딩 값이라 Redis 자체의 지연(<1 ms)이 아니다. 기준선으로만 둔다.

읽는 법 넷:

1. **정지가 멈춤보다 느리다.** 직관과 반대다 — "포트가 닫혔으니 즉시 거부"가 아니다. Redisson은 쓸 연결이 없으면 `retryAttempts`만큼 `retryInterval`을 기다리며 다시 잡으려 하므로 3 × 1.5 = 4.5초 + 마지막 시도가 4.8초다. 멈춤은 명령이 나가긴 했으므로 `timeout` 3초 한 번이다.
2. **멈춤에서 재시도가 없는 이유는 명령이 비멱등이기 때문이다.** 예외 메시지의 `after 0 retry attempts, is non-idempotent command: true`가 그것이다 — INCR과 락의 Lua는 두 번 가면 두 번 세어지므로 Redisson 3.37은 응답 타임아웃 뒤 다시 보내지 않는다. 우리 용도(카운터·락)는 전부 이쪽이다. 멱등 명령(GET 계열)이었다면 3초 × (1 + 3)까지 갈 수 있었다.
3. **동시 16이 순차와 같다 — 풀(8)이 대기를 얹지 않는다.** 정지 상태엔 연결이 없어 풀이 의미가 없고, 멈춤 상태엔 Redisson이 한 연결에 명령을 다중화하므로 16개가 8개 연결을 기다리지 않는다. max 4.7초는 새 연결을 만드는 요청 몫이다. 즉 대기는 **요청당 3~5초로 고정**이고 동시성에 비례해 늘지 않는다. 대신 그 3~5초 동안 Tomcat 스레드가 하나씩 잠기므로, 출근 피크 07:00~07:30(가정표 3-1: 1,500명/1,800초 ≈ 2~3 TPS)에 Redis가 죽으면 동시에 묶이는 스레드는 TPS × 5초 ≈ 10~15개다. Tomcat 200을 말리려면 40 TPS가 필요하니 가정 안에서는 스레드 고갈이 아니라 **출근 응답이 전부 5초짜리가 되는 것**이 실제 증상이다. 번역 피크 10~30 req/s(3-3)는 40에 가깝다 — 그쪽이 먼저 위험하다.
4. **돌아오면 바로 닫힌다.** pause 해제 후 첫 호출(80 ms 뒤)이 이미 Redis에 닿아 세어졌다. 서킷 브레이커 같은 "반열림 → 닫힘" 지연이 없다는 뜻이고, 그만큼 장애 중엔 요청마다 3~5초를 온전히 낸다는 뜻이기도 하다.

**결정 — 값을 줄인다.** 위 숫자면 열림은 "3~5초 뒤에 열림"이다. 락 폴백과 한도 통과는 어차피 그 뒤에 DB나 FastAPI로 가므로, Redis 대기가 응답 시간의 대부분이 된다. `retryAttempts`를 0~1로, `timeout`을 1초 안쪽으로 내리면 정지는 1.5초 이하, 멈춤은 1초로 줄고, 대신 **살아 있을 때** 느린 응답이 실패로 잡히는 오탐이 생긴다 — A의 p99가 Docker Desktop에서 0.3~2.4초까지 튄 것을 보면 로컬 값으로 정할 수 없고, **운영(AWS, 같은 호스트의 컨테이너 간)에서 A를 다시 재서 그 p99의 몇 배로 정한다.** 그때까지 기본값을 유지한다. 서킷 브레이커(P2-11-4)는 그 뒤다 — 4번처럼 복구가 즉각이라 브레이커의 이득은 "장애 중 3~5초를 0으로"뿐이고, 타임아웃을 줄이면 그 이득의 대부분을 먼저 가져간다.

## 5. 죽지 않게, 비우지 않게 — 실측한 것 (P2-19)

닫힘 용도(블랙리스트)가 생긴 순간부터 Redis의 **가용성과 영속성**이 인증의 가용성이다. 그래서 다음 둘을 넣고 실물로 확인했다.

| 문제 | 있었던 상태 | 바꾼 것 | 확인 |
|---|---|---|---|
| 볼륨 없음 | RDB가 컨테이너 안에만. **재생성**(이미지 갱신·`down`)에 전부 사라진다 → 로그아웃한 토큰이 되살아남 | `--appendonly yes` + `dockin_redis_data:/data` | 키 둘 넣고 `rm -sf → up`: 값·TTL 그대로 |
| `maxmemory` 없음 | 차면 쓰기 거부가 아니라 cgroup 100M에 **OOM-kill** → 블랙리스트가 죽어 전원 401 | `--maxmemory 80mb --maxmemory-policy noeviction` | 1MB 값 77번째에서 `OOM command not allowed`, 컨테이너 생존, 78M에서 `BGREWRITEAOF` 통과(RSS 80M, 컨테이너 76.7MiB/100MiB) |
| `restart` 없음 (2026-09-16) | 죽으면 **아무도 안 살린다.** 앱만 `on-failure`였고 Redis·DB·nginx·TEI는 정책이 없었다 | 넷 다 `restart: unless-stopped` | 아래. 크래시 뒤 1.0초, `SHUTDOWN`(exit 0) 뒤 2.4초에 돌아왔고 키·TTL 그대로. `compose stop`은 그대로 서 있다 |

**`restart`가 없으면 어떻게 끝나는지는 이 머신에서 봤다.** Docker Desktop이 재시작되자(호스트 재부팅과 같은 경로 — 데몬이 컨테이너를 SIGTERM으로 내렸다가 자기 정책대로 되살린다) `dockin-app-1`만 `on-failure`로 되살아났고, DB·Redis는 정책이 없어 그대로 누워 있었다. 앱은 `UnknownHostException: DOCKin-DB`로 기동 실패(exit 1) → `on-failure`가 다시 띄움 → 또 실패를 **여섯 번** 반복하고 있었다. `depends_on`은 `compose up`이 올릴 때만 보는 것이고 데몬 재시작은 모른다. 즉 앱의 재시도가 언젠가 성공하려면 **인프라 쪽에 정책이 있어야** 한다 — 앱 쪽 정책만으로는 무한 루프다.

값이 `unless-stopped`인 이유는 exit 코드다:

| 정책 | 크래시(137·1) | 정상 종료(exit 0) — SIGTERM, 즉 **호스트 재부팅** | 손으로 세움(`compose stop`) 뒤 데몬 재시작 |
|---|---|---|---|
| `on-failure` | 살린다 | **안 살린다.** Redis도 postgres도 SIGTERM에 0으로 끝난다 | 안 살린다 |
| `always` | 살린다 | 살린다 | **살린다** — AOF 손상 뒤 `redis-check-aof --fix` 같은 손작업 중에 데몬이 재시작되면 되살아나 버린다 |
| **`unless-stopped`** | 살린다 | 살린다 | 안 살린다 |

앱이 `on-failure`인 채로 되는 이유는 JVM이 SIGTERM에 143으로 끝나서다 — 재부팅에 "실패"로 보여 살아난다. 위 사고에서 앱만 되살아난 것이 그 증거다.

검증 (`docker compose up -d dockin-redis` 뒤, Docker Desktop 29.1.2):

| 한 것 | 결과 |
|---|---|
| 호스트 pid 네임스페이스에서 `kill -9`(`docker run --pid=host --privileged alpine kill -9 <pid>`) | exit 137 → **0.97~1.04초** 뒤 running, `RestartCount` 1. 넣어 둔 `jwt:blacklist:test`의 TTL이 그대로(AOF+볼륨) |
| `redis-cli SHUTDOWN` — exit 0 | **2.3~2.4초** 뒤 running, `RestartCount` 2. `on-failure`였다면 여기서 안 살아난다 |
| `docker compose stop dockin-redis` | exited(0)인 채로 서 있다. 손으로 세운 건 안 건드린다 |
| (실수) `docker kill -s KILL dockin-redis` | **60초 넘게 안 살아났다.** `docker kill`·`docker stop`은 데몬이 "손으로 세움"으로 기록해 정책을 무시한다 — 크래시를 흉내 내려면 호스트 쪽에서 프로세스를 죽여야 한다. 장애 경로 테스트를 쓸 때 같은 함정 |

이 정책이 **못 하는 것** — "떠 있지만 응답이 없는" 상태(4절의 멈춤, healthcheck unhealthy)는 Docker가 재시작하지 않는다. 그건 죽은 게 아니라서 여기 대상이 아니고, 4절의 타임아웃 줄이기와 P2-11-4가 그쪽 답이다.

**축출 정책은 `noeviction`이어야 한다.** `allkeys-lru`면 가득 찼을 때 오래된 키부터 지우는데, 블랙리스트 항목이 밀려나면 그 토큰이 되살아난다 — 3절의 "보안이 깨진다"에 해당한다. 가득 참은 오류로 보여야지 조용히 잊혀선 안 된다. 실제 크기(카운터 ~1MB/일 + 블랙리스트 로그아웃당 ~100B)는 80M에 한참 못 미치므로 이 정책이 발동하는 날은 버그가 있는 날이다.

## 6. 다음 용도가 붙을 때 — 미리 정해 두는 것

| 후보 | 정책 | 이유 |
|---|---|---|
| ~~채팅 접속 상태(presence)~~ **했다 (2026-09-16, `Presence`)** — 1·2절로 올라갔다. 정한 대로 열림, 읽기는 전부 오프라인 | **열림.** 온라인 표시가 빠질 뿐 | 기능 품질(3절 둘째 줄). TTL 키라 Redis가 돌아오면 다음 갱신에 스스로 복구된다 — `PresenceRedisTest` "갱신하면 키가 사라졌어도 다시 쓴다"로 확인 |
| 다중 인스턴스 채팅 전파(ADR-0004 3절) | **열림 + 인스턴스 내 전파는 유지.** 다른 인스턴스로 못 건너갈 뿐 | 메시지는 DB에 저장됐고(ADR-0008 D1) 따라잡기 API가 있어 재연결 시 채운다 |
| ~~로그인 시도 제한(P2-18-8 "시도 제한도 없다")~~ **했다 (2026-09-16, `LoginAttempts`, P2-18-12)** — 1·2절로 올라갔다. **닫힘(503)**, DB 카운터 없이 | **닫힘.** 열면 브루트포스가 통한다 | 붙일 때 결정한 근거는 "보안"이 아니라 "닫아도 잃을 게 없다"였다(2절). 축은 사원번호뿐(현장 NAT), 10회/10분 고정 창, bcrypt 앞에서 검사, 없는 사원번호도 센다 |

규칙은 3절 하나다. 새 용도는 이 표에 한 줄 추가하고, 그 줄이 3절의 어느 답인지 적는다.

## 7. 남은 것

| 항목 | 상태 |
|---|---|
| `/actuator/health`에 `redis` 항목이 실제로 있는가 | **없었다 → 넣었다 (2026-09-16).** 실제 응답은 `db`·`diskSpace`·`ping`뿐이었다 — Spring Boot의 Redis 헬스는 spring-data-redis의 `RedisConnectionFactory`에 붙는데 이 저장소는 `redisson` 단독이라 그 빈이 없다. `RedisHealthIndicator`(`getRedisNodes(SINGLE).pingAll()`)를 넣었고 Redis가 죽으면 **앱 전체가 DOWN**이다 — 블랙리스트가 닫히면 인증이 전부 401이라 UP이라 할 수 없다. `ActuatorEndpointTest`가 항목 존재를, `RedisHealthIndicatorTest`가 DOWN 경로를 본다 |
| 열림 경로의 대기 시간 실측(4절) | **했다 (2026-09-16).** 정지 4.8초·멈춤 3.1초·동시성에 비례하지 않음·복구 즉각. 값 줄이기는 운영에서 기준선(A)을 다시 잰 뒤 |
| 색인 락·블랙리스트의 장애 경로 테스트 | **했다 (2026-09-16).** `IndexingLockRedisTest`(겹치면 건너뜀·끝나면 해제·죽으면 열림), `JwtBlacklistRedisTest`에 닫힘 한 건, `JwtAuthFilterTest`(예외면 인증 없음). 색인 락의 `finally`가 Redis에 가던 버그를 이 테스트가 잡았다(2절) |
| Redisson `timeout`·`retryAttempts` 줄이기 | 운영에서 살아 있을 때의 p99를 잰 뒤(4절 결정) |
| Resilience4j 서킷(P2-11-4) | 타임아웃을 줄인 뒤. 복구가 즉각이라 이득이 작다(4절 4번) |
| `compose.yaml`의 Redis에 `restart` 정책이 없다 | **넣었다 (2026-09-16, 5절).** `unless-stopped`, DB·nginx·TEI도 같이. 재부팅에 앱만 되살아나 무한 실패하던 것 |
