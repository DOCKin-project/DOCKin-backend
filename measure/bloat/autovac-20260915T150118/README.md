# 실험 ② — 대량 삭제 뒤 autovacuum: `cost_delay` 2ms vs 0

- 일시: 2026-09-15 15:01~15:15 KST, 로컬(Windows 10, i3-6100, Docker Desktop)
- rig: `scripts/db/autovacuum-lab.sh` (`ROWS=500000 DELETE_UPTO=450000`, 판 순서 2 0 2 0)
- 컨테이너: `pgvector/pgvector:pg17`, `--memory=512m` (운영과 같음), `shared_buffers` 128MB 기본
- 표: `work_logs` 꼴 50만 행(~279MB 힙, 본문 500B, `user_id` 인덱스) → 앞쪽 45만 행 DELETE
- 관련: `docs/PG-BOOK-EXPERIMENTS.md` 카드 ②, `docs/DB-IMPROVEMENT-PLAN.md` A2

> **절대 시간을 옮겨 적지 말 것.** 이 호스트의 값이다. 볼 것은 배수와 통제군 폭이다.

## 결과

| 판 | `cost_delay` | 깨어남(s) | **VACUUM 자체(s)** | 읽기 속도 | CPU(s) | 힙 |
|---|---|---|---|---|---|---|
| 1 | 2ms (기본) | 46 | **24.58** | 11.3 MB/s | 1.7 | 279MB → 279MB |
| 2 | 0 | 52 | **8.23** | 33.8 MB/s | 1.1 | 279MB → 279MB |
| 3 | 2ms (기본) | 52 | **25.67** | 10.9 MB/s | 2.3 | 279MB → 279MB |
| 4 | 0 | 34 | **13.18** | 21.1 MB/s | 1.4 | 279MB → 279MB |

"VACUUM 자체"는 서버 로그(`log_autovacuum_min_duration=0`)의 `elapsed`다 — 폴링과 무관한 값.
네 판 모두 한 일이 같다: `32,144 pages scanned · 450,000 tuples removed · index scans: 1 · WAL 100,570 records / 8.3MB`.
일이 같으니 시간 차이는 전부 `cost_delay`다.

## 읽는 법

1. **통제군.** 2ms 두 판은 24.58 / 25.67 — 폭 1.1s(4%). 0ms 두 판은 8.23 / 13.18 — 폭 5s(60%, 캐시·디스크 상태에 더 민감하다). 그래도 0ms의 나쁜 값(13.2)이 2ms의 좋은 값(24.6)보다 한참 아래라 방향은 확실하다.
2. **배수.** 2ms → 0으로 **2~3배** 빠르다.
3. **왜 그 정도인가 — 계산이 맞는다.** 로그의 buffer usage로 cost를 세면
   `hits 70,036×1 + misses 35,693×2 + dirtied 46,851×20 = 1,078,442` cost units.
   `cost_limit 200`마다 2ms 자므로 `5,392회 × 2ms ≈ 10.8s`를 **일부러 잔다.** 실측 차이 11~17s와 맞는다.
   나머지 8~13s가 실제 I/O다. 즉 이 크기에서 기본값은 **자는 시간이 일하는 시간과 비슷하다.**
4. **깨어남 34~52s**는 launcher 위상(naptime 60s)이라 설계상 잡음. 임계(50 + 20%)는 45만 삭제면 항상 넘는다.
5. **힙은 안 줄었다** (`pages: 0 removed`). 예상대로다 — 앞쪽 구멍이라 truncate 대상이 아니다(`docs/db/after-bulk-delete.sql` 머리말). `free_pct 89.8`은 그 공간이 재사용 가능으로 바뀌었다는 뜻.

## 780MB 사고에 대입하면

장애 #2의 `work_logs`(98만 삭제, 힙 780MB)는 이 판의 약 2.8배다. 선형으로 놓으면 기본값 **약 70s**, 0이면 **약 25~35s**.
**어느 쪽이든 1분 안팎이다.** 그 사고에서 5분·24분이 걸린 것은 autovacuum이 아니라 FK 인덱스 부재였고(P2-15-4·7), 밤 3의 40분 VACUUM(장애 #5)은 힙이 아니라 **HNSW 인덱스 청소**다 — 이 실험의 인덱스 두 개는 합쳐 1,824페이지라 비교 대상이 아니다.

## 판단 — A2는 "기본값 유지"를 추천한다

`cost_delay=0`은 2~3배 빠르지만 절대값으로 15s를 아끼는 것이고, 그동안 I/O를 3배로 쓴다(11 → 34 MB/s).
`work_logs`의 대량 삭제는 벤치 뒷정리·보존 정책뿐이라 **아무도 그 15초를 기다리지 않는다.** 반대로 I/O를 양보하지 않는
VACUUM은 같은 디스크의 앱 쿼리를 민다. 얻는 것이 없고 잃을 것만 있다. → `DB-IMPROVEMENT-PLAN.md` 4절에 한 줄.

바뀌는 조건: `document_chunks`(HNSW). 거기서는 인덱스 청소가 지배항이라 `cost_delay`가 아니라
`INDEX_CLEANUP`·`maintenance_work_mem`의 문제고, 별도 판이 필요하다.

## 이 실험이 부수적으로 드러낸 것 — E8 열화의 새 후보

시드(50만 INSERT) 직후 **아무것도 지우지 않았는데 autovacuum이 왔다** (`autovacuum_count`가 삭제 전에 이미 1).
PG13+의 INSERT 트리거(`autovacuum_vacuum_insert_threshold` 1,000 + 20%)다. rig는 그것이 지나가기를 기다린 뒤 삭제한다.

이것을 밤 2에 대입하면: 19만 청크를 3시간 연속 INSERT하면 **`document_chunks`에 INSERT 트리거 autovacuum이 여러 번 온다**
— 20%마다. 그리고 그 테이블엔 HNSW가 붙어 있어 한 번이 40분급이다(장애 #5). 색인 처리량이 후반에 1.8배 떨어진 것과
겹친다. `[미검증]` — 이슈 #42의 실험에 `bloat-snapshot`을 붙이면 `last_autovacuum`으로 바로 갈린다.

## rig를 만들며 걸린 것 (다음 사람용)

| 걸린 것 | 증상 | 처방 |
|---|---|---|
| entrypoint의 임시 서버 | `pg_isready` 성공 직후 `database system is shutting down` | `PostgreSQL init process complete` 로그를 먼저 기다린다 |
| `n_dead_tup`으로 종료 판정 | 17개가 남아 영원히 0이 안 됨("dead but not yet removable") | 로그 블록의 `tuples: N removed`로 판정 |
| INSERT 트리거 autovacuum이 삭제와 겹침 | 로그 줄 수가 늘어 8초 만에 "끝", `n_dead_tup`이 그 VACUUM에 덮여 0 | 삭제 전에 그 VACUUM이 끝나기를 기다린다 |
| 서버 로그 상세가 여러 줄 | 첫 줄만 grep하면 `elapsed`를 잃는다 | `docker logs` 통째로 보관, awk로 블록 파싱 |
| 컨테이너 이름 고정 | 앞선 실행의 `trap cleanup`이 뒤 실행의 컨테이너를 지움 | 이름에 실행 시각 |
| `psql -c "A; B"` | 한 트랜잭션이 되어 VACUUM 거부 | 문장마다 별도 psql |

## 파일

- `runs.csv` — 판별 요약 (위 표의 원본)
- `run{1..4}-timeline.csv` — 5초마다 `n_dead_tup`·진행 단계·힙
- `raw/run{N}-{before,deleted,after}.txt` — `bloat-snapshot.sql` 전·삭제직후·후
- `raw/run{N}-postgres.log` — 서버 로그 전체 (autovacuum 상세 포함)
- `env.md` — 조건
- `../autovac-20260915T141958/` — 1차 시도(폴링만, 서버 elapsed 없음). 방향은 같다(2ms 20~39s vs 0ms 11s). 참고용
