---
name: cleanup-merged-worktree
description: PR 머지(또는 작업 종료) 후 feature 워크트리를 정리할 때 사용 — "develop 이동 pull 워크트리 제거", "워크트리 정리", "머지됐으니 치워줘", "cleanup worktree" 요청 시. 메인 체크아웃을 develop 최신으로 맞추고 .claude/worktrees/ 의 해당 워크트리를 제거한다.
---

# 머지된 feature 워크트리 정리

feature 브랜치를 워크트리(`.claude/worktrees/<branch>`)로 작업하고 PR 이 머지된 뒤, **메인 체크아웃 develop 을 최신으로 맞추고 워크트리를 제거**하는 절차. 모든 명령은 **메인 체크아웃**(`/Users/simjonghan/source_code/swm-kbap/kbap`)에서 실행한다.

## 사전 조건

- 대상 워크트리에 **미커밋 변경이 없어야** 한다(`git -C <워크트리> status --porcelain` 빈 출력). 있으면 제거가 거부된다 — **`--force` 로 밀지 말고** 먼저 커밋/푸시 여부를 사용자에게 확인한다.
- 브랜치가 푸시돼 있고 PR 이 머지됐거나, 사용자가 명시적으로 폐기를 지시한 상태여야 한다.

## 절차

1. **develop 이동 + pull** — 메인 체크아웃에서:
   ```bash
   cd /Users/simjonghan/source_code/swm-kbap/kbap
   git checkout develop 2>/dev/null; git pull
   ```
   pull 출력에 해당 PR 의 squash 커밋(`... (#NN)`)이 보이면 머지 확인까지 겸한다. 안 보이면 `git log --oneline -3` 로 확인.

2. **워크트리 제거**:
   ```bash
   git worktree remove .claude/worktrees/<branch>
   ```
   - 대상은 **이번 feature 의 워크트리 하나만**. 다른 워크트리(특히 `locked` 표시)는 건드리지 않는다 — 병행 세션 소유일 수 있다.
   - dirty 라 거부되면 중단하고 사용자에게 보고(사전 조건 참조).

3. **로컬 브랜치 정리(선택)** — squash 머지라 `git branch -d` 는 "not fully merged" 로 실패한다. PR 머지를 확인했다면:
   ```bash
   git branch -D <branch>
   ```
   사용자가 브랜치 정리를 명시하지 않았으면 제안만 하고 실행은 확인 후에 한다.

4. **보고** — pull 결과(머지 커밋 해시), 워크트리 제거 완료, 현재 브랜치(develop), 남은 로컬 브랜치 여부를 전달한다.

## 흔한 함정 (실제로 밟은 것)

- **cwd 착각**: 셸 cwd 가 명령 사이에 메인 체크아웃으로 리셋될 수 있다. 워크트리 내부 파일을 만질 일이 있으면 절대경로로, 정리 명령은 메인 체크아웃 기준으로 실행한다.
- **`-d` 로 브랜치 삭제 시도**: squash 머지 워크플로라 항상 실패한다. 머지 확인 후 `-D` 가 정답.
- **pull 을 워크트리에서 실행**: develop 은 메인 체크아웃이 들고 있다 — 워크트리에서는 checkout 자체가 안 된다(한 브랜치 = 한 워크트리).
- **머지 전 제거**: PR 이 아직 open 이면 워크트리 제거는 가능하지만(커밋은 브랜치에 안전) 후속 수정이 불편해진다 — 머지 전이면 사용자에게 확인.
