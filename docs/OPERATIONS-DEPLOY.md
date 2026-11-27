# 배포 — main 머지가 곧 배포다

- 작성: 2026-11-26
- 닫는 항목: `PRODUCTION-READINESS.md` R2(CD)·R3(환경 분리)·R5(롤백)·R7(graceful shutdown), R4(시크릿)는 절반
- 파일: `.github/workflows/cd.yml`, `scripts/deploy/deploy.sh`, `src/main/resources/application-prod.properties`, `ProdProfileGuard`

---

## 1. 흐름

```
PR 머지 → main push → CI(ci.yml) 통과
        → CD(cd.yml) image 잡: bootJar → docker build → <DOCKER_USERNAME>/dockin-app:<커밋 해시> 푸시
        → CD deploy 잡: compose.yaml·nginx·deploy.sh를 호스트로 복사 → deploy.sh <커밋 해시>
        → 호스트: 앱 컨테이너만 교체 → 헬스 대기 → healthy면 .env의 IMAGE_TAG 갱신 / 아니면 직전 태그로 되돌림
```

- 태그는 `git rev-parse --short HEAD`다. `build.gradle`의 buildInfo가 같은 명령을 쓰므로 `/actuator/info`의 `build.commit`과 이미지 태그가 같은 글자다.
- 테스트는 CD에서 다시 돌리지 않는다. CI가 성공한 커밋(`workflow_run.head_sha`)만 빌드한다.
- PR에서 돈 CI는 배포하지 않는다.

## 2. 처음 한 번 — 저장소와 호스트 준비

**GitHub (Settings → Secrets and variables → Actions)**

| 이름 | 종류 | 상태 (2026-11-26) | 용도 |
|---|---|---|---|
| `DOCKER_USERNAME` / `DOCKER_PASSWORD` | secret | 있음(2026-02-07) | 이미지 푸시. 값이 지금도 유효한지는 미확인 |
| `EC2_HOST` / `EC2_USERNAME` / `EC2_SSH_KEY` | secret | 있음(2026-02-07) | 배포 대상. **없으면 deploy 잡은 실패가 아니라 경고 후 건너뛴다** |
| `EC2_HOST_KEY` | secret | 없음 | `ssh-keyscan <호스트>` 한 줄. 없으면 처음 본 키를 믿는다(중간자 공격에 열림) — 넣는 것을 권한다 |
| `DEPLOY_DIR` | variable | 없음 | 기본 `/home/ubuntu/DOCKin-spring`(이전 워크플로의 경로) |

`environment: production`을 쓰므로 Settings → Environments → production에서 **승인자(required reviewers)** 를 걸면 배포 직전에 사람 확인을 받는다. 첫 실행 때 이 환경이 자동으로 생기고, 그때는 보호 규칙이 없다.

**호스트의 `.env`** — `.env.example`의 값 전부에 더해:

```
SPRING_PROFILES_ACTIVE=prod
CORS_ALLOWED_ORIGINS=https://실제-도메인
```

둘 중 하나라도 없으면 `deploy.sh`가 아무것도 건드리지 않고 끝난다. `IMAGE_TAG`·`PREVIOUS_IMAGE_TAG`·`DOCKIN_IMAGE`는 `deploy.sh`가 적는다.

**첫 배포 전에 호스트에서 확인할 것** — 2026-02 이후 호스트 상태를 아무도 보지 않았다.
1. `docker compose version`이 v2인가(v1이면 `deploy.resources` 메모리·CPU 상한이 무시된다. 스크립트가 경고한다)
2. 배포 디렉터리 이름이 그대로인가 — compose 프로젝트 이름이 디렉터리 이름에서 나오고, DB 볼륨 이름(`<프로젝트>_dockin_db_data`)이 거기 붙는다. 디렉터리를 바꾸면 **빈 DB로 뜬다**
3. 백업이 최근 것인가(`OPERATIONS-BACKUP.md`)

## 3. 환경 분리

| 프로파일 | 어디서 | 다른 점 |
|---|---|---|
| (없음) | 로컬 | Swagger 열림, CORS 기본값 localhost |
| `seed` | 로컬 | 표본 데이터. **prod와 같이 켜면 기동 거부**(`ProdProfileGuard`) |
| `prod` | 운영 | Swagger·API 문서 404, CORS 기본값 없음(없으면 기동 실패), 챗봇 스텁 고정 off, 기동 직후 색인 off |

- staging은 만들지 않았다. 서버가 한 대라 둘 자리가 없다. 생기면 `application-prod.properties`를 복사해 시작하고, 이 표에 행을 더한다.
- 이전에는 compose가 `SPRING_PROFILES_ACTIVE=secret`을 줬는데 `application-secret.properties`가 없었다 — 운영과 로컬이 같은 설정이었다.
- 시크릿(R4): 호스트 `.env`에만 있고 CD는 보내지 않는다. **누가 어떻게 바꾸는가**: SSH로 들어가 `.env`를 고친 뒤 `bash scripts/deploy/deploy.sh <현재 태그>`가 아니라 `docker compose -f compose.yaml up -d dockin-app`(설정만 다시 읽음). SSM Parameter Store로 옮기는 것은 남았다.

## 4. 하지 않는 것

- **무중단이 아니다.** `container_name`이 고정이라 옛 컨테이너를 내려야 새 것이 뜬다. 기동 동안(수십 초) 502. 배포는 사용이 적은 시간에. 블루-그린은 nginx upstream 둘 + 이름 없는 컨테이너가 필요하다 — 파일럿에서 배포 중 502가 실제로 문제가 되면 한다.
- **인프라 컨테이너를 바꾸지 않는다.** `--no-deps`로 앱만 바꾼다. compose.yaml에서 DB·Redis·임베딩 설정을 바꿨으면, 복사는 CD가 하지만 적용은 사람이 `docker compose -f compose.yaml up -d <서비스>`로 한다(DB 재시작을 자동으로 하지 않기 위해).
- **마이그레이션을 따로 돌리지 않는다**(PRODUCTION-READINESS D4). 인스턴스가 하나라 기동 시 Flyway가 한 번만 돈다. 둘이 되면 deploy 잡에 `flyway migrate`를 먼저 넣는다.

## 5. 롤백

```bash
# 호스트에서
bash scripts/deploy/deploy.sh --status      # 현재·직전 태그, 최근 이력
bash scripts/deploy/deploy.sh --rollback    # 직전 태그로
bash scripts/deploy/deploy.sh 1a2b3c4       # 특정 태그로
```

또는 Actions → CD → Run workflow에 tag를 넣는다(빌드 없이 그 이미지로 배포).

**마이그레이션이 이미 돌았으면** — 되돌리지 않는다(전진 수정). 옛 이미지가 새 스키마 위에서 뜨려면 두 가지가 필요하다.
1. Flyway가 "DB에만 있는 더 높은 버전"을 허용해야 한다 → `spring.flyway.ignore-migration-patterns`에 `*:future`(Flyway 기본값인데 `repeatable:missing`을 적으면서 사라져 있었다. 2026-11-26 복구)
2. Hibernate `validate`가 통과해야 한다 → 컬럼·테이블 **추가**는 괜찮고, **삭제·이름 변경·NOT NULL 추가**는 옛 코드를 깨뜨린다. 그런 변경은 두 번에 나눈다: 먼저 새 코드가 안 쓰게 배포, 다음 배포에서 스키마 제거.

## 6. 배포가 실패했을 때

| 증상 | 의미 | 할 일 |
|---|---|---|
| deploy 잡 "배포를 건너뛴다" 경고 | `EC2_HOST` 시크릿 없음 | 의도한 것이면 그대로. 이미지는 푸시됐다 |
| "SPRING_PROFILES_ACTIVE가 prod가 아니다" | 호스트 `.env` 미비 | 2절 |
| "…로 복구됨" | 새 이미지가 healthy가 안 됐고 직전 것으로 돌아갔다 | 잡 로그에 컨테이너 마지막 80줄이 있다. 서비스는 옛 버전으로 돈다 |
| "복구도 실패" | 직전 이미지도 안 뜬다 — 대개 DB·Redis 쪽 | `docs/db/incident-response.md`. `docker compose -f compose.yaml ps`부터 |

이력은 호스트의 `deploy-history.log`에 한 줄씩 남는다.

## 7. 재순위 켜기·끄기 (ADR-0013 2단계 4)

**품질 근거:** `measure/search-eval/modes-2026-11-27`(#220). E1 120문항 × 3언어에서 재순위가 Hit@5를 ko +0.11·en +0.09·vi +0.05, P@5를 +0.08~+0.14 올렸고 나빠진 칸이 없다.

> **지금은 켜지 않는다 — CPU 호스트에서는 느리다.** 2026-11-28 측정(`measure/search-eval/rerank-cpu-2026-11-28`, m7i CPU 2개): 후보 20개 p50 **8.4초**, 10개 4.3초, 5개 2.1초. 검색 SLO 2초(SLO 3-5)와 답변 10초(ADR-0013 3절)를 재순위 혼자 넘는다. 운영 호스트 i3-6100은 이보다 느리다. 후보를 5개로 줄이면 뽑힌 5개 안에서 순서만 바뀌어 품질 이득이 대부분 사라진다. GPU(T4)에서는 0.2~0.35초였다. **GPU 호스트를 두거나 더 작은 다국어 재순위 모델을 재서 정하기 전까지 `RERANK`는 끈다.** 아래는 켤 때의 절차다.

**스위치는 호스트 `.env`의 한 줄이다.** 코드 기본값은 꺼짐이다 — 로컬·CI·재순위 서버가 없는 호스트에서는 그대로 꺼져 있다.

```
RERANK=on
```

있으면 `deploy.sh`가 `compose.rerank.yaml`을 같이 읽는다. 재순위 서버(`dockin-reranker`, TEI CPU, `BAAI/bge-reranker-v2-m3`)를 띄우고 앱에 `RAG_SEARCH_RERANK_ENABLED=true`를 준다. 다음 배포 때 적용되고, 바로 적용하려면 현재 태그로 한 번 더 부른다.

```bash
bash scripts/deploy/deploy.sh "$(grep ^IMAGE_TAG= .env | cut -d= -f2)"   # 같은 태그면 "할 일 없음" — 그때는:
docker compose -f compose.yaml -f compose.rerank.yaml up -d dockin-reranker dockin-app
bash scripts/deploy/deploy.sh --status    # "재순위: 켜짐"
```

**켜기 전에 호스트에서 볼 것**

1. **메모리.** 재순위 서버 상한이 5G다(기동 예열 최대 4.9GB — `--max-batch-tokens=4096`일 때. 기본값이면 13.8GB까지 잡아 3~6G 상한에서 전부 죽었다). 지금 상한 합(앱 1G·DB 1G·임베딩 2G·nginx·Redis 각 100M ≈ 4.2G)에 더하면 약 9.2G다. `free -g`로 총량을 보고, 모자라면 켜지 않는다 — 같은 호스트의 DB가 같이 죽는다. 호스트 사양은 2026-02 이후 아무도 확인하지 않았다(2절).
2. **디스크.** 모델 가중치가 약 2.3G(`reranker_cache` 볼륨). 처음 한 번 받는다.
3. **compose v2.** v1이면 `deploy.resources` 상한이 무시되어 3G 상한도 없다(2절 1번).

**재순위 서버가 없거나 느리면:** `RerankClient`가 빈 값을 주고 `RetrievalService`가 재순위 전 순서로 답한다. 예외는 없다. 처음 기동해 모델을 받는 몇 분 동안도 그렇다. 응답 타임아웃은 5초(`external-api.reranker.response-timeout-ms`)라 서버가 멈춰 있으면 질문마다 최대 5초가 붙는다 — 그때는 끈다.

**지연:** 위 인용 상자. 챗봇 한 번에 LLM 호출(수 초)이 이미 있으므로 재순위 시간은 그 위에 더해진다. 켠 뒤에는 `/api/work-logs/search`·챗봇의 지연을 SLO 표와 대조한다.

**끄기:** `.env`에서 `RERANK=on` 줄을 지우고 `docker compose -f compose.yaml up -d dockin-app`(앱 설정만 다시 읽어 플래그가 꺼진다). 재순위 컨테이너는 남으므로 `docker stop dockin-reranker && docker rm dockin-reranker`.
