# 이 저장소에서 작업하는 에이전트에게

## 브랜치 — 새 작업은 `main`에서 브랜치를 따서, 끝나면 `main`으로 PR (2026-09-19)

- `main`에 직접 커밋하지 않는다. 기능·수정·문서 무엇이든 **`main`에서 브랜치를 새로 만들어** 거기서 작업한다.
- 이름은 지금까지의 관례를 따른다: `feat/…`, `fix/…`, `refactor/…`, `docs/…`, `measure/…` (예: `fix/safety-watch-status-validation`, `docs/readme-from-dev`).
- 끝나면 **`main`으로 PR**을 연다. 로컬 머지 대신 PR — CodeRabbit·CI가 PR에서 돈다. `dev`는 더 쓰지 않는다(2026-09-19 PR #123이 마지막 dev 릴리스. 그 전에는 브랜치→dev→main 두 단계였다).
- **PR도 커밋도 잘게.** 브랜치 하나 = 주제 하나 = PR 하나. 파일 여럿을 고쳤어도 주제별로 나눠 커밋한다. 100파일 넘는 PR은 CodeRabbit이 건너뛴다.
- 다른 PR 위에 쌓아야 하면(스택) base를 그 브랜치로 열고, 아래 PR이 머지되면 base를 `main`으로 옮긴다.
- 브랜치를 만들 때 워킹트리에 **남의 미커밋 변경**이 있으면 그것까지 따라온다. 커밋할 때 자기 파일만 고른다 — 같은 워킹트리에 세션이 둘일 수 있다. 체크아웃이 막히면 `git worktree add`로 따로 판다.

## 마이그레이션 번호 — PR을 열기 전에 열린 PR 전부의 번호를 본다 (2026-09-19, #138)

- Flyway는 `outOfOrder=false`·`validate`라 **버전이 겹치면 기동이 거부된다.** 세션 여럿이 동시에 PR을 열면 겹친다 — 2026-09-19 하루에 세 번 옮겼다(V10·V11·V12).
- `db/migration`에 파일을 만들기 전에 **main과 열린 PR 브랜치 전부**의 최고 번호를 보고 그 다음을 쓴다:
  ```bash
  git fetch -q origin
  for b in main $(gh pr list --json headRefName -q '.[].headRefName'); do
    printf '%s: ' "$b"; git ls-tree -r --name-only "origin/$b" src/main/resources/db/migration | grep -oE 'V[0-9]+' | sort -V | tail -1
  done
  ```
- 먼저 머지된 PR이 내 번호를 가져갔으면 **내 쪽을 옮긴다**(파일명만). 머지 순서가 정해진 스택이면 그 순서대로 번호를 잡는다.

## 이슈 — 문제를 발견하면 GitHub 이슈로 남긴다 (2026-09-17)

- 작업 중 발견한 **문제**(버그, 미결, 다음에 잴 것, 거둔 결정, 남의 코드에서 본 이상)는 답변이나 문서에만 적지 말고 **`gh issue create`로 이슈를 만든다.** 문서·PR 본문에는 이슈 번호를 적어 잇는다.
- 제목은 현상 한 줄(기존 이슈처럼: "무엇이 — 왜/조건"). 본문은 재현 조건 또는 근거(원 출력 경로, PR·커밋 번호), 아직 모르는 것, 다음에 할 일. 추측이면 `[추측]`로 표시한다.
- 자기 작업 범위 밖이어도 남긴다 — 고치지 않는 것과 적지 않는 것은 다르다. 고칠 거면 이슈 번호를 브랜치 작업에 달고, 닫을 땐 무엇으로 닫혔는지(PR 번호) 적는다.
- 라벨은 있는 것 중에서(`bug`, `enhancement`, `📝 Docs`, `🔧 Chore` …). 새 라벨은 만들지 않는다.

## AWS 측정 인스턴스 — 산출물은 끄기 전에 내려받고, 끝나면 terminate까지 눈으로 확인한다 (2026-09-19, #86)

- **정지(stop)해 두고 나중에 받지 않는다.** 밤 4 뒤와 밤 14 뒤, 정지해 둔 인스턴스가 EBS째 사라져 산출물을 잃었다(#86, 원인 미확인). 도는 동안 `scripts/e8-fetch-loop.sh`처럼 **주기적으로 내려받고**, 끝나면 마지막으로 받은 뒤 **terminate**한다.
- **세션을 끝내기 전에 `aws ec2 describe-instances`로 자기 인스턴스가 `terminated`인지 본다.** 볼륨(`describe-volumes`)도 0개인지. 밤 번호는 `docs/AWS-MEASUREMENT-RESULTS.md`의 색인 표에서 잡고, main에 먼저 들어간 밤이 있으면 내 번호를 옮긴다(밤 15 k6 / 밤 16 #83이 그랬다).
- 하드 watchdog(`shutdown -h +N`)은 건다 — 세션이 죽어도 요금이 안 새게. 단 그 정지는 stop이라 terminate는 사람이 한다.
- 밤 번호·인스턴스 ID·비용은 그 밤의 절 첫 문단에 적는다. 다른 세션이 같은 계정을 쓰므로 띄우기 전에 `describe-instances`로 vCPU 한도(32)를 확인한다.
