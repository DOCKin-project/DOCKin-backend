# ADR-0011: 점검은 템플릿의 상태가 아니라 사건이다 — 체크리스트 회차(run)

- 상태: **결정.** 2026-09-19. 코드는 PR #129(`feat/checklist-runs`, V11). 후속은 관리자 회차 목록·항목 퇴역(5절)
- 대상 코드: `checklist/model/ChecklistRun`·`ChecklistRunOutcome`, `ChecklistResult.run`, `ChecklistRunService`(옛 `ChecklistStatusService` 대체), `ChecklistRunRepository`, `ChecklistResultRepository.findLatestResultsByRunId`, `ChecklistUserController`, `V11__checklist_runs.sql`
- 관련 문서: `docs/2026-07-04-checklist-domain-work-summary.md`(회차 없이 만든 첫 설계), `docs/adr/0010`(같은 꼴의 결정 — 열린 것을 닫는 규칙, 배치 없이 시간으로), `docs/WORK-BACKLOG.md` P2-17(PPT "작업 전/후 점검"), 이슈 #81(앱이 옮길 옛 경로)
- 작성 목적: 체크리스트 테이블 셋(`checklists`·`checklist_items`·`checklist_results`)에 "누가 언제 한 번 점검했다"가 없었다. 그래서 상태가 전역이었고 완료가 없었다. 테이블 하나가 왜 필요한지, 그 테이블의 열림·닫힘 규칙, 옛 경로를 어떻게 남겼는지를 적는다.

---

## 1. 무엇이 없었나

`checklist_results`는 (항목, 사용자, 체크 여부, 시각)의 append-only 로그다. 그 자체는 맞다 — 체크·해제 이력은 남아야 한다.
빠진 것은 **묶는 키**다. "현재 상태"를 항목별 `MAX(result_id)`로 뽑았는데, 그 범위가 템플릿 전역이었다.

| 상황 | 예전 |
|---|---|
| A가 월요일 1도크 크레인 "작업 전" 전부 체크 | 화요일 B가 열면 **전부 체크된 채로** 보인다 |
| "이 작업 전에 점검을 했는가" | 답할 수 없다 — 완료라는 개념이 없다 |
| 오늘 몇 대가 점검됐나, 누가 안 했나 | 셀 수 없다 |
| 관리자가 항목 문구 수정 | 과거 체크의 의미가 바뀐다(결과가 item_id만 참조) |

원인은 하나다. **점검을 템플릿의 상태로 모델링했다.** 점검은 한 사람이 한 템플릿을 한 번 수행한 사건이고, 그 사건에 결과가 속한다.

## 2. 결정 — 회차 테이블 하나

```
checklists ──< checklist_items                       (템플릿, 그대로)
     └──< checklist_runs ──< checklist_results        (회차 = 사건. 결과는 회차에 속한다)
```

| 컬럼 | 뜻 |
|---|---|
| `run_id` | |
| `checklist_id`, `user_id` | 어느 템플릿을 누가 |
| `started_at` | QR/NFC를 찍고 연 시각 |
| `closed_at`, `outcome` | 닫힘. 둘은 한 쌍이다(CHECK로 같이 NULL이거나 같이 값). `outcome` ∈ COMPLETED, ABANDONED |

상태는 셋인데 컬럼은 이 둘이다. **열려 있음(IN_PROGRESS)은 "닫히지 않음"**이고, 닫혔으면 `outcome`이 곧 상태다. 상태 컬럼을 따로 두면 `closed_at`과 어긋날 수 있는 자리가 하나 더 생긴다.

`checklist_results.run_id NOT NULL`. 최신 판정은 이제 **회차 안**이다 — `findLatestResultsByRunId`. 결과의 `user_id`는 남긴다: 회차의 점검자와 같음을 서비스가 보장하고, 행 하나만 봐도 누가 체크했는지 읽히는 감사 로그로 둔다.

## 3. 열림·닫힘 규칙

**한 사람은 같은 템플릿의 열린 회차를 하나만 가진다.** 부분 유니크 `uq_checklist_runs_open (checklist_id, user_id) WHERE closed_at IS NULL`.
그래서 열기(`POST /runs`)는 멱등이다 — 더블탭·재접속·앱 재시작이 같은 회차로 돌아온다(200). 없을 때만 새로 연다(201).
동시에 두 번 열면 진 쪽이 유니크에 걸리는데, 그 트랜잭션은 이미 중단된 상태라 안에서 복구할 수 없다. `ChatService.saveMessage`와 같은 꼴로 트랜잭션 **밖**에서 잡아 이긴 쪽을 다시 찾는다(`TransactionTemplate`).

**12시간 넘은 열린 회차는 이어서 하지 않는다.** 그 사람이 같은 템플릿을 다시 열 때 ABANDONED로 닫고 새로 연다. 어제의 체크가 오늘의 점검이 되면 안 된다.
배치는 없다 — ADR-0010이 잊힌 출근을 "다음 행동 때" 처리하는 것과 같다. 12시간은 잠금이 아니다: 닫히기 전까지는 체크·완료가 된다. "새로 시작할 때 이어서 할지 새로 할지"의 기준일 뿐이다.

**완료는 전 항목 체크여야 한다.** 하나라도 기록이 없거나 마지막 기록이 해제면 409 `CHECKLIST_RUN_INCOMPLETE`. 안전 점검의 목적이 그것이다 — 미체크를 두고 완료할 수 있으면 완료가 뜻을 잃는다.
어느 항목이 비었는지는 응답 본문에 싣지 않는다. `ErrorResponseDto`는 `status/message/timestamp`뿐이고 그 계약을 여기서 바꾸지 않는다(`GlobalExceptionHandler` 주석). 회차 조회가 보여 준다.

## 4. 옛 경로를 어떻게 남겼나

앱은 `GET /checklists?equipmentId&phase`와 `PATCH /checklists/{id}/items/{itemId}/check`를 쓴다. 지우지 않고 **의미만 회차 단위로 바꿨다**:

| 옛 경로 | 지금 뜻 |
|---|---|
| `GET /checklists` | 템플릿 + **내 열린 회차**의 상태 + `myOpenRunId`. 열린 회차가 없으면 전부 미체크 — 남의 체크는 더 이상 안 보인다 |
| `PATCH …/check` | 내 열린 회차에 기록. 없으면 연다 |

옛 앱이 그대로 동작하고 1절의 결함은 사라진다. 앱이 `/runs`로 옮기면 지운다(#81·#79와 같은 절차).

## 5. 하지 않은 것

| 항목 | 왜 | 자리 |
|---|---|---|
| 관리자 회차 목록 | `GET /admin/runs?date&equipmentId&userId&status` — "오늘 크레인 3호 작업 전 점검 누가 했나". 이 PR의 범위가 근무자 흐름이라 | 다음 PR |
| 항목 퇴역(`retired_at`) | 결과가 있는 항목은 삭제·문구 수정 대신 퇴역(새 회차엔 안 나오고 옛 회차는 그대로 참조). 지금은 삭제만 막고 수정은 허용 — 기록 의미가 바뀐다 | 다음 PR |
| 작업일지 연결(`runs.work_log_id`) | "어느 작업의 점검인가"가 되지만 작업일지 생성(STT 포함) 흐름을 같이 바꿔야 한다 | 별도 주제 |
| 미완료 회차 배치 | 12시간 지난 열린 회차를 배치로 닫을 수도 있지만, 다음 열기가 닫는 것으로 충분하다. 목록에서 "열린 채 12시간 지남"을 보여 주려면 파생값으로 | 필요해지면 |
| 항목 순서 UNIQUE(`checklist_id, sequence`) | 스키마 정리 마이그레이션에 같이 | V12~ |

## 6. 검증

- `ChecklistRunServiceTest` 14건 — 열기 4(없음/12h 안/12h 밖/경합), 권한, 체크 3(기록·닫힌 회차·남의 것/다른 템플릿), 완료 3(기록 없음·해제·전부), 옛 경로 3
- `ChecklistResultRepositoryTest` 3건(실제 DB) — 회차 경계로 다시 씀. "같은 사람·같은 템플릿·다른 회차"는 서로 안 보인다
- V11은 Testcontainers에서 실제 실행(`@DataJpaTest` + `ddl-auto=validate`). 백필은 시드에 결과 행이 없어 0건 — 데이터 있는 DB에서는 회차를 못 찾는 행이 남으면 RAISE

## 7. 되돌릴 조건

- 한 회차를 여러 사람이 나눠 점검하는 운영(조장이 열고 조원이 체크)이 실제로 있으면 `uq_checklist_runs_open`의 `user_id`가 틀린 것이다. 그때는 회차의 점검자를 "연 사람"으로 두고 결과의 `user_id`가 실제 체크자를 가리키게 한다 — 결과에 `user_id`를 남긴 이유 중 하나다.
- 12시간이 맞지 않으면(야간 잔업이 12시간을 넘는 것이 정상이면) `ChecklistRun.OPEN_SPAN`만 바꾼다. ABANDONED가 로그에 쌓이는지 본다.
