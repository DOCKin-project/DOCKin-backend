# ADR-0005: 근태 시스템 포트폴리오 요소 격차 진단

- 상태: **진단은 2026-07-04 시점 기록. 이후 상당수 해소됨 — 아래 5절의 갱신 표를 먼저 볼 것**
- 대상 코드: `attendance/*`, `absence/*`, `member/*`, `global/error/*`
- 작성 목적: "근태 시스템 백엔드 포트폴리오"에 흔히 요구되는 항목 체크리스트를 실제 코드와 대조해, 이미 충족된 것과 아닌 것을 코드 근거와 함께 구분한다. ADR-0001/0002와 동일하게 근거 없는 항목은 만들어서 넣지 않는다.

> **2~4절은 진단 당시의 스냅샷이며 고치지 않는다.** 격차 진단 문서의 값어치는 "그때 무엇이 비어 있었는가"에 있고,
> 본문을 현재 상태로 덮어쓰면 무엇을 채웠는지가 사라진다. 대신 **5절에 갱신 표를 붙여** 현재와의 차이를 남긴다.
> 인용할 때는 반드시 5절을 함께 볼 것 — 특히 **반대로 뒤집힌 항목**이 있다.

## 1. 진단 배경

근태 시스템 포트폴리오에 흔히 기대되는 항목(핵심 도메인 기능, 동시성/시간 정합성, 배치, 이벤트 기반 설계, 인증/인가, 테스트/감사로그/성능/문서화, 정책 유연성/집계 API)을 5개 카테고리로 묶어, 실제 코드에서 확인되는지 하나씩 대조했다.

## 2. 카테고리별 대조

### 2-1. 핵심 도메인 기능

| 항목 | 상태 | 근거 |
|---|---|---|
| 출퇴근 기록 | 부분 충족 | `AttendanceService.clockin/clockout` 구현됨. `AttendanceStatus`에 `LEFT_EARLY/ABSENT/VACATION/SICK`가 정의돼 있으나 실제로 계산되는 곳이 없다 — `doClockIn`은 NORMAL/LATE만 분기하고(`AttendanceService.java:91-93`), `clockout`에는 조퇴 판정 로직이 없다(`AttendanceService.java:113-141`). |
| 근무 정책 엔진 | 미충족 | `WorkShift`가 하드코딩 enum이다(`WorkShift.java:6-9`). 주석에도 "업무 규정 확정 전까지의 잠정값"이라고 스스로 명시돼 있어, "정책을 데이터로 관리(하드코딩 X)"라는 목표와 정반대 상태다. |
| 휴가 관리 | 절반만 충족 | `schema.sql`에 `absence_requests` 테이블은 이미 있다(PENDING/APPROVED/REJECTED 상태값, `processed_by`/`processed_at` 컬럼까지). 그런데 이 테이블에 대응하는 Entity/Repository/Service/Controller가 자바 코드에 전혀 없다. DB 설계는 끝났고 로직이 비어있는 상태. |
| 초과근무/야근 계산 | 미구현 | `totalWorkTime`은 clock_in~clock_out 경과시간을 문자열로 포맷한 값뿐이다(`AttendanceService.java:133-138`). 근로기준법 기준 초과근무 판정 로직은 없다. |
| 결재 라인(다단계 승인) | 미구현 | `absence_requests`도 `processed_by` 단일 컬럼 구조라, 신청 → 팀장 → 인사팀 같은 다단계 승인 자체가 스키마 레벨에서부터 없다. |

### 2-2. 기술 어필 포인트

| 항목 | 상태 | 근거 |
|---|---|---|
| 동시성 제어 | 완료 (기존 최상급 소재) | Redis 분산락 + DB 유니크 제약 이중 방어. `docs/adr/0001`에 k6 실측 수치까지 기록됨(중복 10건→0건). 별도 조치 불필요, 이미 완결된 서사. |
| 서버 시간 기준 기록 | 충족 | `LocalDateTime.now()`로 서버 기준 기록(`AttendanceService.java:88,129`), 클라이언트 시간 미신뢰. |
| GPS 스푸핑 대응 | 없음 | `inLocation/outLocation`은 검증 없는 문자열 저장(`ClockInRequestDto`). ADR-0001 5번에서 이미 "이번 범위 밖"으로 명시적으로 스코프 아웃해뒀다. |
| 배치/스케줄링 | 없음 | 프로젝트 전체에 `@Scheduled` 사용 0건. 자정 미체크 결근 처리, 월말 집계 배치 없음. |
| 이벤트 기반 설계 | 근태와 무관 | `AsyncConfig`의 스레드풀(`messageExecutor`)은 채팅 쪽에 물려있고, 출퇴근/승인에 대한 비동기 알림 발송 로직은 없다. |
| JWT/RBAC | 충족(기본 수준) | JWT 완비(`JwtUtil`, `JwtBlacklist`). 다만 역할이 `ADMIN/USER` 2단계뿐(`UserRole`) — 팀장/인사 같은 중간 권한 분리는 없다. |

### 2-3. 비기능 요구사항

| 항목 | 상태 | 근거 |
|---|---|---|
| 단위 테스트 | 사실상 없음 | `src/test`에 `MemberServiceTest` 하나뿐. 이 프로젝트의 핵심 셀링포인트인 동시성 로직(`AttendanceService`)에 대한 자동화 테스트가 0건 — ADR-0001의 k6는 수동 실측이라 회귀 테스트를 대신하지 못한다. |
| 감사 로그 | 이름만 있음 | `JpaAuditingConfig`가 `@EnableJpaAuditing`만 선언돼 있고, 이를 실제로 쓰는 `@EntityListeners`나 `@CreatedBy/@LastModifiedBy` 필드가 `Attendance`/`Member`에 없다. 근태 수정 이력 추적이 되지 않는다. |
| 성능(N+1 등) | 발견은 했으나 미조치 | `docs/adr/0002`에서 채팅 목록 N+1(방 20개 → 최대 41쿼리), `work_logs` LIKE 검색 풀스캔 가능성을 이미 진단하고 우선순위까지 매겨뒀다. 아직 고치지는 않았지만, 실측 기반으로 문제를 찾은 상태라 서사 자체는 유효하다. |
| API 문서화 | 충족 | springdoc Swagger 설정 완료(`SwaggerConfig`), 컨트롤러에 `@Operation` 부착(`AttendanceController`). |
| 예외 처리 표준화 | 절반만 충족 | `GlobalExceptionHandler`가 `BusinessException`만 처리한다(`GlobalExceptionHandler.java:15`). `ErrorCode`에 정의된 `INVALID_INPUT_VALUE`, `METHOD_NOT_ALLOWED`, `INTERNAL_SERVER_ERROR`는 정의만 있고 이를 던지는 핸들러가 없다 — `@Valid` 검증 실패(`MethodArgumentNotValidException`) 같은 흔한 케이스가 500으로 나갈 수 있다. |

### 2-4. 차별화 포인트

| 항목 | 상태 | 근거 |
|---|---|---|
| 정책 테이블 분리 | 미충족 | 2-1의 근무 정책 엔진과 동일한 문제(enum 하드코딩). |
| 파티셔닝/아카이빙 | 해당 없음 | 현재 규모에서 근거가 없어 시도하지 않는 것이 ADR-0002의 원칙과 일치한다(근거 없이 소재를 갖다 붙이지 않는다). |
| 통계 대시보드 API | 미구현 | 부서별 지각률, 초과근무 순위 같은 집계 엔드포인트가 없다. |
| CI/CD, Docker | 충족 | `.github/workflows/workflow.yml`, `compose.yaml`/`compose.override.yaml`. |

## 3. 종합 판단

동시성 제어(ADR-0001)는 체크리스트가 요구하는 수준을 이미 넘어섰고, CI/CD·Swagger·JWT도 충족돼 있다. 반면 아래 네 가지가 가장 뚜렷한 격차다.

1. `absence_requests` 스키마는 있는데 백엔드 로직이 없다 — 휴가 신청/승인 도메인이 통째로 비어있다. 스키마가 이미 있어 가장 빠르게 채울 수 있는 구멍.
2. 핵심 로직(동시성)에 자동화된 회귀 테스트가 없다 — k6 수동 실측 외에 CI에서 반복 검증되는 테스트가 필요하다.
3. 근무 정책이 하드코딩 enum이다 — 체크리스트가 명시적으로 짚은 "정책을 데이터로 관리" 항목과 정면으로 충돌한다.
4. 예외 처리 표준화가 절반만 끝났다 — 정의된 `ErrorCode`가 실제로 쓰이지 않는 부분이 있어, 마무리 작업만 남았다.

## 4. 우선순위 정리

1. 휴가 관리 도메인 구현 (Entity/Repository/Service/Controller) — 스키마 이미 존재, 착수 비용이 가장 낮음
2. `AttendanceService` 동시성 로직에 대한 통합 테스트 추가 — 핵심 셀링포인트를 회귀 가능하게 만듦
3. 예외 처리 표준화 마무리 — `MethodArgumentNotValidException` 등 미처리 케이스를 `GlobalExceptionHandler`에 추가
4. `WorkShift`를 정책 테이블(DB) 기반으로 전환 — 하드코딩 제거
5. 배치(자정 결근 처리, 월말 집계)와 통계 대시보드 API — 위 네 가지 이후 진행

---

## 5. 갱신 (2026-08-05 기준)

2~4절은 2026-07-04 스냅샷이다. 그 뒤 무엇이 달라졌는지를 코드 근거와 함께 남긴다.

### 해소된 것

| 원래 진단 | 현재 | 근거 |
|---|---|---|
| 휴가 관리 "절반만 충족" — 자바 코드가 **전혀 없음** | **해소** | `absence` 패키지 11개 파일(모델·리포지토리·서비스·컨트롤러·이벤트) |
| `VACATION`/`SICK`/`ABSENT`가 정의만 되고 채우는 곳 없음 | **해소** | 승인 시 `AbsenceApprovedListener:81-82`, 결근은 `Attendance:85` |
| 배치/스케줄링 — `@Scheduled` **0건** | **해소** | 3곳: `AttendanceBatchService`(자정 결근), `IndexingService`(RAG 색인), `JwtBlacklist`(만료 토큰 정리) |
| 이벤트 기반 설계 — "근태와 무관" | **해소** | `AbsenceApprovedEvent` 발행 → `AbsenceApprovedListener` 수신. **동기 `@EventListener`로 둔 것은 의도**다 — 비동기면 근태 반영 실패 시 승인만 남아 "승인된 휴가인데 결근" 상태가 된다 |
| 단위 테스트 — `MemberServiceTest` **하나뿐** | **해소** | 테스트 클래스 **20개**. 동시성(`LeaveBalanceConcurrencyTest`), 락 타임아웃, 배치 검증, 벡터 매핑/검색 등 |
| 공휴일 인지 부재(진단 당시 미언급, 배치 도입으로 드러남) | **해소** | `work_calendar` + `WorkCalendarService`. 없으면 공휴일에 전원 결근 처리된다 |

### 반대로 뒤집힌 것 ⚠️

| 원래 진단 | 현재 | 근거 |
|---|---|---|
| CI/CD — **"충족"** (`.github/workflows/workflow.yml`) | **미충족** | 워크플로가 계속 실패해 삭제됐다(커밋 `e672ea9`). `.github/workflows` 디렉터리가 **존재하지 않는다.** 테스트가 20개로 늘었는데 **자동 실행되는 곳이 없다** — 진단 당시보다 오히려 나빠진 유일한 항목이다 |

### 여전히 유효한 격차

| 항목 | 상태 |
|---|---|
| `LEFT_EARLY` (조퇴 판정) | **정의만 있고 사용처 0건.** `AttendanceStatus.java:6`에만 존재한다 |
| 예외 처리 표준화 | `GlobalExceptionHandler`에 `@ExceptionHandler`가 **여전히 1개**(`BusinessException`). `@Valid` 실패가 500으로 나갈 수 있다 |
| 감사 로그 | `@CreatedBy`/`@LastModifiedBy` 여전히 없음. **추가로 발견:** `@EnableJpaAuditing`이 `DocKinSpringApplication`과 `JpaAuditingConfig` **두 곳에 중복 선언**되어 있다 |
| 근무 정책 엔진 | `WorkShift` 하드코딩 enum 유지 (백로그 P3) |
| 다단계 결재 | `absence_requests.processed_by` 단일 컬럼 유지 |
| 초과근무/야간 계산 | 미구현 |
| 통계 대시보드 API | 미구현 |
| GPS 검증 | 미구현 (ADR-0001에서 명시적 스코프 아웃) |
| 채팅 N+1 | 진단만 완료, 미조치 (ADR-0002 2-2) |

### 진단 당시에 없던 축

이 문서는 **근태 체크리스트**로만 대조했기 때문에, 이후 프로젝트의 무게중심이 옮겨간 영역이 잡히지 않는다.

- RAG/벡터 검색 (`docs/adr/0006`) — 근태와 무관한 별도 축
- PostgreSQL 이관, Flyway 도입 — 인프라/스키마 관리 축

**즉 이 문서만으로 프로젝트 전체를 대표할 수 없다.** 근태 도메인에 한정된 진단으로만 인용할 것.
