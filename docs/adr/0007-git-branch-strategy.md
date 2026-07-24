# 0007. Git 브랜치 전략 — develop+main 채택, github-flow → git-flow 점진 확장

- **상태**: Accepted
- **날짜**: 2026-06-28
- **관련**: specs/001-menu-scan-mock, ADR-0001

## Context

001 슬라이스부터 협업·릴리스 흐름을 고정해야 한다. 제약:

- 현재 백엔드 개발자 1명(혼자), 정기 묶음 배포 예정, 추후 팀 확장 가능.
- 의존 작업이 실제로 발생(예: 001 작업물을 후속 기능이 곧장 필요).
- 운영(main)은 항상 배포 가능 상태를 유지하고 싶고, 작업물은 한곳에 모아 검토 후 릴리스하고 싶다.
- 처음부터 full git-flow(release/* · hotfix/*)를 운영하면 혼자에겐 관리 포인트가 과하다. 반대로 순수 github-flow(main 단일)는 "묶어서 릴리스"가 안 된다.
- 나중에 git-flow로 올라갈 때 **머지 규칙이 바뀌면 develop↔main 히스토리가 갈라지는** 비용이 크므로, 지금부터 확장에도 안 깨지는 규칙을 둬야 한다.

## Decision

**`develop` + `main` 2-브랜치 모델을 채택한다**(github-flow와 full git-flow 사이의 중간 단계). 그리고 **나중에 `release/*` · `hotfix/*`를 얹어 full git-flow로 무변경 확장**할 수 있도록 머지 규칙을 지금부터 고정한다.

- `main`: 운영. 항상 배포 가능. **직접 커밋 금지**, annotated 태그만 찍힌다.
- `develop`: 통합 트렁크. main에서 분기. **직접 커밋 금지**, 머지로만 갱신.
- `feature/<이슈#>-<slug>`: develop에서 분기, **Squash** 로 develop에 머지. **머지 후 브랜치는 삭제하지 않는다.**
- 릴리스 = `develop → main` **merge 커밋(--no-ff)** + main에서 `vX.Y.Z` 태그.

**불변 머지 원칙(지금도, 확장 후에도 동일):**
> **Squash 는 `feature → develop` 진입점에서만. develop·main(·미래 release·hotfix) 사이 통합 경로는 항상 merge 커밋(--no-ff, squash 금지).** develop/main 에 올라온 커밋은 rebase/amend 하지 않는다.

이 한 줄 덕분에 develop 의 (squash된) 커밋이 merge 커밋을 통해 main 으로 흘러 **develop↔main 이 정렬**되고, 후일 `release/*`·`hotfix/*` 를 끼워도 규칙이 그대로 적용된다.

**버전**: SemVer. 초기 개발 `0.0.1 → 0.1.0`, **첫 스토어 출시 시 `1.0.0`** 으로 펌핑. 스토어 빌드번호는 SemVer 와 별개로 단조증가.

**확장 트리거**: 정기 묶음 배포가 굳어지면 `release/*`(안정화·QA), 운영 핫픽스가 필요해지면 `hotfix/*` 를 추가한다(→ full git-flow).

실무 절차·커밋 메시지·레포 머지 설정은 [`docs/guides/git-branch-strategy.md`](../guides/git-branch-strategy.md) 가 단일 출처다.

## Alternatives Considered

- **순수 github-flow(main 단일, feature→main, 태그 릴리스).** 가장 단순하나 "develop 에 모아 약속된 시점에 묶어 릴리스"가 불가능 → 정기 배포 의도와 안 맞아 탈락. (단 더 단순함을 원하면 언제든 이쪽으로 내려갈 수 있다.)
- **처음부터 full git-flow(develop+main+release/\*+hotfix/\*).** 묶음 릴리스·핫픽스를 정식 지원하나, 혼자 단계에선 release/hotfix 브랜치·백머지 관리가 과한 오버헤드 → 확장 시점까지 보류.
- **통합 경로까지 Squash.** PR 단위 깔끔하지만 develop↔main 커밋이 달라져 백머지 충돌·중복 커밋을 유발 → 통합 경로는 merge 커밋으로 고정해 회피.
- **머지 후 feature 브랜치 자동 삭제.** 히스토리 보존을 위해 **삭제하지 않기로** 함(기여 그래프와는 무관 — 잔디는 기본 브랜치 커밋·PR·리뷰로 집계되며 브랜치 보존 여부와 독립).

## Consequences

- ✅ 작업물을 develop 에 모아 검토 후 main 으로 한 번에 릴리스 가능. main 은 항상 배포 가능 상태 유지.
- ✅ Squash(feature→develop)로 develop 히스토리가 PR 단위로 깔끔. 통합 경로 merge 커밋으로 develop↔main 정렬 유지.
- ✅ full git-flow 로 올라갈 때 **머지 규칙·브랜치 네이밍 변경 없이** release/*·hotfix/* 만 추가하면 됨.
- ⚠️ feature 브랜치를 삭제하지 않으므로 원격에 브랜치가 누적된다(검토 시 노이즈) — 의도된 트레이드오프.
- ⚠️ 레포 머지 설정에서 merge commit·squash 를 **둘 다 켜고**, PR base 에 따라 버튼을 **사람이 골라야** 한다(GitHub 가 base 별 머지 방식 자동 강제는 미지원). 규칙은 가이드와 후속 ArchUnit/CI 가 아니라 **리뷰 습관**으로 지킨다.
- 후속: `develop` 브랜치 생성, 진행 중 PR base 재타깃, `build.yml` 트리거에 `develop`(확장 시 `release/**`) 추가가 필요하다.
