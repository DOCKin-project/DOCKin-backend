# 슬로우 쿼리와 대기 이벤트 — 주 1회 보는 절차

- 작성: 2026-09-15
- 관련: `PRODUCTION-READINESS.md` O2·O4, `DB-IMPROVEMENT-PLAN.md` B4·C2·D3, `scripts/db/slow-query-report.sh`, `HikariPoolWatch`
- 한 줄: **`pg_stat_statements`는 적재만 돼 있고 아무도 안 읽었다.** 주 1회 세 축(누적·평균·호출)으로 top 10을 뽑고, 그 자리에서 지금 기다리는 세션(`wait_event`)도 같이 본다. 임계값은 없다 — 몇 주 쌓인 뒤 분포가 정한다.

---

## 1. 지금까지는

`compose.yaml`이 `shared_preload_libraries=pg_stat_statements`를 주고 있었고, 그 이유는 배치 INSERT가 실제로 묶이는지 시퀀스 호출 수를 세기 위해서였다(`HibernateBatchInsertVerificationTest`). 그것이 이 확장을 읽는 유일한 곳이었다. 운영 DB에서 어떤 쿼리가 시간을 먹는지는 **아무도 본 적이 없다**(O4 △).

락 쪽도 같다. 13분 44초 `transactionid` 대기(`PG-BOOK-EXPERIMENTS.md` 장애 #4)는 테스트가 멈춘 뒤 사람이 `pg_stat_activity`를 열어서야 알았다. 로그에는 한 줄도 없었다 — `log_lock_waits`가 off였다(지금은 on, C1).

## 2. 무엇을 어떻게

| | 값 | 근거 |
|---|---|---|
| 도구 | `./scripts/db/slow-query-report.sh` → `measure/slow-query/report-<ts>.txt` | 여섯 표 + 서버 로그의 락 대기 수 |
| 언제 | **주 1회**, 같은 요일 같은 시각 | 주간 창끼리 비교하려면 창이 같아야 한다. `[0]`의 `stats_reset`이 창의 시작이다 |
| [1] 누적 `total_exec_time` | 시스템 전체 부하의 주범 | 짧아도 자주 불리면 여기 온다 |
| [2] 평균 `mean_exec_time` (calls ≥ 5) | 사용자가 체감하는 것 | 목록·검색·색인 배치. 1~4회짜리는 콜드 캐시라 뺀다 |
| [3] 호출 `calls` | N+1·캐시 후보 | P2-12-1(방 목록 41개 쿼리)이 여기 보였을 것 |
| [4] 지금 기다리는 세션 | `wait_event_type`·`wait_event`·`pg_blocking_pids()` | **C2.** `Lock`이면 `blocked_by`의 pid가 범인. ShadowFit이 `data_locks`로 본 GRANTED/WAITING을 PG에서는 이렇게 본다 |
| [5] 1분 넘게 열린 트랜잭션 | `idle in transaction` 포함 | 이 뒤의 죽은 행은 VACUUM이 못 치운다(`after-bulk-delete.sql` ③) |
| [7] 서버 로그 `still waiting for` | 최근 7일, `log_lock_waits=on` | 1초 넘게 기다린 락의 수. 0이 정상 |

**임계값은 일부러 없다.** "느리다"의 기준을 지금 정하면 근거 없는 숫자가 된다(`DB-IMPROVEMENT-PLAN.md` 0절 3). 4주 쌓이면 [2]의 분포에서 `log_min_duration_statement`를 정하고, 그때 `auto_explain`을 켠다.

## 3. 읽는 법 — 표를 보고 무엇을 하나

| 보이는 것 | 뜻 | 다음 |
|---|---|---|
| [1]에 있는데 [2]엔 없다 | 짧은 쿼리가 너무 자주 불린다 | [3]에서 calls를 보고 N+1인지, 캐시 대상인지(ADR-0002 2-4 Caffeine) |
| [2]에 `hit_pct`가 낮다 | 캐시 밖에서 읽는다 | `shared_buffers` 128MB보다 워킹셋이 크다는 뜻. 올리기 전에 밤 3(8배 올려도 불변)을 다시 읽는다 |
| [2]에 `stddev`가 `mean`보다 크다 | 어떤 때는 빠르고 어떤 때는 느리다 | 파라미터에 따라 계획이 다르다(선택도). `EXPLAIN (ANALYZE, BUFFERS)`를 느린 파라미터로 |
| [4]에 `Lock` | 다른 트랜잭션이 쥐고 있다 | `blocked_by` pid의 `[5]` 행을 본다. 앱 쪽이면 어느 요청인지 `application_name`·`traceId` |
| [4]에 `LWLock`·`IO` | 내부 경합·디스크 | 한 번이면 무시, 매주 같은 자리면 그 쿼리를 [1]에서 찾는다 |
| [5]에 `idle in transaction` | 커넥션을 잡은 채 앱이 다른 일을 한다 | 트랜잭션 안에서 외부 호출(ADR-0008 4-1이 번역을 밖으로 뺀 이유)을 의심 |
| [7]이 0이 아니다 | 1초 넘는 락 대기가 있었다 | 서버 로그의 그 줄에 쥔 pid와 문장이 있다 |

## 4. 풀 고갈은 따로 본다 — `HikariPoolWatch`

DB 쪽 표에는 **커넥션을 못 받아 DB에 도달하지 못한 요청**이 안 보인다. M1(ADR-0008 7-1)이 그 경우였다 — 채팅 지연 초 단위의 원인이 DB가 아니라 풀 앞의 줄이었다.

`HikariPoolWatch`가 10초마다 `pending`을 읽고 0이 아니면 WARN 한 줄을 찍는다:

```
HikariPool 대기 pending=1 active=2 idle=0 total=2 max=2 — 풀 고갈이면 '누가 잡고 있나'부터 (ADR-0008 7-1)
```

`active=total=max`면 고갈이다. 그때 답은 풀을 올리는 것이 아니라 **누가 잡고 있나**다 — PostgreSQL은 커넥션이 프로세스라 풀을 올린 만큼 DB 메모리를 낸다. `[4]`·`[5]`에서 그 커넥션들이 무엇을 하고 있는지 본다. 풀 크기는 `maximum-pool-size=10`으로 명시만 했고, 바꾸려면 ADR-0004 3-2 부하 실측이 먼저다.

알림 인프라(O2)가 붙기 전까지는 이 WARN이 알림이다. 로그 집계(O3)가 붙으면 이 문자열이 조건이 된다.

## 5. 다음

| 항목 | 언제 |
|---|---|
| `log_min_duration_statement` + `auto_explain` | 4주 분포 뒤 |
| WARN → 실제 알림(Slack/이메일) | O2·O3와 함께 |
| 주간 보고서를 cron으로 | `scripts/backup/install-cron.sh`에 한 줄. 지금은 손으로 |

## 6. 파일

| 파일 | 역할 |
|---|---|
| `scripts/db/slow-query-report.sql` | 여섯 표 |
| `scripts/db/slow-query-report.sh` | 파일로 남기기 + 서버 로그 락 대기 수 |
| `src/main/java/com/DOCKin/global/config/HikariPoolWatch.java` | 풀 대기 WARN. 테스트 `HikariPoolWatchTest` |
| `measure/slow-query/` | 주간 보고서. `.gitignore` 대상 아님 — 분포가 곧 근거라 남긴다 |
