#!/usr/bin/env bash
#
# 측정 인스턴스 부트스트랩 (Amazon Linux 2023 / docs/AWS-MEASUREMENT-PLAN.md 밤 0)
#
#   빈 EC2를 "색인을 시작할 수 있는 상태"까지 만든다. 색인 자체는 시작하지 않는다 --
#   순서가 측정의 일부이기 때문이다(계획 8-3-1). 이 스크립트는 준비까지만 하고
#   다음에 칠 명령을 찍어 준다.
#
# [실행]
#   curl -fsSL <이 파일> -o bootstrap.sh   # 또는 scp
#   REPO_URL=https://<토큰>@github.com/DOCKin-project/DOCKin-backend.git \
#   SEED_CORPUS_SIZE=165000 bash bootstrap.sh
#
# [다시 돌려도 안전하다] 각 단계가 이미 된 것을 건너뛴다. 중간에 죽으면 그냥 다시 돌린다.
#
set -euo pipefail

REPO_URL=${REPO_URL:-}
REPO_DIR=${REPO_DIR:-$HOME/DOCKin-spring}
SEED_CORPUS_SIZE=${SEED_CORPUS_SIZE:-}          # 주면 원본 코퍼스까지 만든다
COMPOSE_VERSION=${COMPOSE_VERSION:-v2.32.4}
COMPOSE_FILES="-f compose.yaml -f compose.gc.yaml"

say()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
info() { printf '   %s\n' "$*"; }
die()  { printf '\n[중단] %s\n' "$*" >&2; exit 1; }

# ── 0. 도커 그룹 -------------------------------------------------------------
# usermod로 그룹에 넣어도 "현재 셸"에는 반영되지 않는다. 재로그인을 요구하는 대신
# 그룹을 적용한 채 자기 자신을 다시 실행한다. 한 번만 하도록 플래그로 막는다.
if [[ "${BOOTSTRAP_REEXEC:-0}" != "1" ]] && ! docker info >/dev/null 2>&1; then
    if command -v docker >/dev/null && sudo docker info >/dev/null 2>&1; then
        say "도커 그룹을 적용해 다시 실행한다"
        export BOOTSTRAP_REEXEC=1
        exec sg docker -c "$(printf '%q ' bash "$0" "$@")"
    fi
fi

# ── 1. 패키지 ---------------------------------------------------------------
say "패키지"
if ! command -v docker >/dev/null; then
    sudo dnf install -y -q docker git java-21-amazon-corretto-devel tar
    sudo systemctl enable --now docker
    sudo usermod -aG docker "$USER"
    info "도커 설치 완료 — 그룹 반영을 위해 스크립트를 다시 실행한다"
    export BOOTSTRAP_REEXEC=0
    exec sg docker -c "$(printf '%q ' bash "$0" "$@")"
fi
command -v git  >/dev/null || sudo dnf install -y -q git
command -v java >/dev/null || sudo dnf install -y -q java-21-amazon-corretto-devel tar
info "docker $(docker --version | awk '{print $3}' | tr -d ,) / java $(java -version 2>&1 | head -1 | cut -d'"' -f2)"

# compose v2는 AL2023 기본 저장소에 없다. CLI 플러그인으로 직접 넣는다.
if ! docker compose version >/dev/null 2>&1; then
    say "docker compose 플러그인"
    sudo mkdir -p /usr/libexec/docker/cli-plugins
    sudo curl -fsSL \
        "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-x86_64" \
        -o /usr/libexec/docker/cli-plugins/docker-compose
    sudo chmod +x /usr/libexec/docker/cli-plugins/docker-compose
fi
info "compose $(docker compose version --short)"

# ── 2. 저장소 ---------------------------------------------------------------
say "저장소"
if [[ -d "$REPO_DIR/.git" ]]; then
    git -C "$REPO_DIR" pull --ff-only && info "갱신: $(git -C "$REPO_DIR" log -1 --oneline)"
else
    [[ -n "$REPO_URL" ]] || die "REPO_URL이 필요하다. 비공개 저장소라 토큰이 든 URL이나 배포 키를 준다.
       예: REPO_URL=https://<PAT>@github.com/DOCKin-project/DOCKin-backend.git"
    git clone --depth 50 "$REPO_URL" "$REPO_DIR"
    info "클론: $(git -C "$REPO_DIR" log -1 --oneline)"
fi
cd "$REPO_DIR"

# ── 3. .env -----------------------------------------------------------------
# 이 측정에 진짜 값이 필요한 것은 DB_PASSWORD 하나다. JWT와 AI/S3는 앱이 뜨는 데
# 필요할 뿐 색인 경로가 쓰지 않는다. 그래서 시크릿 전달(P2-11-6)이 오늘 밤을 막지 않는다 --
# 여기서 만들어 쓰고 인스턴스와 함께 버린다.
say ".env"
if [[ -f .env ]]; then
    info "이미 있다 — 건드리지 않는다"
else
    cat > .env <<EOF
# 측정 전용. 진짜 서비스 값이 아니다 (scripts/aws-bootstrap.sh가 생성).
DB_PASSWORD=$(openssl rand -hex 16)
JWT_SECRET=$(openssl rand -hex 48)
JWT_EXPIRATION=3600000
JWT_REFRESH_EXPIRATION=604800000
AI_SERVER_URL=http://localhost:9999
S3_BUCKET_NAME=unused-in-measurement
AWS_ACCESS_KEY=unused
AWS_SECRET_KEY=unused
AWS_REGION=ap-northeast-2
EOF
    chmod 600 .env
    info "생성했다 (DB_PASSWORD/JWT는 난수)"
fi

# 밤 1의 전제. 이것이 없으면 번역본까지 색인되어 E4의 기준선이 오염된다.
if grep -q '^RAG_INDEXING_TRANSLATIONS_ENABLED=' .env; then
    info "번역본 색인 스위치: $(grep '^RAG_INDEXING_TRANSLATIONS_ENABLED=' .env)"
else
    echo 'RAG_INDEXING_TRANSLATIONS_ENABLED=false' >> .env
    info "번역본 색인을 껐다 (밤 1은 off 기준선을 만든다)"
fi

# ── 4. 빌드 -----------------------------------------------------------------
# bootJar를 먼저 돌리지 않으면 Dockerfile이 옛 jar를 COPY한다. P2-15-9가 겪은
# "이미지가 옛것이면 Flyway는 정상 동작하면서 옛 상태를 유지한다"가 그 자리다.
say "빌드"
./gradlew bootJar -q --console=plain
info "jar: $(ls -lh build/libs/*.jar | awk '{print $9, $5}')"
docker compose $COMPOSE_FILES build dockin-app
docker compose $COMPOSE_FILES pull -q DOCKin-DB dockin-embedding dockin-redis dockin-nginx
info "이미지 준비 완료 (TEI 모델 가중치는 첫 기동에서 내려받아 embedding_cache에 남는다)"

# ── 5. DB·TEI 기동 ----------------------------------------------------------
# 앱은 아직 띄우지 않는다. compose.gc.yaml이 기동 즉시 색인을 켜므로,
# 코퍼스가 없는 상태에서 띄우면 아무 일도 없이 한 바퀴 돌 뿐이다.
say "DB · TEI 기동"
docker compose $COMPOSE_FILES up -d DOCKin-DB dockin-embedding dockin-redis
for i in $(seq 1 60); do
    docker compose $COMPOSE_FILES exec -T DOCKin-DB pg_isready -U root -d dockindb >/dev/null 2>&1 && break
    sleep 2
done
docker compose $COMPOSE_FILES exec -T DOCKin-DB pg_isready -U root -d dockindb >/dev/null 2>&1 \
    || die "DB가 준비되지 않았다"
info "DB 준비됨"

# 앱이 한 번 떠야 Flyway가 스키마를 만든다. 코퍼스 생성기는 그 테이블에 넣는다.
# 색인은 코퍼스가 비어 있어 즉시 끝난다.
say "스키마 (앱을 한 번 띄워 Flyway를 돌린다)"
docker compose $COMPOSE_FILES up -d dockin-app
for i in $(seq 1 90); do
    [[ "$(docker inspect -f '{{.State.Health.Status}}' dockin-app-1 2>/dev/null)" == "healthy" ]] && break
    sleep 3
done
[[ "$(docker inspect -f '{{.State.Health.Status}}' dockin-app-1 2>/dev/null)" == "healthy" ]] \
    || die "앱이 healthy가 되지 않았다. docker compose $COMPOSE_FILES logs dockin-app | tail -50"
info "마이그레이션: $(docker compose $COMPOSE_FILES exec -T DOCKin-DB \
        psql -U root -d dockindb -qtAX -c 'SELECT count(*) FROM flyway_schema_history;' | tr -d '\r') 개 적용"
docker compose $COMPOSE_FILES stop dockin-app >/dev/null
info "앱은 다시 내렸다 — 색인은 코퍼스를 넣은 뒤에 시작한다"

# ── 6. 원본 코퍼스 (선택) ----------------------------------------------------
if [[ -n "$SEED_CORPUS_SIZE" ]]; then
    say "원본 코퍼스 $SEED_CORPUS_SIZE 건"
    DB_PASSWORD=$(grep '^DB_PASSWORD=' .env | cut -d= -f2-) \
    SEED_CORPUS_SIZE="$SEED_CORPUS_SIZE" \
        ./gradlew test --tests "*CorpusSeedGeneratorTest*" -q --console=plain
    info "work_logs: $(docker compose $COMPOSE_FILES exec -T DOCKin-DB \
            psql -U root -d dockindb -qtAX -c 'SELECT count(*) FROM work_logs;' | tr -d '\r') 행"
    info "청크: $(docker compose $COMPOSE_FILES exec -T DOCKin-DB \
            psql -U root -d dockindb -qtAX -c 'SELECT count(*) FROM document_chunks;' | tr -d '\r') 개 (0이어야 한다)"
fi

# ── 끝 -----------------------------------------------------------------------
say "준비 완료 — 여기부터는 손으로 친다 (순서가 측정의 일부다)"
cat <<'EOF'

  cd ~/DOCKin-spring

  # 0) 연습 주행. 흐름이 한 번 지나가야 밤을 건다.
  WINDOW_SEC=60 CONDITIONS="2.0 3.0" ./scripts/e1-tei-cpu-sweep.sh

  # 1) 샘플러 먼저. 색인보다 늦으면 E8 곡선의 왼쪽 끝이 없다.
  ./scripts/e8-index-sampler.sh &

  # 2) 색인 시작 (상한 2.0 고정)
  docker compose -f compose.yaml -f compose.gc.yaml up -d dockin-app

  # 3) 두어 시간 뒤 위치 P에서 스윕. SHUTDOWN_WHEN_DONE은 주지 않는다.
  ./scripts/e1-tei-cpu-sweep.sh

  # 4) 이긴 상한으로 남은 색인 완주 (스크립트는 앱을 내려둔 채 끝난다)
  docker update --cpus=<이긴 값> dockin-embedding
  docker compose -f compose.yaml -f compose.gc.yaml start dockin-app

  # 5) 아침에 샘플러를 멈추면 구간별 표를 찍는다
  kill %1

EOF
info "결과는 measure/ 아래에 남는다. S3 업로드는 인스턴스 역할이 없어 이번 밤에는 끈다."
