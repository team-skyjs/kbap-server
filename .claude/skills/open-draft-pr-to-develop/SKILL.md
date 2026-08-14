---
name: open-draft-pr-to-develop
description: "kbap-server 에서 현재 feature 브랜치를 base=develop 으로 draft Pull Request 를 여는 절차 — 커밋(없으면)→푸시(-u)→develop 대상 draft PR 생성을 표준 컨벤션(제목·본문·Jira 링크·Co-Authored-By/Generated 라인)으로 수행한다. 'develop 으로 draft PR 열어줘', 'PR 초안', 'open draft pr', '작업 PR로 올려줘' 요청 시 사용."
---

# develop 으로 Draft PR 열기

현재 feature 브랜치의 작업을 **base `develop` 대상 draft Pull Request** 로 올리는 표준 절차. 커밋부터 PR 생성까지 한 흐름으로 처리하고, 프로젝트 git 전략(develop+main, ADR-0007 — feature 는 develop 으로 PR)·커밋/PR 규약을 강제한다.

## 사전 조건

- `main`/`develop` 에서 직접 작업하지 않는다 — feature 브랜치(`kb-<nn>-slug`, [git-branch-strategy §1.1](../../../docs/guides/git-branch-strategy.md))에 있어야 한다. 아니면 먼저 브랜치를 판다.
- 변경이 검증된 상태(테스트/빌드 그린)여야 한다 — draft 라도 깨진 채 올리지 않는다(`./gradlew build` 확인).
- `gh` CLI 인증이 되어 있어야 한다.

## 절차 (체크리스트)

1. **상태 확인** — `git status --short` 로 변경/미추적 파일을 파악한다. `_workspace/` 등 gitignore 대상이 섞이지 않는지 `git check-ignore` 로 확인한다. 사전부터 있던 무관한 변경(`CLAUDE.md`·`.specify/feature.json` 등 SpecKit 포인터는 feature 의 일부라 포함)은 `git diff` 로 내용 확인 후 포함 여부를 판단한다.

2. **커밋(미커밋이면)** — 작업/논리 단위로 커밋한다. 파일이 스토리 간 크게 겹치면 단일 feature 커밋이 더 정직하다(중간 깨진 상태 방지). 메시지는 **한국어 Conventional Commits**(`feat(scope): 요약` — **제목 끝에 이슈/PR 번호를 붙이지 않는다**) + 본문(모듈별 변경·테스트 요지)으로 쓰고, 본문은 스크래치패드 파일에 작성해 `git commit -F` 로 넣는다. 메시지 끝에 반드시:
   ```
   Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
   ```

3. **푸시** — 업스트림이 없으면 `-u` 로:
   ```bash
   git push -u origin <feature-branch>
   ```

4. **base 브랜치 결정** — feature PR 의 base 는 **`develop`**(통합 브랜치, git-branch-strategy/ADR-0007). `main` 은 릴리스용이라 feature PR 의 base 로 쓰지 않는다. `git ls-remote --heads origin` 으로 `develop` 존재를 확인한다.

5. **PR 본문 작성** — **`.github/PULL_REQUEST_TEMPLATE.md` 포맷을 그대로 따른다**(섹션·순서 고정). 스크래치패드에 마크다운으로 채워 `--body-file` 로 전달(셸 이스케이프 회피). 고정 섹션:
   - `### Issue Number` — **닫는 이슈마다 `- close #이슈번호`**(예: `- close #15`). **후속/참조 이슈는 `close` 없이 `#번호` 만**. (default 브랜치 머지 시 종료 — base 가 develop 이어도 develop→main 도달 시 닫힘.)
   - **Jira 링크(태스크가 Jira 로 추적되면)** — 본문 상단에 `> **Jira:** [KB-NN](https://<site>.atlassian.net/browse/KB-NN)` 를 넣고 본문에 `Refs KB-NN` 을 적는다. 대응 GitHub 이슈가 없으면 `### Issue Number` 의 `- close #` 는 비운다(Jira 로만 추적). **Jira 이슈에는 별도 완료/DoD 코멘트를 달지 않는다** — PR 본문 링크로 충분하다(GitHub for Jira 앱 설치 시 커밋의 `KB-NN` 로 Development 패널 자동 연동).
   - `## 무엇을 / 왜`(문제·해결 2~4문장, 파일 나열 금지) · `## 변경 사항`(커밋 단위가 아니라 기능·모듈 단위) · `## 기능 흐름`(동작 흐름이 바뀐 경우만 — mermaid flowchart 로 정상 흐름·중요 실패 분기, 흐름 변화 없으면 섹션 삭제)
   - **닫는 이슈가 있으면 `### Issue Number` 의 `close` 를 절대 빠뜨리지 않는다.**
   - Claude 가 작성한 PR 이면 본문 끝에:
     ```
     🤖 Generated with [Claude Code](https://claude.com/claude-code)
     ```

6. **draft PR 생성**:
   ```bash
   gh pr create --draft \
     --base develop \
     --head <feature-branch> \
     --title "feat(scope): 요약" \
     --body-file <scratchpad>/pr-body.md
   ```
   - **제목 끝에 이슈/PR 번호(`(#15)` 등)를 붙이지 않는다** — GitHub squash 머지가 squash 커밋 제목 끝에 PR 번호 `(#NN)` 를 자동으로 붙인다(수동으로 넣으면 `(#15) (#19)` 처럼 중복). 이슈 종료는 본문 `### Issue Number` 의 `close #번호` 가 담당한다.
   - 제목은 **브랜치 전체 결과**를 반영한다(초기 커밋만이 아니라 최종 산출 기준). 작업을 더 했으면 푸시 후 제목을 갱신한다.

7. **보고** — 생성된 PR 번호·URL·base/head·커밋 요약을 사용자에게 전달한다. "Ready for review" 전환·리뷰어/라벨 지정은 사용자 요청 시 처리한다.

## 주의

- 외부로 나가는 동작(푸시·PR)은 되돌리기 어렵다 — 사용자 승인 후 진행한다(이미 "PR 열어줘" 류 지시가 있으면 그게 승인).
- 이미 PR 이 있으면 새로 만들지 말고 `gh pr view`/`gh pr edit` 로 갱신한다.
- 푸시 후 추가 작업을 더 했다면, 마지막에 PR 제목/본문을 전체 결과에 맞게 다시 손본다.
