# 무중단 DDL 절차 — 앱이 떠 있는 채로 스키마를 바꾸는 법

- 대상: `src/main/resources/db/migration/V*.sql`을 쓰는 사람. 운영 DB는 `lock_timeout=5s`(compose.yaml)라 5초 넘게 락을 기다리는 문장은 **DDL이든 근태 체크든** 죽는다.
- 근거: `DB-IMPROVEMENT-PLAN.md` E3, `PRODUCTION-READINESS.md` D5. ①은 `OnlineDdlMigrationTest`가 재현·검증했고(2026-09-16), ②③④는 PostgreSQL 문서와 우리 마이그레이션(V5·V6)에서 끌어낸 절차로 **아직 운영 규모에서 해 본 적 없다** — 처음 쓰는 마이그레이션이 곧 그 기록이 된다(5절 기준).
- 짝: `after-bulk-delete.sql`(대량 삭제 뒤), `rebuild-hnsw-index.sql`(HNSW 재작성)

## 0. MySQL에서 온 사람이 먼저 버려야 할 둘

| MySQL(InnoDB) | PostgreSQL | 그래서 |
|---|---|---|
| `ALTER TABLE … ADD INDEX`는 기본이 online(`LOCK=NONE`) | 일반 `CREATE INDEX`는 **SHARE 락** — 빌드 내내 INSERT/UPDATE/DELETE를 세운다 | 큰 테이블엔 `CONCURRENTLY`를 **직접** 붙여야 한다 |
| DDL은 트랜잭션 밖(암묵 커밋) | DDL이 트랜잭션 **안**에서 돈다. Flyway는 스크립트 하나를 `BEGIN … COMMIT`으로 감싼다 | 스크립트 첫 줄의 `ALTER`가 잡은 락은 **스크립트 끝까지** 안 풀린다. 한 파일에 DDL과 큰 UPDATE를 같이 넣으면 그 UPDATE 시간만큼 락이 길어진다 |

PostgreSQL 락의 핵심 하나: `ALTER TABLE`이 원하는 `ACCESS EXCLUSIVE`는 **대기 중에도 뒤에 오는 모든 문장을 세운다.**
긴 SELECT 하나가 테이블을 읽고 있으면 `ALTER`는 그 뒤에 줄을 서고, 그 뒤에 오는 근태 체크는 `ALTER` 뒤에 줄을 선다 — 아무도 락을 안 쥐었는데 전부 멈춘다.
그래서 ③(짧은 `lock_timeout`)이 "예의"가 아니라 필수다.

## 1. 인덱스 추가 — `CONCURRENTLY` (검증됨)

```sql
-- V8__work_logs_created_at_idx.sql   (예시. 같은 파일에 다른 DDL을 넣지 않는다)
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_work_logs_created_at_log_id
    ON work_logs (created_at DESC, log_id DESC);
```

- **`.sql.conf`는 필요 없다.** Flyway 11의 PostgreSQL 파서가 `CREATE INDEX CONCURRENTLY`를 알아보고 그 스크립트만 트랜잭션 밖에서 돌린다. 그래서 그 파일엔 **이 문장 하나만** 둔다 — 다른 문장을 섞으면 그것들도 트랜잭션 없이 돌아 실패해도 롤백이 안 된다.
- **`spring.flyway.postgresql.transactional-lock=false`가 있어야 한다** (`application.properties`, 2026-09-16). 없으면 Flyway가 히스토리 표를 지키는 advisory lock을 트랜잭션 수준으로 잡아 마이그레이션 내내 `idle in transaction`이고, `CONCURRENTLY`는 마지막 단계에서 "나보다 오래된 스냅샷을 가진 트랜잭션"이 끝나기를 기다리다 **Flyway 자신을 기다린다.** 우리 서버는 `lock_timeout=5s`라 5초 뒤 `55P03 canceling statement due to lock timeout`으로 기동이 실패하고, `lock_timeout` 기본값(0)인 서버였다면 기동이 멈춘다. `OnlineDdlMigrationTest`가 둘 다 재현한다.
- **실패하면 invalid 인덱스가 남고, 재시도는 그것을 보고 건너뛴다.** `CONCURRENTLY`가 중간에 죽으면 인덱스는 `pg_index.indisvalid=false`로 남는다. Flyway 히스토리엔 아무것도 안 남아(트랜잭션 밖인데도 — PostgreSQL에선 성공 뒤에만 행을 쓴다, `OnlineDdlMigrationTest`가 확인) 앱을 다시 띄우면 같은 스크립트가 다시 도는데, `IF NOT EXISTS`가 invalid 인덱스를 "있다"로 읽어 **조용히 통과한다.** 인덱스 없는 채로 기동이 성공하는 것이 최악이다. 그래서 재시도 전에:
  ```sql
  SELECT indexrelid::regclass FROM pg_index WHERE NOT indisvalid;   -- 무엇이 남았나
  DROP INDEX CONCURRENTLY idx_…;                                    -- 지운다 (이것도 CONCURRENTLY)
  ```
  그 다음 앱을 다시 띄운다. `repair`는 필요 없다.
- 유니크 제약이 필요하면 `ADD CONSTRAINT … UNIQUE`(ACCESS EXCLUSIVE로 인덱스를 빌드한다)가 아니라 둘로 나눈다: `CREATE UNIQUE INDEX CONCURRENTLY …` 한 파일 → 다음 파일에서 `ALTER TABLE … ADD CONSTRAINT … UNIQUE USING INDEX …`(메타데이터만, 순간). V6의 `uq_chat_messages_room_seq`는 이렇게 하지 않았다 — 그때 테이블이 작았다.
- **V3는 고치지 않는다.** 이미 적용된 마이그레이션은 체크섬이 히스토리에 있어 파일을 바꾸면 `Migration checksum mismatch`로 기동이 거부되고, 바꿔도 얻는 게 없다(인덱스는 이미 있다). V3의 주석이 "work_logs 본체에 인덱스를 만들 때는 다시 판단"이라고 남긴 그 자리의 답이 이 문서다.

## 2. 컬럼 추가 — nullable 먼저, 채우고, NOT NULL은 검사로

`ADD COLUMN`은 순간이다(PG11+는 상수 `DEFAULT`도 메타데이터만 — V6의 `last_message_seq bigint NOT NULL DEFAULT 0`이 그 경우). 느린 것은 그 뒤다.

| 단계 | 문장 | 락 | 시간 |
|---|---|---|---|
| 1 | `ALTER TABLE t ADD COLUMN c bigint;` | ACCESS EXCLUSIVE, 순간 | 파일 하나 |
| 2 | 값 채우기 — **배치로** (`WHERE c IS NULL … LIMIT 10000`을 반복, 트랜잭션마다 커밋) | 행 락만 | 앱이 새 행에 값을 넣도록 코드가 먼저 나가야 한다 |
| 3 | `ALTER TABLE t ADD CONSTRAINT c_nn CHECK (c IS NOT NULL) NOT VALID;` | ACCESS EXCLUSIVE, 순간 (기존 행을 안 본다) | 파일 하나 |
| 4 | `ALTER TABLE t VALIDATE CONSTRAINT c_nn;` | SHARE UPDATE EXCLUSIVE — **쓰기를 안 막는다** | 전체 스캔, 분 단위여도 된다 |
| 5 | `ALTER TABLE t ALTER COLUMN c SET NOT NULL;` `ALTER TABLE t DROP CONSTRAINT c_nn;` | ACCESS EXCLUSIVE, **순간** — PG12+는 유효한 `CHECK (c IS NOT NULL)`이 있으면 스캔을 건너뛴다 | 파일 하나 |

- V5(`work_logs.user_id SET NOT NULL`)와 V6(`room_seq`)는 3·4 없이 바로 `SET NOT NULL`을 걸었다. 그 문장은 ACCESS EXCLUSIVE를 쥔 채 **테이블 전체를 읽는다** — 2만 행이라 순간이었지, 100만 행이면 초 단위 동안 모든 쓰기가 서고 `lock_timeout=5s`에 걸린다.
- V6는 2단계 UPDATE(전 행에 `ROW_NUMBER()`)를 **같은 파일의 같은 트랜잭션**에서 했다. 0절 둘째 줄이 그 경우다 — 첫 `ALTER`의 락이 UPDATE가 끝날 때까지 이어졌다. 작아서 넘어갔다.
- 배치 UPDATE는 죽은 행을 테이블 크기만큼 만든다. 끝나면 `after-bulk-delete.sql`의 눈으로 `bloat-snapshot`을 한 번 본다.

## 3. 모든 DDL 앞에 — 짧은 `lock_timeout`, 그리고 재시도는 기동 재시작

```sql
-- 트랜잭션 안에서 도는 파일의 첫 줄. Flyway의 트랜잭션에 묶여 스크립트가 끝나면 원래 값(5s)으로 돌아간다.
SET LOCAL lock_timeout = '2s';
ALTER TABLE …;
```

- 왜 2초인가: 0절의 줄서기 때문이다. `ALTER`가 긴 SELECT 뒤에 5초를 기다리면 그 5초 동안 근태 체크가 전부 `ALTER` 뒤에 서고, 그것들도 5초에 죽는다. DDL이 먼저 포기하는 쪽이 싸다. 2초는 근태 체크 p99(수십 ms)보다 두 자릿수 위고 사람이 기다릴 만한 값 — **측정으로 정한 값은 아니다.**
- 재시도는 Flyway가 안 한다. 트랜잭션 안의 스크립트가 죽으면 롤백되고 히스토리에 아무것도 안 남으니 **앱을 다시 띄우면 그게 재시도다.** 세 번 연속 죽으면 누가 그 테이블을 오래 읽고 있는지 본다 — `log_lock_waits=on`이라 1초 넘게 기다린 순간 컨테이너 로그에 `Process holding the lock: <pid>`가 남는다.
- `CONCURRENTLY` 파일에는 `SET LOCAL`을 못 쓴다(트랜잭션이 없다). 서버 기본 5초가 그대로 적용되고, 그것이 1절의 55P03이었다.

## 4. 하지 않는 것

| 무엇 | 왜 |
|---|---|
| 컬럼 타입 변경(`ALTER COLUMN TYPE`) | 테이블 재작성 + ACCESS EXCLUSIVE 끝까지. 새 컬럼 추가 → 2절 → 옛 컬럼 DROP으로 우회. 지금까지 한 적 없다 |
| 큰 테이블에 `ADD CONSTRAINT … FOREIGN KEY` 한 번에 | 참조 대상 전체를 검사한다. `NOT VALID` → `VALIDATE`로 나눈다(2절 3·4단과 같은 꼴) |
| 한 파일에 DDL 여러 개 + 데이터 이동 | 0절 둘째 줄. 파일을 나누면 트랜잭션도 나뉜다 |
| 기동 시 Flyway로 큰 마이그레이션 | 인스턴스가 둘이면 동시에 돈다(D4). 배포 파이프라인에서 `flyway migrate`를 먼저 한 번 — 이건 D4의 몫이고 이 문서 밖이다 |

## 5. "했다"고 말하려면

`DB-IMPROVEMENT-PLAN.md` 5절의 절차 기준: **앱이 떠 있는 채로 한 번 실제로 해 본 기록.** 첫 후보는 D1(`pg_trgm`)이나 A4의 `(created_at, log_id)` 인덱스 — 100만 행 벤치 rig(`WorkLogListBenchmarkTest`)에 부하를 걸어 둔 채 마이그레이션을 돌리고, 그동안 `lock_timeout` 예외가 0건인지, `pg_stat_activity`의 `wait_event`가 무엇이었는지를 이 절 아래에 적는다. 그 전까지 D5는 △다.
