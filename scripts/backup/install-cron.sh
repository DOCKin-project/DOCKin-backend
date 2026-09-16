#!/usr/bin/env bash
#
# 매일 02:00에 db-backup.sh를 돌리는 crontab 항목을 넣는다. 두 번 돌려도 한 줄만 남는다.
# (docs/OPERATIONS-BACKUP.md)
#
#   ./scripts/backup/install-cron.sh                      # 로컬 디스크에만
#   S3_BACKUP_URI=s3://bucket/db ./scripts/backup/install-cron.sh   # + S3
#
# [02:00인 이유] 색인 cron이 03:00이다(IndexingService). 덤프는 MVCC라 색인과 겹쳐도
# 틀리지는 않지만 디스크를 같이 쓰므로 한 시간 앞에 둔다. 밤 측정(AWS-MEASUREMENT-PLAN)이
# 도는 날은 측정 스크립트가 cron을 끄므로 그날은 백업도 없다 -- 측정 결과에 그 사실을 적는다.
#
# [실패 알림] cron은 stdout/stderr를 메일로 보내려 하는데 EC2에는 MTA가 없다. 그래서 로그 파일로
# 보내고, 실패(exit != 0)면 backup.log에 FAIL 줄이 남는다. 그 줄을 보는 것은 아직 사람이다 --
# PRODUCTION-READINESS.md O2(알림)가 닫히면 여기에 붙인다.
#
set -euo pipefail

REPO_DIR=$(cd "$(dirname "$0")/../.." && pwd)
S3_BACKUP_URI=${S3_BACKUP_URI:-}
MARK="# dockin-db-backup"

# 리다이렉션(>> backups/db/cron.log)은 cron의 셸이 db-backup.sh를 실행하기 *전에* 연다.
# 스크립트 안의 mkdir은 그때 이미 늦다 -- 새 체크아웃이면 매일 02:00에 조용히 죽는다.
mkdir -p "$REPO_DIR/backups/db"

LINE="0 2 * * * cd $REPO_DIR && S3_BACKUP_URI=$S3_BACKUP_URI ./scripts/backup/db-backup.sh >> backups/db/cron.log 2>&1 $MARK"

( crontab -l 2>/dev/null | grep -v "$MARK" || true; echo "$LINE" ) | crontab -
echo "설치됨:"
crontab -l | grep "$MARK"
