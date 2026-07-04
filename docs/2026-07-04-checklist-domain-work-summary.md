# 작업 요약 (2026-07-04) — 체크리스트 도메인 구현 + springdoc 버전 수정

## 1. 진단

`docs/adr/0005`에서 확인된 갭 중 착수 비용이 가장 낮은 것부터 처리:
- `schema.sql`에 `checklists`/`checklist_items`/`checklist_results` 테이블은 있었지만 Java 코드(Entity/Repository/Service/Controller)가 전혀 없었음.
- `checklist_results`가 항목(item) 단위가 아니라 체크리스트 통째로 `is_checked` 하나만 두는 구조적 결함이 있었음 ("5개 중 3개만 체크"를 표현 못 함).

## 2. 구현 — 장비 작업 전/후 점검 체크리스트 (`com.DOCKin.checklist.*`)

- **Checklist**(템플릿, equipment+phase 유니크) → **ChecklistItem**(순서 있는 항목) → **ChecklistResult**(append-only 점검 기록, 항목 단위로 재설계).
- `checklist_results`를 upsert 없이 매 체크/해제마다 새 행을 쌓는 감사 로그로 설계 — `docs/adr/0005`가 별도로 짚었던 "감사 로그 부재" 갭도 같이 해소.
- "현재 상태" 조회는 네이티브 쿼리로 항목별 최신 결과를 배치 1회 조회(`MAX(result_id)` 기준) — N+1 방지, `docs/adr/0002` 원칙 준수.
- RBAC은 프로젝트 관례대로(`@PreAuthorize` 미사용) 서비스 레이어 수동 체크(`SafetyCourseService` 패턴).
- 관리자(`ChecklistAdminController`, `/api/checklist/admin`)와 사용자(`ChecklistUserController`, `/api/checklist/user`) 컨트롤러 분리.
- `ErrorCode`에 `CK001~CK007` 추가, `schema.sql` 갱신(컬럼명 `role`→`phase`, `checklist_results`를 `checklist_item_id` 참조로 변경, 중복 `equipment_id` 컬럼 제거).
- 단위 테스트: `ChecklistServiceTest`(12개), `ChecklistStatusServiceTest`(8개) — 전부 통과.

## 3. 실제 기동 검증 (curl, 14개 시나리오)

로컬 Docker(MySQL+Redis)로 앱을 직접 띄워 관리자 생성→일반 사용자 체크/해제→중복 생성 차단(409)→삭제 보호(409, 점검 기록 있는 체크리스트/항목)→항목 불일치(400)까지 전부 curl로 검증. DB 직접 조회로 `checklist_results`가 upsert가 아니라 append-only로 누적되는 것도 확인.

## 4. 부수적으로 발견/수정한 버그 — springdoc 버전 불일치

검증 중 `/v3/api-docs`, `/swagger-ui.html`이 500 에러(`NoSuchMethodError: ControllerAdviceBean.<init>`)를 내는 것을 발견. 원인은 `springdoc-openapi-starter-webmvc-ui:2.6.0`이 Spring Boot 4.0.1(Spring Framework 7)의 바뀐 `ControllerAdviceBean` 생성자 시그니처와 호환되지 않는 것 — 실제 API 엔드포인트 자체는 영향 없었고 Swagger 문서화 기능만 깨져 있었음.
- `build.gradle`에서 `springdoc-openapi-starter-webmvc-ui`를 `2.6.0` → `3.0.3`으로 업그레이드.
- 재기동 후 `/v3/api-docs`, `/swagger-ui/index.html` 200 확인, 체크리스트 엔드포인트 6개가 OpenAPI 문서에 정상 노출되는 것 확인, 전체 테스트 회귀 없음(`DocKinSpringApplicationTests`의 DB 연결 실패는 로컬 인프라 미기동 문제로 무관).

## 5. 변경 파일 요약

- 신규: `com.DOCKin.checklist.*`(model/repository/service/controller/dto), 대응 테스트 2개, `docs/PROJECT-SCOPE.md`, `docs/adr/0005-attendance-portfolio-gap-analysis.md`
- 수정: `ErrorCode.java`(CK 코드 추가), `schema.sql`(체크리스트 섹션 재설계), `build.gradle`(springdoc 버전업)
