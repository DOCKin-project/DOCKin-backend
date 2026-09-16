# E2 — 스트리밍 복제 standby 하나: 반영 지연·부하 중 지연·읽기 분리·promote

- 일시: 2026-09-15 16:40~16:47 KST, 로컬(Windows 10, i3-6100, Docker Desktop 29.1.2)
- rig: `scripts/db/replication-lab.sh` (기본값 `ROWS=300000 LOAD_SEC=60 PROBES=20`)
- 컨테이너: `pgvector/pgvector:pg17`(17.11) 둘, 각각 `--memory=512m`(운영과 같음), 같은 호스트의 도커 네트워크 하나. `dockin-db`는 건드리지 않았다
- 표: `work_logs` 꼴 30만 행(본문 500B, `user_id` 인덱스), DB 183MB. standby는 `pg_basebackup -R -C -S lab_slot -X stream`(복제 슬롯, 비동기)
- 관련: `docs/DB-IMPROVEMENT-PLAN.md` E2, `docs/adr/0004` 3-3(읽기 분리), `docs/adr/0008` 7-2(따라잡기), `docs/PG-BOOK-EXPERIMENTS.md` 2절 "복제 0건"

> **절대 시간을 옮겨 적지 말 것.** 같은 호스트의 두 컨테이너다 — 네트워크가 없다. 볼 것은 **자릿수**와 **부하 전후의 배수**다.
> 운영에서 standby가 다른 머신에 있으면 여기 값에 왕복 시간이 더해지고, 디스크가 다르면 replay 속도가 달라진다.

## 결과

| 항목 | 값 |
|---|---|
| 시드 30만 행 → DB 183MB | 23s |
| `pg_basebackup`(183MB, `-c fast`) → streaming | 40s |
| **반영 지연(유휴, n=20)** | **p50 81.5 · p95 340.6 · max 340.6 ms** (min 27.7) |
| **반영 지연(부하 중 프로브, n=8)** | **p50 381.7 · p95 856.5 · max 856.5 ms** |
| `replay_lag`(부하 중, `pg_stat_replication` 2초마다, n=8) | p50 280.5 · max 991.3 ms, 밀린 WAL 최대 9.1MB |
| 부하 | 60s 동안 2,000행 배치 INSERT 반복 → 506,000행(약 270MB). 끝난 뒤 **따라잡기 47s** |
| standby 읽기 / 쓰기 | `u1` 600행 조회 됨 / `cannot execute INSERT in a read-only transaction` |
| **promote → 쓰기 가능** | **10,826 ms** (`pg_ctl promote -w` + `pg_is_in_recovery()` 50ms 폴링 + `docker exec` 왕복 포함) |
| promote 뒤 | 옛 primary에 `k=1000001`, 새 primary에 `k=1000000` — **둘 다 자기가 primary라고 믿는다**. 옛 primary의 `pg_stat_replication`은 0행 |

"반영 지연"은 standby 세션 안에서 잰다: `dblink`로 primary에 표식 행을 넣고(커밋 시각을 행에 적는다) 같은 세션에서
그 행이 보일 때까지 2ms 간격으로 돈 뒤 `clock_timestamp() − sent_at`. 같은 호스트라 시계가 같고 `docker exec` 왕복은 측정 밖이다.

## 읽는 법

1. **유휴 지연은 수십~수백 ms다.** 비동기 복제의 기본값(`wal_receiver`가 받고 → 디스크에 쓰고 → replay)이 그 자릿수다.
   20회 중 17회가 200ms 아래고, 340ms 한 번은 잡음이 아니라 꼬리다 — `flush_lag`가 `write_lag`보다 한참 큰 순간이 부하 중에도
   보인다(t=28: write 3.7 / flush 815 / replay 815). standby의 **fsync**가 꼬리를 만든다. 운영 디스크(EBS gp3)에서 이 꼬리는 여기와 다를 것이다.
2. **부하 중엔 4~5배(p50 81 → 382ms), 꼬리는 1초 가까이.** 그리고 밀린 WAL이 최대 9MB로 "standby가 primary의 쓰기 속도를 못 따라간다"가
   숫자로 보인다 — 부하 60초 동안 밀렸다 풀렸다를 반복했다(0 → 9.1MB → 0 → 8.8MB → 2.3MB). 같은 512MB·같은 디스크인데도 그렇다.
   replay는 단일 프로세스고 primary의 INSERT는 병렬이 아니어도 replay보다 빠르다.
3. **부하가 끝난 뒤 따라잡기 47s의 내역은 못 갈랐다.** 부하 마지막 표본(t=63)의 backlog는 2.2MB였는데 0이 되기까지 47초다.
   그 창(16:45:24~16:46:11)에 primary의 `checkpoint starting: time`(16:46:08)이 있고, standby 로그는 rig 버그로 잃었다(아래 표).
   추정은 "standby의 restartpoint·fsync가 replay를 세웠다"이지만 `[미검증]` — rig를 고쳐 따라잡는 동안에도 2초마다 backlog를 찍게 했다. 다음 판에서 갈린다.
4. **promote 10.8초 중 PostgreSQL 몫이 얼마인지도 못 갈랐다.** `pg_ctl promote -w`는 서버가 recovery를 끝낼 때까지 기다리고, 그 앞뒤로
   `docker exec` 두 번(Windows Docker Desktop에서 왕복 수백 ms)과 50ms 폴링이 붙는다. 자릿수는 **초 단위**고, 그 사이 쓰기는 전부 실패한다 —
   ADR-0001의 멱등 재시도가 있어야 그 초를 넘긴다.
5. **split brain은 promote 직후 바로 생긴다.** 옛 primary를 죽이지 않으면 두 DB가 다른 미래를 산다. rig는 그것을 일부러 만들어 숫자로 남겼다(k=1000000 vs 1000001).
   운영에서 promote는 "옛 primary 격리 → promote → 앱 접속 전환" 순서고, 이 rig는 그 첫 단계를 뺀 것이다.

## ADR-0004 3-3(읽기 분리)·ADR-0008(따라잡기)에 대입하면

- **채팅 따라잡기 `after?seq=`는 standby로 보내면 안 된다.** 재접속 순간 primary에 커밋됐지만 standby에 아직 없는 메시지(유휴 80~340ms, 부하 중 최대 850ms 어치)는
  따라잡기 응답에도 없고 실시간 전파는 끊긴 사이 지나갔으니 **양쪽에서 빠진다**. 7-2의 M6는 primary 한 대에서 유실 0이었고, 그 조건이 깨진다.
  한 방의 저장이 약 23ms/건(7-1)이니 340ms면 15건, 850ms면 37건이 그 창에 들어간다. `roomSeq` 구멍 감지로 다시 부르는 보정이 없는 한 primary 고정.
- **근태 `clockin` 직후 `getMyAttendanceRecords`도 같은 꼴이다** — 방금 쓴 것을 바로 읽는 경로(read-after-write)는 standby에서 수백 ms 동안 옛 값을 준다.
  3-3이 읽기 분리 후보로 든 조회 API 중 **작업일지 목록·타인 조회·검색**처럼 방금 쓴 것을 바로 보지 않는 것만 standby로 갈 수 있다. 그것도 "출근 버스트 중"이 전제인데,
  버스트는 곧 쓰기 부하고 그때 지연이 4~5배·1초 꼬리다 — 3-3이 기대한 "버스트 중 조회 보호"가 정확히 지연이 가장 나쁜 순간이다.
- **결론(ADR-0004 3-3에 반영할 것)**: 읽기 분리는 "어느 API를"이 아니라 "**어느 API가 stale을 견디나**"로 다시 물어야 한다. 지금 트래픽(SERVICE-SCALE-ASSUMPTIONS)에서
  그 답이 "없다"에 가깝고, 붙인다면 세션 단위 read-your-writes(쓴 직후 N초는 primary) 같은 라우팅이 앱에 들어와야 한다. 복제의 첫 용도는 읽기 분리가 아니라 **HA(promote)와 백업 오프로드**다.

## rig를 만들며 걸린 것 (다음 사람용)

| 걸린 것 | 증상 | 처방 |
|---|---|---|
| `docker exec -i` 둘이 겹침 | 한쪽이 20초 넘게 멈춘다 (Windows Docker Desktop, stdin 공유 추정) | `-i`는 heredoc 먹이는 곳만. 프로브는 standby 한 세션 안에서 `dblink`로 primary에 쏘고 같은 세션에서 기다린다 |
| `docker exec -d`로 띄운 postgres | `docker logs`에 아무것도 안 잡힌다 (PID 1이 아니다) | 파일로 보내고 끝에 `cat` |
| 그 `cat /tmp/standby.log` | Git Bash가 `/tmp`를 `C:/Users/.../Temp`로 바꿔 넣어 **standby 로그를 통째로 잃었다** | `sh -c 'cat /tmp/...'`로 감싼다 (MSYS 경로 변환은 첫 인자에만 온다) — 고침 |
| `pg_basebackup` 기본 체크포인트 | 시작 전 `checkpoint_timeout`(5분)까지 기다린다 | `-c fast` |
| 이미지의 `$PGDATA`가 VOLUME | `rm -rf $PGDATA` → `Device or resource busy` | `find -mindepth 1 -delete`로 안만 비운다 |
| 이미지 `pg_hba.conf` | `host all all all`만 있어 replication 가상 DB는 거부 | `host replication replicator all scram-sha-256` 한 줄 + `pg_reload_conf()` |
| 따라잡기 동안 표본 없음 | 47초의 내역을 못 갈랐다 (위 3) | 따라잡는 동안에도 `load-lag.csv`에 2초마다 — 고침 |
| promote 뒤 옛 primary의 슬롯 | 확인 못 함 (rig가 안 찍었다). 슬롯이 남으면 옛 primary가 **WAL을 영원히 보관**한다 — 디스크가 찬다 | `pg_replication_slots`를 promote 뒤에 찍는다 — 고침. 운영 절차엔 "옛 primary 슬롯 드롭" |

## 파일

- `summary.md` — rig가 계산한 요약(위 표의 원본)
- `idle-lag.csv` — 유휴 프로브 20회
- `load-lag.csv` — 부하 중 2초마다 `write/flush/replay_lag`·밀린 바이트
- `load-probe.csv` — 부하 중 5초마다 프로브
- `raw/basebackup.log`, `raw/load.log`, `raw/promote.log`, `raw/replication-{initial,after-promote}.txt`, `raw/primary-postgres.log`
- `raw/standby-postgres.log` — **비어 있다**(위 표 셋째 줄)
- `run.log`, `env.md` — 타임라인·조건
