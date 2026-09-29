---
name: speckit-team-implement
description: "kbap-server 의 speckit tasks.md 를 구현자 에이전트 팀(워크트리별 병렬)으로 구현하는 오케스트레이터. 사용자가 '팀으로 구현', '에이전트 팀으로 구현', '병렬로 구현', '워크트리로 나눠서 구현', 'implement with team', '팀 하네스로 tasks 진행' 처럼 팀·병렬·에이전트를 명시했을 때만 사용한다 — 기본 /speckit-implement 는 메인 세션 직접 구현이며 이 스킬로 대체하지 않는다. 후속 작업: '남은 묶음만 팀으로', '실패한 구현자만 다시', '머지만 다시', '팀 구현 이어서', '이전 팀 결과 개선', '워크트리 정리' 요청도 이 스킬."
---

# SpecKit 팀 구현 오케스트레이터

tasks.md 의 작업을 **파일이 겹치지 않는 묶음**으로 나누고, 묶음마다 구현자(`implementer`) 하나를 **전용 워크트리**에서 병렬로 띄운 뒤, 리더(이 세션)가 순서대로 머지해 최종 테스트를 돌린다.

이전 하네스(2026-08-14 폐기)는 task 하나를 test-writer→implementer→reviewer 로 쪼개 왕복시켜 느리고 과한 코드를 냈다. 이 하네스는 **역할을 쪼개지 않고 작업을 쪼갠다** — 구현자 한 명이 자기 묶음의 Red→Green→Refactor→커밋을 혼자 끝낸다. 리뷰는 하네스 밖이다(사용자가 `kbap-code-review`·`kbap-db-review` 를 따로 부른다).

## 실행 모드: 에이전트 팀 (이 빌드의 팀 = 이름 붙인 Agent + SendMessage)

이 빌드에는 `TeamCreate`·`TaskCreate` 가 없다. 팀은 `Agent(name: ..., isolation: "worktree")` 로 이름을 붙여 띄운 구현자들이고, 통신은 `SendMessage(to: <이름>)`, 작업 목록은 tasks.md 파일이다. 구현자는 `main` 으로 리더에게 메시지를 보낼 수 있다.

## 에이전트 구성

| 팀원 | subagent_type | 인원 | 역할 | 스킬 | 출력 |
|------|---------------|------|------|------|------|
| `impl-<묶음>` | `implementer` | 묶음 수만큼(1~3) | 묶음의 task 를 워크트리에서 TDD 로 구현·커밋 | `kbap-tdd-implementation` | 워크트리 브랜치 커밋 + 구현 보고 |
| 리더 | (이 세션) | 1 | 묶음 분할·계약 추출·머지·최종 테스트·tasks.md 체크 | 이 스킬 | feature 브랜치 커밋 |

모든 Agent 호출은 `model: "opus"`, `subagent_type: "implementer"`, `isolation: "worktree"`.

## 워크플로우

### Phase 0: 컨텍스트 확인 (초기 / 후속 판별)

1. `.specify/feature.json` → `specs/<feature>/` 를 찾는다. `tasks.md` 가 없으면 `/speckit-tasks` 부터 하라고 안내하고 멈춘다.
2. `_workspace/team-implement/<feature>/` 존재 여부로 모드를 정한다:
   - **미존재** → 초기 실행. Phase 1 로.
   - **존재 + "남은 묶음만"/"실패한 구현자만"** → 부분 재실행. `00_partition.md` 를 읽어 완료 표시 없는 묶음만 Phase 3 으로 보낸다.
   - **존재 + "머지만 다시"** → Phase 4 부터.
   - **존재 + 사용자가 tasks.md 를 새로 만들었다** → 기존 디렉터리를 `_workspace/team-implement/<feature>_<YYYYMMDD_HHMMSS>/` 로 옮기고 초기 실행.
3. `git status` 를 본다. 미커밋 변경이 **구현 대상 파일과 겹치면** 체리픽이 오염되므로 사용자에게 커밋·스태시를 요청하고 멈춘다. 겹치지 않으면(스펙 문서·하네스 파일 등) 진행하되, 워크트리에는 그 파일이 없으므로 프롬프트에 절대경로로 넘긴다.
4. `docker info` 로 Docker 를 확인한다. 내려가 있으면 `open -a Docker` 로 띄우고 `docker info` 가 성공할 때까지 기다린 뒤 스폰한다(Testcontainers 없이는 구현자가 테스트를 못 돌린다).

### Phase 1: 준비 — 묶음 분할과 계약 추출

1. `tasks.md`·`plan.md`·`data-model.md`(있으면) 를 읽는다. `/speckit-implement` 의 체크리스트 상태 확인(`checklists/` 미완 항목이 있으면 진행 여부 질문)을 그대로 수행한다.
2. **묶음 분할**. 규칙은 "같은 파일을 두 묶음이 고치지 않는다" 하나다.
   - 미완료(`[ ]`) task 의 파일 경로를 전부 뽑아 task→파일 표를 만든다.
   - 파일을 공유하는 task 는 같은 묶음. 스토리 경계보다 파일 경계가 우선이다.
   - 묶음 간 의존(A 의 시그니처를 B 가 호출)은 허용한다 — 계약을 미리 적어 주면 B 는 A 를 기다리지 않는다.
   - 묶음이 1개면 구현자 1명으로 진행한다(병렬 이득은 없지만 워크트리 격리와 보고 형식은 동일). 묶음이 4개 이상이면 작은 것끼리 합쳐 3개 이하로 줄인다 — 머지 비용이 병렬 이득을 넘는다.
   - 검증·PR 태스크(quickstart 실행·grep 검사·draft PR)는 묶음에 넣지 않고 리더가 Phase 5 에서 한다.
3. **인터페이스 계약 추출**. 묶음을 넘나드는 시그니처(새 public 메서드·이동하는 타입·바뀌는 생성자)를 `data-model.md`·`plan.md` 에서 뽑아 한 표로 만든다. 계약이 문서에 없으면 리더가 정한다 — 구현자에게 맡기면 둘이 다르게 만든다.
4. `_workspace/team-implement/<feature>/00_partition.md` 에 기록한다:

```markdown
# 묶음 분할 — <feature>
| 묶음 | 구현자 이름 | task | 소유 파일 | 의존 묶음 |
|------|------------|------|-----------|-----------|
| A | impl-a | T003,T004 | api/.../FoodService.kt, ... | - |
| B | impl-b | T007,T010 | api/.../IngredientService.kt, ... | A(계약 1) |

## 인터페이스 계약
| # | 제공 묶음 | 시그니처 | 소비 묶음 |
|---|----------|----------|----------|
| 1 | A | `fun getMostReviewedFoods(memberId: Long?, lang: LanguageCode, size: Int): List<FoodSummaryView>` | B |

## 리더 보류 task
T014, T015, T016 (검증·PR)
```

### Phase 2: 팀 구성 — 구현자 스폰

한 메시지에서 묶음 수만큼 `Agent` 를 동시에 호출한다:

```text
Agent(
  subagent_type: "implementer",
  name: "impl-a",
  model: "opus",
  isolation: "worktree",
  description: "묶음 A 구현",
  prompt: <아래 프롬프트 템플릿>
)
```

프롬프트 템플릿(묶음마다 채운다):

```text
kbap-tdd-implementation 스킬을 먼저 Skill 도구로 호출하고 절차대로 진행하라.

feature: specs/<feature>/  (tasks.md·plan.md·research.md·data-model.md 를 읽어라)
당신의 이름: impl-a  / 함께 뛰는 구현자: impl-b
할당 task (tasks.md 원문 그대로):
- [ ] T003 ...
- [ ] T004 ...
소유 파일 (이 밖은 편집 금지): ...
인터페이스 계약 (이 시그니처 그대로 만든다 / 이 시그니처가 있다고 가정하고 호출한다):
- 제공: ...
- 소비: ... (컴파일에 필요하면 계약대로 빈 껍데기를 두지 말고 리더에게 알려라)
무수정 파일: api/src/test/kotlin/com/kbap/api/home/*  (열지 않는다)
완료 시 워크트리 브랜치에 커밋하고 '구현 보고' 형식으로 돌려줘라. tasks.md 의 체크박스는 고치지 마라.
```

### Phase 3: 구현 — 구현자 자체 진행, 리더는 중재만

**실행 방식**: 구현자들은 각자 워크트리에서 진행한다. 리더는 완료 알림과 SendMessage 를 기다린다. 폴링하지 않는다.

리더가 개입하는 경우는 세 가지뿐이다:
- **계약 변경 통보**(구현자 → main): 소비 묶음의 구현자에게 이미 통보됐는지 확인하고, `00_partition.md` 의 계약 표를 갱신한다.
- **소유권 밖 수정 요청**: 그 파일의 소유 묶음이 아직 진행 중이면 그 구현자에게 SendMessage 로 위임한다. 이미 끝났으면 리더가 머지 후 직접 고친다.
- **차단 보고**: 에러 원문을 보고 지시한다. Docker 미기동처럼 환경 문제면 사용자에게 알린다.

구현자의 최종 보고(반환값)를 받으면 `_workspace/team-implement/<feature>/01_<이름>_report.md` 에 그대로 저장한다.

### Phase 4: 머지 — 리더가 의존 순서대로

**실행 방식**: 리더 단독(이 세션). 구현자는 이 단계에 관여하지 않는다.

1. 전 구현자 보고가 모이면 `00_partition.md` 의 의존 순서(제공 묶음 먼저)로 가져온다. **`Agent isolation: "worktree"` 는 현재 feature 브랜치가 아니라 `main` 위에 워크트리를 만든다**(2026-09-30 확인 — base 가 릴리스 머지 커밋). `git merge` 를 쓰면 main 의 머지 커밋들이 feature 브랜치로 딸려 오므로 **체리픽**한다:

```bash
git log --oneline <feature>..<워크트리 브랜치>        # 구현자 커밋만 골라낸다(main 의 머지 커밋 제외)
git cherry-pick <구현자 커밋 SHA들 — 오래된 것부터>
```

   base 가 feature HEAD 와 같으면(소스 동일) 체리픽은 충돌 없이 붙는다. 구현자 보고의 "브랜치 base" 줄로 먼저 확인한다.

2. 충돌이 나면 계약 표를 기준으로 푼다. 계약과 다른 쪽이 틀린 쪽이다. 푼 뒤 `./gradlew :api:compileKotlin :api:compileTestKotlin` 로 컴파일만 먼저 확인한다.
3. 모두 머지한 뒤 **전체 모듈 테스트**를 한 번 돌린다: `./gradlew :api:test`(batch 를 건드렸으면 `:batch:test` 도). 실패하면 실패 클래스가 어느 묶음 소유인지 보고 그 구현자에게 SendMessage 로 수정 지시 → 재커밋 → 재머지. 왕복은 **최대 2회**, 넘으면 리더가 직접 고치거나 사용자에게 에스컬레이션한다.
4. 그린이면 tasks.md 의 완료 task 를 `[X]` 로 바꾸고 커밋한다.
5. 워크트리·브랜치 정리: `git worktree remove <경로>` → `git branch -D <워크트리 브랜치>`. 체리픽이라 조상 관계가 없어 `-d` 는 거부된다 — **체리픽한 커밋이 전부 feature 브랜치에 있고 테스트가 그린인 것을 확인한 뒤에만** `-D` 한다. 가져오지 못한 커밋이 있는 브랜치는 지우지 않고 사용자에게 경로를 알린다.

### Phase 5: 리더 보류 task 와 마무리

1. Phase 1 에서 보류한 검증 task(quickstart 실행·grep 검사)를 리더가 실행한다.
2. draft PR task 가 있으면 `open-draft-pr-to-develop` 스킬로 연다.
3. `_workspace/team-implement/<feature>/02_summary.md` 에 묶음별 결과·머지 순서·테스트 결과·남은 task 를 적는다. `_workspace/` 는 보존한다(gitignore 대상).
4. 사용자에게 보고: 묶음 수·구현자별 커밋·최종 테스트 결과·리뷰는 `kbap-code-review` 로 따로 부르라는 안내. 그리고 한 줄로 피드백을 청한다("묶음 분할이나 계약 방식에 바꿀 점이 있나요?").

## 데이터 흐름

```text
tasks.md ─▶ [리더] 묶음 분할 + 계약 추출 ─▶ 00_partition.md
                    │
        ┌───────────┼───────────┐            (한 메시지에서 동시 스폰)
        ▼           ▼           ▼
   [impl-a]     [impl-b]     [impl-c]       각자 worktree, Red→Green→Refactor→commit
   worktree/a   worktree/b   worktree/c
        │  ◀── SendMessage(계약 변경) ──▶  │
        └───────────┼───────────┘
                    ▼  (구현 보고 = 반환값 → 01_*_report.md)
              [리더] 의존 순서 merge → :api:test → tasks.md [X] → worktree 정리
                    ▼
              02_summary.md + 사용자 보고 (리뷰는 별도 호출)
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| 구현자 1명 실패·중단(보고 없이 종료) | SendMessage 로 상태 확인 1회. 응답 없으면 같은 프롬프트로 새 이름(`impl-a2`)으로 1회 재스폰. 재실패 시 그 묶음은 리더가 직접 구현하고 요약에 명시 |
| 구현자 과반 실패 | 사용자에게 알리고 계속할지 확인(환경 문제일 가능성이 크다 — Docker·Gradle) |
| 머지 충돌 | 계약 표 기준으로 리더가 해결. 계약에 없는 충돌(둘 다 같은 파일을 고침)은 분할 오류 — `00_partition.md` 에 기록하고 다음 실행의 분할 규칙에 반영 |
| 머지 후 테스트 실패 | 소유 구현자에게 수정 지시(최대 2회 왕복) → 리더 직접 수정 → 에스컬레이션 |
| 워크트리 잔존 | 체리픽 완료·테스트 그린인 것만 제거. 못 가져온 커밋이 있는 브랜치는 남기고 사용자에게 경로를 알린다 |
| 워크트리 base 가 feature 와 다름 | 정상(main 기준). 소스가 같으면 체리픽. main 이 feature 보다 앞서 있으면 구현자 커밋만 체리픽하고 충돌은 계약 표 기준으로 해결 |
| 구현자가 feature 문서·스킬을 못 찾음 | 워크트리에는 미커밋 파일이 없다. 프롬프트에 메인 체크아웃의 **절대경로**를 준다(읽기 전용) |
| Docker 미기동 | 구현자 보고를 받는 즉시 사용자에게 알리고 팀을 멈춘다(테스트 없이 커밋 금지) |

## 이 하네스를 쓰지 않는 경우

- 묶음이 1개이고 task 가 5개 이하 → 메인 세션 직접 구현(`/speckit-implement`)이 더 빠르다. 사용자가 팀을 명시해도 그 사실을 한 줄로 알리고 진행 여부를 묻는다.
- tasks.md 가 없거나 파일 경로가 없는 task 가 많다 → 분할 불가. `/speckit-tasks` 재생성을 권한다.

## 테스트 시나리오

### 정상 흐름 (묶음 2개)
1. 사용자: "010 tasks.md 를 팀으로 구현해줘".
2. Phase 0: `_workspace/team-implement/010-home-service-facade/` 없음 → 초기 실행. `git status` 깨끗.
3. Phase 1: task→파일 표에서 `FoodService.kt`·`HomeService.kt` 를 T003/T004 와 T008/T011 이 공유 → US3+US2 를 한 묶음(A)으로 합침. T014~T016 은 리더 보류. 묶음 1개 → "직접 구현이 더 빠르다" 안내 후 사용자가 팀 유지를 택하면 impl-a 1명으로 진행.
4. Phase 2: `Agent(name: "impl-a", subagent_type: "implementer", isolation: "worktree", model: "opus")`.
5. Phase 3: impl-a 가 워크트리에서 T003~T013 처리, 커밋, 구현 보고 반환 → `01_impl-a_report.md`.
6. Phase 4: `git merge --no-ff` → `./gradlew :api:test` 그린 → tasks.md `[X]` → 워크트리 제거.
7. Phase 5: quickstart grep 검사, draft PR, `02_summary.md`, 사용자 보고.

### 에러 흐름 (계약 변경 + 머지 후 실패)
1. 묶음 A(FoodService)·B(IngredientService+HomeService) 로 분할. 계약 1: `getMostReviewedFoods(memberId, lang, size)`.
2. impl-a 가 구현 중 `size` 를 없애고 상수로 바꾸려 함 → 계약 변경을 impl-b 와 main 에 SendMessage. 리더는 계약 표를 기준으로 거부("size 는 홈 정책, research D3") → impl-a 가 계약대로 복귀.
3. 머지 후 `:api:test` 에서 `HomeControllerTest` 실패(최근 스캔 순서). 실패 클래스의 소유 묶음 B → impl-b 에 SendMessage 로 수정 지시. 1회 왕복으로 그린.
4. 요약에 "계약 변경 시도 1회 거부, 머지 후 수정 1회" 기록.
