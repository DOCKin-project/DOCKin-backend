# 프로덕트 준비도 — 포폴에서 프로덕트로 가려면 무엇이 비어 있는가

- 작성: 2026-09-13
- 관련 문서: `WORK-BACKLOG.md` P2-11(관측·복원력·보안)·P2-17(발표 자료 대비 미구현)·P2-18(이 문서에서 나온 보안 항목), `PORTFOLIO-ROADMAP.md` 5절(DB)·7절(운영), `docs/adr/0008`(채팅), `SERVICE-SCALE-ASSUMPTIONS.md`
- 기준: **팀 밖의 사람이 매일 쓰고, 고장 나면 우리가 먼저 알고, 데이터를 잃지 않는다.** 이 세 문장에 "예"라고 답할 수 있으면 프로덕트다.

---

## 0. 진단 한 줄

이 저장소는 성능·측정 쪽이 현업 이상이고(ADR-0006, P2-15, E1~E8), **재지 않아도 되는 쪽이 비어 있다** — 백업, 배포, 알림, 토큰 생명주기, 권한 경계. "측정으로 결정한다"는 원칙이 강할수록 측정할 것이 없는 일은 뒤로 밀린다. 그것이 포폴과 프로덕트의 차이다.

아래 표의 상태 표기: ✅ 있음 · △ 절반 · ❌ 없음.

---

## 1. 출고 차단 — 이것 없이는 아무것도 내보내지 않는다

2026-09-13 코드 점검에서 나왔다. 항목별 상세는 `WORK-BACKLOG.md` P2-18.

| # | 항목 | 상태 | 어디 | 왜 차단인가 |
|---|---|---|---|---|
| G1 | 회원가입으로 ADMIN 획득 | ❌ | `MemberService.java:79` `.role(dto.getRole())`, `/member/signup`은 화이트리스트 | 가입 본문에 `"role":"ADMIN"` 한 줄. 그 뒤 휴가 승인·actuator·전 관리자 API가 열린다 |
| G2 | 남의 계정 탈퇴 (IDOR) | ❌ | `MemberController.java:49` `DELETE /member/{userId}` | principal과 대조하지 않는다 |
| G3 | 남의 채팅방 도청·투고 | ❌ | `StompHandler.java:75` SUBSCRIBE 목적지 검사 없음, `ChatController.java:23` 발신 시 멤버십 검사 없음 | REST(`ChatRoomController`)는 멤버십을 보는데 WebSocket만 비어 있다 |
| G4 | WebSocket 토큰 원문이 INFO 로그에 | ❌ | `StompHandler.java:37` | HTTP 쪽(`JwtAuthFilter`)에서 2026-08-07에 지운 그 결함이 STOMP에 남아 있다(로드맵 7-3 S4) |
| G5 | 토큰 생명주기가 없다 | ❌ | refresh 토큰을 저장만 하고(`MemberService.login`) 갱신 엔드포인트가 없다. 로그아웃 폐기는 `JwtBlacklist` in-memory(P2-5) | 만료되면 재로그인뿐이고, 재시작하면 로그아웃이 풀린다. **액세스 토큰이 곧 세션**인 구조라 블랙리스트가 유일한 폐기 수단 |
| G6 | 관리자 경로를 한 곳에서 막지 않는다 | △ | `SecurityConfig`는 `/actuator/**`만 `hasRole`. `/api/*/admin/**`은 서비스가 손으로 `role != ADMIN` 검사 | 메서드 하나 빠지면 그대로 구멍 — `SafetyAdminController` 읽기 3개·`ChecklistAdminController` 상세 조회가 이미 그렇다 |

> **G1~G4는 각각 몇 줄이다.** G5는 엔드포인트 하나 + Redis 이관(P2-5). G6은 `SecurityConfig` 한 줄.

---

## 2. 데이터를 잃지 않는다

| # | 항목 | 상태 | 지금 | 해야 할 것 |
|---|---|---|---|---|
| D1 | DB 백업 | ❌ | 없음. 로드맵 5절 D9가 "포폴 값어치 낮음"으로 미뤘다 | `pg_dump` 일일 + WAL 아카이브(PITR). **복구를 실제로 한 번 해보고 걸린 시간을 적은 것**까지가 백업이다 |
| D2 | S3 보존 | ❌ | 버킷 하나, 버전관리·수명주기 없음 | 버전관리 켜기. 휴가 증빙서류는 개인정보라 보존기간을 정한다(7절) |
| D3 | 스키마 마이그레이션 | ✅ | Flyway + `ddl-auto=validate` + `SchemaValidationTest`·`LocalMigrationDriftTest` | 유지 |
| D4 | 다중 인스턴스 기동 시 마이그레이션 | ❌ | 기동 시 Flyway가 돈다. 인스턴스 둘이면 동시에 돈다(로드맵 7-5) | 배포 파이프라인에서 `flyway migrate`를 **먼저 한 번** 돌리고 앱을 띄운다 |
| D5 | 무중단 스키마 변경 | ❌ | 로드맵 D7 계획만 | `CREATE INDEX CONCURRENTLY`, 컬럼 추가는 nullable 먼저. **ADR-0008 V6가 첫 실전**이다 |
| D6 | 탈퇴 시 삭제 범위 | △ | `deleteAccount`는 `users`·`refresh_token`만 지운다. FK 8개(P2-15-7)가 걸린 행은 어떻게 되는지 정한 적 없다 | 삭제·익명화·보존 중 무엇인지 도메인별로 정한다(7절) |

---

## 3. 배포를 사람 손에서 뗀다

| # | 항목 | 상태 | 지금 | 해야 할 것 |
|---|---|---|---|---|
| R1 | CI | ✅ | 빌드+테스트, Testcontainers, 빨간불 → 이슈 자동 생성(P2-16-1) | 유지 |
| R2 | CD | ❌ | 없음. 배포 워크플로는 P0-7에서 의도적으로 복구하지 않았다(로드맵 7-5). 그 결과 이미지가 소스보다 한 달 낡은 채 돌았다(P2-13-1) | `main` 머지 = 이미지 푸시 = 배포. 태그를 커밋 해시로(`/actuator/info`가 이미 해시를 답한다) |
| R3 | 환경 분리 | ❌ | `application.properties` 하나 + `application-seed.properties`. Swagger·`/v3/api-docs`가 화이트리스트 | `dev`/`staging`/`prod` 프로파일. prod에서 Swagger·seed 끔 |
| R4 | 시크릿 | ❌ | `.env`는 커밋되지 않는다. 배포 환경에 어떻게 넘기는지 답이 없다(P2-11-6) | AWS면 SSM Parameter Store/Secrets Manager. 아니면 최소한 "호스트 `.env`를 누가 어떻게 바꾸는가" 문서 |
| R5 | 롤백 | ❌ | 없음 | 이전 이미지로 돌리는 명령 한 줄 + "마이그레이션이 이미 돌았으면"의 답(전진 수정 원칙이면 그렇게 적는다) |
| R6 | 헬스 기반 교체 | △ | `/actuator/health` + compose 헬스체크(P2-11-2) | 새 컨테이너 UP 뒤 옛 것을 내리는 순서만 |
| R7 | graceful shutdown | ❌ | `server.shutdown` 미설정 | 한 줄. 색인 배치는 멱등이라 중단 후 재개가 이미 된다 |

---

## 4. 고장을 우리가 먼저 안다

| # | 항목 | 상태 | 지금 | 해야 할 것 |
|---|---|---|---|---|
| O1 | 헬스·지표 | ✅ | Actuator health/metrics/info, `traceId` MDC(P2-11-2/3) | 유지 |
| O2 | 알림 | ❌ | 지표는 있는데 **아무도 호출받지 않는다** | 4개만: 5xx 비율, health DOWN, 디스크, Hikari 풀 고갈. Slack/이메일 |
| O3 | 로그 집계 | ❌ | 컨테이너 로그가 호스트에만 | CloudWatch Logs나 Loki. `traceId`를 만든 이유가 여기서 살아남는다 |
| O4 | 슬로우 쿼리 | △ | `pg_stat_statements` 로드됨(compose·테스트 컨테이너 둘 다). 보는 사람이 없다(로드맵 D2) | 주 1회 상위 10개를 보는 절차, 또는 `auto_explain` |
| O5 | 에러 트래킹 | ❌ | `GlobalExceptionHandler`가 500을 삼키고 로그만 남긴다 | Sentry 류. 지금은 사용자가 말해줘야 안다 |
| O6 | 장애 대응 문서 | ❌ | 없음 | "DB가 안 뜬다 / 번역 서버가 죽었다 / 디스크가 찼다" 세 시나리오면 된다. E8의 "앱 재시작으로 안 돌아오면 DB도 재시작"이 이미 하나다 |

---

## 5. 한 부품이 죽어도 전체가 안 죽는다

| # | 항목 | 상태 | 지금 | 해야 할 것 |
|---|---|---|---|---|
| F1 | 외부 호출 타임아웃 | ✅ | P2-9-1 완료 | 유지 |
| F2 | 폴백·서킷 브레이커 | △ | RAG만 키워드 폴백. FastAPI(번역·STT)·S3·Redis는 없다(P2-11-4) | Resilience4j. 색인 분산락은 Redis 장애 시 경고 후 진행으로 이미 처리했다(`IndexingService`) |
| F3 | 레이트 리밋 | ❌ | 로그인·STT·번역·챗봇 전부 무제한 | 외부 호출 비용이 붙는 경로(임베딩·번역·STT)부터 |
| F4 | 비동기 큐 포화 시 유실 | ❌ | `messageExecutor` `AbortPolicy` → 채팅 저장이 조용히 유실(ADR-0008 1절) | ADR-0008 D1(저장을 동기로, 전파를 AFTER_COMMIT으로)이 곧 이 항목 |
| F5 | 스레드 컨텍스트 전파 | △ | `SecurityConfig.java:36` `MODE_INHERITABLETHREADLOCAL`. 풀 스레드는 **만들어질 때** 컨텍스트를 복사해 그 사용자를 계속 든다. 지금은 `@Async` 코드가 `SecurityContextHolder`를 안 읽어 무사하다 | ADR-0008 리스너에서 "현재 사용자"를 읽는 순간 다른 사용자로 실행될 수 있다. `DelegatingSecurityContextAsyncTaskExecutor`로 |

---

## 6. 수평 확장 — 지금 하지 않는다. 못 하는 이유만 안다

인스턴스 하나가 v1로 충분하다(`SERVICE-SCALE-ASSUMPTIONS.md` 3-1 근태 3 TPS, 3-3 채팅 30 msg/s). 다만 **둘이 되는 순간 깨지는 것**의 목록은 지금 갖고 있어야 한다.

| 무엇 | 왜 깨지나 | 대응 |
|---|---|---|
| `JwtBlacklist` | in-memory Map. 로그아웃이 한 인스턴스에만 | Redis(P2-5) |
| `StompHandler.onlineUsers` | static Map. 접속 상태가 인스턴스별 | Redis |
| SimpleBroker | 인스턴스 간 전파 없음 — A에 붙은 사람이 B에서 보낸 메시지를 못 받는다 | Redis pub/sub 또는 외부 STOMP 브로커 |
| Flyway 기동 | 동시에 돈다 | D4 |
| 색인 배치·스케줄러 | — | **이미 Redisson 락으로 막았다**(`IndexingService`, 밤 1 사고의 결과) |

---

## 7. 사람과 데이터

| # | 항목 | 상태 | 해야 할 것 |
|---|---|---|---|
| H1 | 파일럿 | ❌ | 스테이징에서 현장 사용자 3~5명. **베트남어·태국어 사용자가 한 명은 있어야** 교차언어 RAG와 채팅 번역의 실사용 분포를 안다 — `SERVICE-SCALE-ASSUMPTIONS.md`의 [측정 필요] 대부분이 여기서 채워진다 |
| H2 | 개인정보 | ❌ | 사원번호·근태·휴가 사유·증빙서류를 다룬다. 접근 로그, 보존 기간, 탈퇴 시 삭제 범위(D6) |
| H3 | 기능 완결성 | △ | PPT 대비 미구현(P2-17): FCM 없음(채팅이 반쪽), 공지 없음, 오프라인 동기화 없음. **채팅 저장 실패가 조용히 유실되는 것(F4)은 성능이 아니라 신뢰 문제**라 앞순위 |
| H4 | 클라이언트 오류 계약 | △ | `ErrorCode` 체계는 있다(P0-9). STOMP `ERROR` 프레임은 ADR-0008 3절에서 정한다 |

---

## 8. 순서

```
0  1절 G1~G6                          ← 이거 없이는 아무것도 못 내보낸다
1  D1 백업·복구 리허설                   ← 이거 없이는 내보내면 안 된다
2  R2 CD + R3 환경 분리 + R4 시크릿      ← 이거 없이는 고칠 때마다 손으로 배포
3  O2 알림 4개 + O3 로그 집계            ← 이거 없이는 사용자가 QA
4  ADR-0008 구현(F4) + FCM(P2-17-3)     ← 이거 없이는 기능이 반쪽
5  H1 파일럿 → 가정표의 [측정 필요] 채우기
6  F2·F3·6절                            ← 파일럿에서 실제로 아픈 것부터
```

0~3은 재지 않아도 되는 일이다. 그래서 이 저장소에서 미뤄져 왔다. 4부터는 다시 이 저장소의 방식대로 — 재고 정한다.

---

## 9. 괜찮았던 것 (다시 점검하지 않아도 된다)

SQL 인젝션(전부 바인딩), CORS(P2-11-1 완료), actuator 노출(health만 공개), 비밀번호(bcrypt), 시크릿(커밋 이력에 없음 — `git log -G` 확인), 작업일지·댓글·채팅방 REST의 소유자 검사, HTTP 쪽 JWT 로깅(S4 완료), 외부 호출 타임아웃(P2-9-1), 색인 동시 실행(Redisson 락).
