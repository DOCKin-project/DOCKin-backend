# ADR-0005: 근태 시스템 포트폴리오 요소 격차 진단

- 상태: 진단 완료, 구현 전 (우선순위만 정함)
- 대상 코드: `attendance/*`, `member/*`, `global/error/*`, `schema.sql`
- 작성 목적: "근태 시스템 백엔드 포트폴리오"에 흔히 요구되는 항목 체크리스트를 실제 코드와 대조해, 이미 충족된 것과 아닌 것을 코드 근거와 함께 구분한다. ADR-0001/0002와 동일하게 근거 없는 항목은 만들어서 넣지 않는다.

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
