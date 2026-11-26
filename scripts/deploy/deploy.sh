#!/usr/bin/env bash
# 운영 호스트에서 앱 컨테이너를 지정한 이미지 태그로 바꾼다. 실패하면 직전 태그로 되돌린다.
#
#   bash scripts/deploy/deploy.sh <태그>        # 배포 (CD가 부른다)
#   bash scripts/deploy/deploy.sh --rollback    # 직전 태그로 (사람이 부른다)
#   bash scripts/deploy/deploy.sh --status      # 지금 무엇이 올라가 있는가
#
# 배포 디렉터리(compose.yaml과 .env가 있는 곳)에서 돈다. 절차와 전제는 docs/OPERATIONS-DEPLOY.md.
#
# [하는 일]
#  1. .env가 prod 프로파일인지 확인 — 아니면 아무것도 안 건드리고 끝낸다
#  2. 이미지를 받고 앱 컨테이너만 바꾼다(DB·Redis·임베딩은 건드리지 않는다)
#  3. 컨테이너 헬스체크가 healthy가 될 때까지 기다린다
#  4. healthy면 .env의 IMAGE_TAG를 그 태그로 적는다 — 사람이 손으로 `docker compose up`해도 같은 태그가 뜬다
#     unhealthy면 직전 태그로 다시 띄우고 실패로 끝난다
#
# [못 하는 것]
#  - 무중단이 아니다. container_name이 고정이라 옛 것을 내려야 새 것이 뜬다. 기동 시간(수십 초) 동안 502다.
#    인스턴스 하나·현장 근태 3 TPS(SERVICE-SCALE-ASSUMPTIONS 3-1)에서 고른 값이다. 배포는 사용이 적은 시간에.
#  - 마이그레이션을 되돌리지 않는다. 롤백은 "옛 코드가 새 스키마 위에서 돈다"가 지켜졌을 때만 된다(문서 5절).
set -euo pipefail

cd "$(dirname "$0")/../.."
DEPLOY_DIR=$(pwd)
APP_CONTAINER=dockin-app-1
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-240}   # compose 헬스체크 start_period 90s + retries 6 x 10s = 150s, 여유 포함
HISTORY=deploy-history.log

log() { printf '[deploy %s] %s\n' "$(date '+%F %T')" "$*"; }
die() { log "실패: $*"; exit 1; }

# docker 그룹에 없는 사용자면 sudo. 이전 워크플로가 sudo docker-compose를 썼다.
DOCKER=docker
if ! docker info >/dev/null 2>&1; then
  if sudo -n docker info >/dev/null 2>&1; then DOCKER="sudo docker"; else die "docker에 접근할 수 없다"; fi
fi
if $DOCKER compose version >/dev/null 2>&1; then
  COMPOSE="$DOCKER compose"
elif command -v docker-compose >/dev/null 2>&1; then
  # v1은 deploy.resources(메모리·CPU 상한)를 무시한다. 상한이 사라진 채 도는 것을 알리기만 한다.
  log "경고: docker compose v2가 없어 docker-compose(v1)를 쓴다 — 컨테이너 자원 상한이 적용되지 않는다"
  COMPOSE="${DOCKER/docker/docker-compose}"
else
  die "docker compose가 없다"
fi
# compose.override.yaml은 머신별 로컬 설정이라 운영에서는 읽지 않는다.
compose() { $COMPOSE -f compose.yaml "$@"; }

[ -f .env ] || die "$DEPLOY_DIR/.env가 없다"
env_get() { grep -E "^$1=" .env | tail -1 | cut -d= -f2- || true; }
env_set() {
  if grep -qE "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi
}

health() { $DOCKER inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$APP_CONTAINER" 2>/dev/null || echo missing; }

wait_healthy() {
  local waited=0 status
  while [ "$waited" -lt "$HEALTH_TIMEOUT" ]; do
    status=$(health)
    case "$status" in
      healthy) return 0 ;;
      unhealthy) return 1 ;;
    esac
    # restart: on-failure라 기동 실패면 컨테이너가 재시작을 반복한다. 그건 starting으로 보이므로 시간으로 끊는다.
    sleep 5; waited=$((waited + 5))
  done
  return 1
}

run_tag() {
  log "$1 받는 중"
  IMAGE_TAG=$1 compose pull dockin-app
  # --no-deps: DB·Redis 설정이 바뀌어도 여기서는 재생성하지 않는다. 인프라 변경은 손으로(문서 4절).
  IMAGE_TAG=$1 compose up -d --no-deps dockin-app
  log "$1 헬스 대기 (최대 ${HEALTH_TIMEOUT}초)"
  wait_healthy
}

case "${1:-}" in
  --status)
    echo "현재 태그:  $(env_get IMAGE_TAG)"
    echo "직전 태그:  $(env_get PREVIOUS_IMAGE_TAG)"
    echo "컨테이너:   $(health)"
    [ -f "$HISTORY" ] && tail -5 "$HISTORY"
    exit 0 ;;
  --rollback)
    TARGET=$(env_get PREVIOUS_IMAGE_TAG)
    [ -n "$TARGET" ] || die ".env에 PREVIOUS_IMAGE_TAG가 없다 — 되돌릴 대상이 없다" ;;
  ""|-*)
    die "사용법: deploy.sh <태그> | --rollback | --status" ;;
  *)
    TARGET=$1 ;;
esac

[ "$(env_get SPRING_PROFILES_ACTIVE)" = "prod" ] || die ".env의 SPRING_PROFILES_ACTIVE가 prod가 아니다 — 운영 설정 없이 띄우지 않는다"
[ -n "$(env_get CORS_ALLOWED_ORIGINS)" ] || die ".env에 CORS_ALLOWED_ORIGINS가 없다 — prod 프로파일은 이것 없이 기동하지 않는다"
# CD가 DOCKIN_IMAGE를 넘기면 .env에 적는다. 없으면 .env 값, 그것도 없으면 compose.yaml 기본값.
[ -n "${DOCKIN_IMAGE:-}" ] && env_set DOCKIN_IMAGE "$DOCKIN_IMAGE"

CURRENT=$(env_get IMAGE_TAG)
if [ "$CURRENT" = "$TARGET" ] && [ "$(health)" = "healthy" ]; then
  log "$TARGET가 이미 떠 있다 — 할 일 없음"; exit 0
fi

if run_tag "$TARGET"; then
  if [ "$CURRENT" != "$TARGET" ]; then env_set PREVIOUS_IMAGE_TAG "${CURRENT:-latest}"; fi
  env_set IMAGE_TAG "$TARGET"
  # nginx 등 나머지가 내려가 있으면(첫 배포, 재부팅 뒤) 올린다. 이미 떠 있고 설정이 같으면 아무것도 안 한다.
  IMAGE_TAG=$TARGET compose up -d
  printf '%s ok   %s <- %s\n' "$(date '+%F %T')" "$TARGET" "${CURRENT:-none}" >> "$HISTORY"
  log "완료: $TARGET"
  exit 0
fi

log "$TARGET가 healthy가 되지 않았다. 마지막 로그:"
$DOCKER logs --tail 80 "$APP_CONTAINER" 2>&1 || true
printf '%s FAIL %s (되돌림: %s)\n' "$(date '+%F %T')" "$TARGET" "${CURRENT:-none}" >> "$HISTORY"

if [ -n "$CURRENT" ] && [ "$CURRENT" != "$TARGET" ]; then
  log "$CURRENT로 되돌린다"
  if run_tag "$CURRENT"; then die "$TARGET 배포 실패, $CURRENT로 복구됨"; fi
  die "$TARGET 배포 실패, $CURRENT 복구도 실패 — 손으로 봐야 한다(docs/OPERATIONS-DEPLOY.md 6절)"
fi
die "$TARGET 배포 실패, 되돌릴 직전 태그가 없다"
