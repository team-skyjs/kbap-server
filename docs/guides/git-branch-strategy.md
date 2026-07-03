# Git 브랜치 전략 — 실무 지침

> 결정 근거는 [ADR-0007](../adr/0007-git-branch-strategy.md). 이 문서는 **무엇을 어떻게** 하는지의 단일 출처다.
> 현재 모델: **`develop` + `main`** (github-flow 와 full git-flow 사이). 추후 `release/*`·`hotfix/*` 추가로 full git-flow 로 확장.

## 1. 브랜치 모델

| 브랜치 | 분기 원천 | 머지 대상 | 머지 방식 | 비고 |
|---|---|---|---|---|
| `main` | — | — | — | 운영. 항상 배포 가능. **직접 커밋 ❌**, 태그만 |
| `develop` | `main` | — | — | 통합 트렁크. **직접 커밋 ❌**, 머지로만 |
| 기능 작업 브랜치 | `develop` | `develop` | **Squash** | 기능 작업. 이름은 §1.1 참조. **머지 후 삭제 안 함** |
| `release/x.y.z` *(확장 시)* | `develop` | `main` + `develop` | Merge --no-ff | 안정화·QA 전용, 신규 기능 ❌ |
| `hotfix/<slug>` *(확장 시)* | `main` | `main` + `develop` | Merge --no-ff | 운영 긴급 패치 |

### 1.1 spec 폴더 · 브랜치 이름

기능 작업의 이름은 **Jira 태스크 키를 기준**으로 짓는다. 순번(`NNN`)은 쓰지 않는다 — 여러 개발자가 동시에 작업할 때 공유 카운터가 충돌하기 때문이다. Jira 키는 전역 유일하므로 카운터 없이 절대 겹치지 않는다.

**① spec 폴더 = 브랜치 = `kb-NN-slug`** (SpecKit 사이클이 생성)

- `kb-NN` — Jira 태스크 키를 **소문자로** 그대로 쓴 것(`KB-28` → `kb-28`). 폴더 유일성을 보장한다.
- `slug` — 작업 설명(영문 2~4단어, `--short-name`으로 전달). 예: `kb-28-food-spiciness`.
- 별도 prefix(`feature/`·순번 등)를 붙이지 않는다.
- 생성: `/speckit-specify` 를 `JIRA_KEY=KB-28` 와 함께 실행한다 — `before_specify` 훅(`speckit-git-branch` 스킬)이 순번 없이 `kb-28-<slug>` 브랜치·`specs/kb-28-<slug>/` 를 만든다. 공유 스크립트 `create-new-feature.sh` 는 손대지 않는다(Jira 경로는 스킬이 git 으로 직접 생성). 수동 생성 시엔 `git checkout -b kb-28-<slug>` 후 `specs/kb-28-<slug>/` 를 스캐폴드한다.

**② Jira 태스크 ↔ spec 폴더 = 기본 1:1**

- **한 태스크 = 한 spec 폴더**가 기본이다. 작업이 늘면 폴더를 쪼개지 말고 tasks.md 안에서 PR 크기로 슬라이스한다(③).
- 드물게 별도 spec·plan·tasks 가 필요할 만큼 설계가 갈리면 새 폴더를 파도 된다 — slug 가 달라 자연히 구분된다(`kb-28-food-spiciness`, `kb-28-spiciness-batch`).

**③ PR 브랜치 = 폴더당 1~N개** (멀티 PR 대비)

- 단일 PR 태스크 → 브랜치명 = **spec 폴더명 그대로**(`kb-28-food-spiciness`).
- 여러 PR로 쪼갤 때 → 각 PR 브랜치에 **`-pK-서브슬러그`** 를 붙인다(`p1`, `p2`, …): `kb-28-food-spiciness-p1-domain-column`, `kb-28-food-spiciness-p2-response-dto`.
  - 전부 `develop`에서 분기하고 각자 **Squash** PR로 병합한다(§2).
  - 구분자는 반드시 `-pK-` — `kb-28-food-spiciness` 브랜치와 `kb-28-food-spiciness/p1` 은 git ref 가 충돌하므로 `/` 를 쓰지 않는다.
  - PR 본문에 Jira 키를 참조한다: 중간 PR `Refs KB-28`, 태스크를 끝내는 마지막 PR `Closes #<GitHub 이슈>`(연계 시).

- *(확장)* `release/*`·`hotfix/*` 네이밍 예: `release/0.0.1`, `hotfix/food-detail-npe`.

## 2. 불변 머지 원칙 ★

> **Squash 는 `feature → develop` 진입점에서만. develop·main(·미래 release·hotfix) 사이 통합 경로는 항상 Merge 커밋(--no-ff, squash 금지).**
> develop/main 에 올라온 커밋은 **rebase/amend 금지.**

| 구간 | 방식 | 결과 커밋 메시지 |
|---|---|---|
| `feature → develop` | **Squash** | PR 제목 = conventional 1줄 (+ 본문) |
| `develop → main` (릴리스) | **Merge --no-ff** | `Merge develop into main (release x.y.z)` |
| *(확장)* `release/* → main` | Merge --no-ff | `Merge release/x.y.z into main` |
| *(확장)* `release/* → develop` 백머지 | Merge --no-ff | `Merge release/x.y.z back into develop` |
| *(확장)* `hotfix/* → main`·`→ develop` | Merge --no-ff | `Merge hotfix/<slug> into …` |

이 규칙을 지키면 확장(release/hotfix 추가) 시에도 규칙이 안 바뀌고 develop↔main 이 정렬된다.

## 3. 레포 머지 설정 (Settings → Pull Requests)

base 별 머지 방식을 GitHub 가 자동 강제하지 못하므로 **두 방식을 모두 켜고 PR 마다 버튼을 골라 쓴다.**

| 설정 | 값 |
|---|---|
| Allow merge commits | ✅ ON · default message = **Pull request title** |
| Allow squash merging | ✅ ON · default message = **Pull request title and description** |
| Allow rebase merging | ⬜ OFF |
| Automatically delete head branches | ⬜ OFF (브랜치 보존) |

**PR 머지 버튼 선택 규칙:**
- base = **`develop`** → **Squash and merge**
- base = **`main`**(릴리스) → **Create a merge commit**

## 4. 커밋 메시지 컨벤션

Conventional Commits + 한국어 본문(기존 repo 스타일 유지). 코드 주석 금지 규약상 "왜"는 커밋 본문/문서에 남긴다.

```
<type>(<scope>): <요약 — 명령형, 50자 내, 마침표 없음>

<본문(선택): 왜·트레이드오프. 72자 줄바꿈>

<푸터(선택): Closes #6   Refs #4>
```

| type | 용도 | 라벨 |
|---|---|---|
| `feat` | 기능 추가 | `feat` |
| `fix` | 버그 수정 | `fix` (긴급은 `hotfix` 라벨) |
| `refactor` | 동작 불변 구조 개선 | `refactor` |
| `chore` | 빌드·설정·릴리스 잡무 | `chore` |
| `docs` | 문서 | `docs` |
| `build` `ci` `test` `perf` `style` | 각 영역 | — |

`scope` = 도메인·모듈: `scan` `food` `member` `avoidance` `research` `persistence` `infra` `presentation` `core` `common` `batch`.

### 브랜치별 커밋 입도
- **기능 작업 브랜치**: 로컬은 자유·자주·WIP 허용(어차피 squash 됨). **PR 제목만** conventional 하게.
- **develop / main**: 직접 커밋 ❌, 머지로만.
- *(확장)* **release/***: 안정화만 — `fix(scan): QA 빈입력 400 처리`, `chore(release): 0.0.1 버전 범프`, `docs(changelog): …`.
- *(확장)* **hotfix/***: 단일 긴급 수정 — `fix(food): 상세조회 NPE 긴급 수정`.

## 5. PR 규약

- 제목 = (squash 시) 커밋 메시지 → **conventional 1줄**.
- 본문에 `Closes #N`(여러 기능이면 각각), 상위 릴리스 이슈는 `Refs #4`.
- **Jira 추적 태스크면 양방향 링크**: PR 본문에 이슈 URL + `Refs KB-NN`, PR 생성 후 Jira 이슈에 PR 링크 역방향 코멘트. 대응 GitHub 이슈가 없으면 `close #` 는 비운다. GitHub for Jira 앱 설치 시 커밋/브랜치의 `KB-NN` 로 Development 패널 자동 연동.
- base 브랜치 = **`develop`** (릴리스 PR 만 `main`).
- CI(build) + Codex 리뷰가 PR 마다 자동 실행.

## 6. 버전 / 태그 (main 에서만, annotated)

```bash
git tag -a v0.0.1 -m "release 0.0.1: 메뉴 스캔·상세조회 mock 슬라이스"
git push origin v0.0.1
```

- SemVer: 초기 개발 `0.0.1 → 0.1.0`, **첫 스토어 출시 `1.0.0`**, 이후 MAJOR/MINOR/PATCH 엄격 적용.
- 스토어 빌드번호(android `versionCode` / iOS build)는 SemVer 와 별개로 단조증가.

## 7. 한 사이클 요약

```
001-menu-scan (기능) ─(Squash)─► develop ─●─●─●─(약속된 시점)─┐
                                                             │ Merge --no-ff
                                                    main ◄───┘ + tag v0.0.1
   (확장) hotfix/* ─(Merge)─► main & develop
```

## 8. 기여 그래프(잔디) 참고

- 잔디는 **기본 브랜치(`main`) 커밋 + PR 열기 + 리뷰**로 집계된다. **feature 브랜치 보존 여부와 무관.**
- feature→develop 의 squash 커밋은 **develop→main 으로 main 에 도달하는 시점**에 집계된다(PR 연 것은 그날 즉시 집계).
