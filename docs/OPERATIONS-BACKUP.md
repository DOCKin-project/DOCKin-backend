# DB 백업과 복구 — 복구를 해 본 것까지가 백업이다

- 작성: 2026-09-14
- 관련: `PRODUCTION-READINESS.md` 2절 D1·D2, `scripts/backup/`, `compose.yaml`(DOCKin-DB)
- 한 줄: **매일 02:00 `pg_dump`, 복구는 옆에 풀어서 대조한 뒤 바꿔치기.** 리허설 30,000청크 기준 덤프 38초·복구 2분 23초·유실 0행.

---

## 1. 지금까지는 없었다

`PRODUCTION-READINESS.md`가 D1을 ❌로 적었다 — "로드맵 D9가 포폴 값어치 낮음으로 미뤘다". 볼륨 `dockin_db_data` 하나가 전부였고, 그 디스크가 죽으면 근태·휴가·작업일지·채팅이 전부 사라지는 상태였다. 코퍼스(`document_chunks`)는 원본에서 다시 만들 수 있지만 원본은 아니다.

## 2. 무엇을 어떻게

| | 값 | 근거 |
|---|---|---|
| 방식 | `pg_dump -Fc` (custom 포맷, 압축) | 테이블 단위 선택 복구가 되고 `--list`로 내용을 열어볼 수 있다. "만들어졌다"와 "읽힌다"를 갈라 확인하려면 이쪽이어야 한다 |
| 어디서 | DB 컨테이너 안에서 (`docker exec dockin-db pg_dump`) | 호스트에 pg_dump가 없어도 되고 서버와 같은 메이저 버전이 보장된다 |
| 언제 | 매일 02:00 (`install-cron.sh`) | 색인 cron 03:00 한 시간 앞. 디스크를 같이 쓰지 않게 |
| 어디에 | `backups/db/dockindb-<ts>.dump` + `.sha256`, 최신 14개 | 하루 하나면 2주 |
| 오프사이트 | `S3_BACKUP_URI`가 있으면 `aws s3 cp --sse AES256` | 같은 디스크의 백업은 디스크와 같이 죽는다 |
| 검증 | 덤프 직후 `pg_restore --list`로 항목 수·TABLE DATA 수 | 0이면 실패로 처리하고 파일을 지운다 — 반쪽 파일이 "최신 백업"으로 보이는 것이 없는 것보다 나쁘다 |
| 기록 | `backups/db/backup.log` 한 줄/회 | `OK`/`FAIL`, 바이트, 항목 수, 소요 초 |

**RPO는 24시간이다.** 하루치 데이터는 잃을 수 있다. 그것을 줄이는 것이 5절이다.

## 3. 복구 절차

```bash
# 1. 앱을 내린다 (덤프 이후 들어온 요청이 복구본에 섞이지 않게)
docker compose stop dockin-app

# 2. 옆에 풀어서 대조만 본다 (원본은 건드리지 않는다)
./scripts/backup/db-restore.sh backups/db/dockindb-20260914T020000.dump
#    → 테이블별 행 수가 "복구본 | 현재 DB | 차이"로 나온다. 차이가 곧 잃는 양이다.

# 3. 그 숫자를 보고 결정했으면 바꿔치기
./scripts/backup/db-restore.sh backups/db/dockindb-20260914T020000.dump --swap
#    → dockindb → dockindb_old_<ts>, 복구본 → dockindb. RENAME 두 번이라 수 ms.

# 4. 앱을 올린다. Flyway가 validate로 스키마를 다시 확인한다.
docker compose start dockin-app
```

되돌리기: 앱 내리고 RENAME을 반대로. `_old_` DB는 스크립트가 지우지 않는다 — 복구가 잘못됐을 때 되돌릴 유일한 길이라, 지우는 것은 사람이 확인한 뒤다.

**왜 제자리에 덮어쓰지 않나.** `DROP → 풀기`는 그 사이에 DB가 없고, 풀다가 실패하면 원본도 복구본도 없다. 옆에 풀면 실패해도 원본은 그대로다. `pg_restore --exit-on-error`라 반쯤 들어온 DB도 만들지 않는다.

## 4. 리허설 — 2026-09-14, 로컬

`pgvector/pgvector:pg17` 컨테이너에 V1~V6 마이그레이션과 `R__seed_sample.sql`을 넣고, `document_chunks`에 행마다 다른 384차원 랜덤 벡터 30,000건을 더했다. DB 크기 130 MB.

| 단계 | 결과 |
|---|---|
| 덤프 | 30,851,330 bytes, 145 항목 / TABLE DATA 22, **38초** |
| 복구 (옆에 풀기) | **143초**. HNSW 인덱스(`idx_chunk_embedding_hnsw`) 재생성 포함 |
| 대조 | 22개 테이블 **행 수 차이 0**. `document_chunks` 30,000 = 30,000 |
| 벡터 | 청크 1·15,000·30,000의 `embedding::text` md5가 원본과 같다 |
| `--swap` | RENAME 두 번, 옛 DB `dockindb_old_<ts>` 남음, 확인 뒤 삭제 |

> **첫 시도는 틀린 데이터로 쟀다.** 벡터를 만드는 서브쿼리가 바깥 행과 상관되지 않아 30,000행이 **같은 벡터**를 갖고 있었고, 그래서 덤프가 1.7 MB(압축률 55배)에 복구가 25초였다. 상관시켜 다시 만들자 30.9 MB·143초가 됐다. 숫자가 좋아 보이면 먼저 의심한다 — 6-8절에서 "12ms/건"이 틀렸던 것과 같은 종류다.

**운영 규모로 외삽하면** — 밤 측정의 코퍼스가 원본 165,016건, 원본당 청크 1.22(가정표 6-9)라 약 200,000청크, 리허설의 6.7배. 선형이면 덤프 약 4분·복구 약 16분. HNSW 재생성은 선형보다 나쁠 수 있어 **복구는 20~30분으로 잡는다** — 이것이 지금의 RTO다. [측정 필요]: 운영 볼륨에서 한 번 리허설해 이 줄을 실측으로 바꾼다.

## 5. 다음 — RPO를 줄이려면

| 항목 | 무엇 | 사는 것 | 대가 |
|---|---|---|---|
| WAL 아카이브 + `pg_basebackup` (PITR) | `archive_mode=on`, `archive_command`로 WAL을 볼륨/S3에, 주 1회 베이스 백업 | RPO 24시간 → **분 단위**, 임의 시점 복구 | WAL 볼륨(쓰기량에 비례), 복구 절차가 한 단계 더(`recovery_target_time`), `compose.yaml` DB command에 인자 추가 |
| S3 버킷 버전관리 (D2) | `aws s3api put-bucket-versioning --bucket $S3_BUCKET_NAME --versioning-configuration Status=Enabled` + 수명주기 | 작업일지 사진·휴가 증빙의 실수 삭제 복구 | 저장 비용 약간 |
| 백업 실패 알림 | `backup.log`의 `FAIL` → 알림 | 실패를 사람이 로그를 열어야 아는 상태에서 벗어남 | O2(알림 4개)와 함께 |

PITR을 지금 안 한 이유: 가정표 3-1이 근태 3 TPS, 3-3이 채팅 30 msg/s다. **하루 유실이 실제로 얼마인지**를 파일럿(H1)에서 보고 정한다. 채팅이 붙으면 24시간이 너무 길 수 있고, 그때 위 표의 첫 줄을 한다.

## 6. 파일

| 파일 | 역할 |
|---|---|
| `scripts/backup/db-backup.sh` | 덤프 + 읽힘 확인 + 체크섬 + (S3) + 회전 + 로그 |
| `scripts/backup/db-restore.sh` | 옆에 풀기 + 테이블별 대조. `--keep` 남김, `--swap` 바꿔치기(앱이 떠 있으면 거부) |
| `scripts/backup/install-cron.sh` | 02:00 crontab 한 줄. 두 번 돌려도 한 줄 |
| `backups/` | `.gitignore`. 덤프는 저장소에 넣지 않는다 |
