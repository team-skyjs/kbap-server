---
name: tdd-harness-orchestrator
description: "kbap-server 의 SpecKit·TDD 개발 에이전트 팀을 조율하는 오케스트레이터. test-writer→implementer→(code-reviewer∥database-expert) 사이클로 task 를 Red→Green→Refactor→리뷰까지 몰고 간다. 'TDD 로 구현해', 'task 구현해줘', 'tasks.md 진행', '이 기능 TDD 로', 'US2 구현', '테스트부터 짜고 구현' 요청 시 사용. 후속 작업: 특정 task/스토리만 다시, 리뷰 지적 반영, 재실행, 이어서 진행, 검토만 다시도 이 스킬."
---

# TDD 하네스 오케스트레이터

kbap-server 의 SpecKit·TDD 흐름을 4역할 에이전트 팀으로 구동한다. 헌법 원칙 I(Test-First, NON-NEGOTIABLE)을 **리더가 게이트로 강제**한다: 테스트가 Red 임을 본 뒤에만 구현, Green 인 뒤에만 리뷰.

이 스킬은 `/speckit-implement` 를 **대체가 아니라 보완**한다 — tasks.md 의 task 를 한 단위씩 TDD 사이클로 처리하며, 각 단위마다 점진적으로 리뷰한다.

## 실행 모드: 에이전트 팀

순차 의존(Red→Green)이 강하지만, **리뷰 단계의 병렬 팬아웃(code-reviewer ∥ database-expert)과 reviewer→implementer 수정 루프**가 팀 모드의 가치다. 리더가 단계 게이트를 집행하고, 팀원은 SendMessage 로 직접 피드백을 주고받는다.

## 에이전트 구성

| 팀원 | 타입 | 역할 | 스킬 |
|------|------|------|------|
| test-writer | test-writer | 실패 테스트 작성·Red 확인 | tdd-test-authoring |
| implementer | implementer | 최소 구현(Green)·리팩터·수정 | tdd-implementation |
| code-reviewer | code-reviewer | 헌법·컨벤션·테스트 품질 리뷰 | kbap-code-review |
| database-expert | database-expert | JPA·Mongo·Flyway 설계/성능 리뷰 | kbap-db-review |

모든 Agent/TeamCreate 멤버는 `model: "opus"`.

## 워크플로우

### Phase 0: 컨텍스트 확인

1. 대상 파악: 사용자가 지정한 feature(`specs/<NNN-slug>/`)·user story·task 범위. 미지정이면 현재 브랜치명으로 feature 를 추정하고 `tasks.md` 의 미완료(`[ ]`) task 를 후보로 제시.
2. 실행 모드 판별:
   - **신규 진행**: 지정 범위의 미완료 task 부터 시작.
   - **부분 재실행**("US2 의 컨트롤러만 다시", "그 리뷰 지적 반영"): 해당 task/단계만 재투입. 이미 통과한 단계는 건너뛴다.
   - **검토만 다시**: implementer 변경 없이 code-reviewer·database-expert 만 재실행.
3. 필독: `specs/<feature>/{spec,plan,data-model}.md`, `contracts/*.md`, `.specify/memory/constitution.md`. 핵심 제약을 팀 프롬프트에 요약해 전달.

### Phase 1: 준비

1. task 목록을 **작업 단위**로 확정(보통 user story 안의 논리 단위 = 도메인→영속→application→web 묶음, 또는 tasks.md 의 개별 task).
2. 리뷰 산출물 보관용 `_workspace/` 를 repo 루트에 준비(리뷰 리포트·사이클 로그 저장, 커밋 대상 아님).

### Phase 2: 팀 구성

```
TeamCreate(team_name: "kbap-tdd-team", members: [
  { name: "test-writer",     agent_type: "test-writer",     model: "opus",
    prompt: "tdd-test-authoring 스킬을 사용. 할당 task 의 실패 테스트를 먼저 작성하고 Red 를 실제 확인해 보고하라. 구현은 절대 작성하지 말 것. 제약: <헌법·컨벤션 요약>" },
  { name: "implementer",     agent_type: "implementer",     model: "opus",
    prompt: "tdd-implementation 스킬을 사용. test-writer 의 Red 확인 후에만 최소 구현(Green)→리팩터. 모듈 경계·영속 규약·BaseResponse·/api/v·Kotlin 주석 금지 준수." },
  { name: "code-reviewer",   agent_type: "code-reviewer",   model: "opus",
    prompt: "kbap-code-review 스킬을 사용. Green 직후 헌법·컨벤션·테스트 품질을 검토하고 심각도별로 보고. 코드 수정 금지. DB 스키마는 database-expert 에 위임." },
  { name: "database-expert", agent_type: "database-expert", model: "opus",
    prompt: "kbap-db-review 스킬을 사용. 영속 변경(엔티티·Flyway·Mongo)이 있으면 3소스 교차로 성능·요구 적합성 검토. 없으면 'DB 영향 없음' 보고." },
])
```

작업 등록(task 단위로 반복):

```
TaskCreate(tasks: [
  { title: "[test] <task> 실패 테스트",   assignee: "test-writer" },
  { title: "[impl] <task> 구현",          assignee: "implementer",     depends_on: ["[test] <task> 실패 테스트"] },
  { title: "[review] <task> 코드 리뷰",   assignee: "code-reviewer",   depends_on: ["[impl] <task> 구현"] },
  { title: "[db-review] <task> DB 검토",  assignee: "database-expert", depends_on: ["[impl] <task> 구현"] },
])
```

### Phase 3: TDD 사이클 (task 단위 반복) — 게이트 집행

각 작업 단위마다 순서대로:

1. **Red** — test-writer 가 테스트 작성·실행. 리더는 **Red 증거(의미 있는 실패)**를 확인한다. 증거 없으면 다음으로 넘기지 않는다.
2. **Green** — Red 확인 후 implementer 에 진행 지시. 리더는 **Green 증거(통과 출력)**를 확인한다.
3. **Refactor** — implementer 가 정리 후 테스트 재통과 확인.
4. **리뷰 팬아웃(병렬)** — Green 직후 code-reviewer 와 database-expert 가 **동시에** 검토. 둘은 변경 파일을 읽고 각자 보고서를 `_workspace/<task>_review-code.md`·`_workspace/<task>_review-db.md` 에 쓰고 SendMessage 로 implementer·리더에 통보.
5. **수정 루프** — Blocker/Major 가 있으면 implementer 가 수정(`[fix]`), 해당 리뷰어가 **재검증**. 최대 2~3회 왕복 후에도 남으면 리더가 사용자에게 에스컬레이션.
6. **게이트 통과** — 양 리뷰어 Blocker 0 + 테스트 그린이면 이 task 완료. tasks.md 의 `[ ]` 를 `[X]` 로 갱신(해당되면).

> 팀원 간 통신: test-writer→implementer(명세 인계), implementer↔reviewers(수정 협의), reviewer↔reviewer(영역 겹침 시 공유). 리더는 단계 게이트와 충돌 중재만.

### Phase 4: 마무리

1. 범위 내 모든 task 가 게이트 통과했는지 TaskGet 으로 확인.
2. `./gradlew build`(또는 영향 모듈 test)로 **전체 그린** 최종 확인.
3. 사이클 요약을 사용자에게 보고: task별 작성 테스트 수·변경 파일·리뷰 발견(심각도별)·잔여 이슈.
4. 커밋은 사용자 승인 후(작업/논리 단위마다, 헌법 워크플로우). 커밋·머지 규칙은 프로젝트 git 전략을 따른다.

### Phase 5: 정리

1. 팀원 종료 후 `TeamDelete`.
2. `_workspace/` 리뷰 리포트 보존(감사 추적).

## 데이터 흐름

```
[리더] TeamCreate
   │  spec/plan/data-model/contracts 요약 전달
   ▼
test-writer ─(Red 확인·SendMessage)→ implementer ─(Green·변경목록)→ ┬→ code-reviewer ─┐
                                          ▲                          └→ database-expert ┘
                                          │  수정 요청(SendMessage)        │ 리뷰 리포트
                                          └──────────── [fix] 루프 ────────┘
   │ 게이트 통과
   ▼
[리더] 전체 빌드 그린 확인 → 요약 보고 → (승인 시) 커밋
```

## 에러 핸들링

| 상황 | 전략 |
|------|------|
| Red 가 컴파일 실패로만 발생 | test-writer 에 최소 stub + 단언 실패로 재설계 요청(약한 Red 거부) |
| 테스트가 통과해 버림(이미 구현) | 누락 요구사항 추가 테스트 또는 task 범위 재확인 |
| Green 이 안 됨 | implementer 가 구현→의존→DI 순 점검. 오래 막히면 부분 진행 보고·리더 개입 |
| implementer 가 테스트를 약화 시도 | 리더가 차단 — 테스트 변경은 test-writer 만, 근거 있는 이의로만 |
| 리뷰 Blocker 무한 왕복 | 2~3회 후 사용자 에스컬레이션, 결정 받기 |
| 리뷰어 간 지적 충돌 | 출처 병기해 둘 다 보고, 리더가 중재(삭제하지 않음) |
| 팀원 1명 중지 | 리더가 SendMessage 로 상태 확인→재시작, 실패 시 해당 단계 누락 명시 |

## 테스트 시나리오

### 정상 흐름
1. 사용자: "US2 음식 상세를 TDD 로 구현해줘".
2. Phase 0: `specs/001-menu-scan-mock/` 읽고 US2 미완료 task 확정.
3. Phase 2: 4인 팀 생성, task별 [test]→[impl]→[review]∥[db-review] 등록.
4. Phase 3: test-writer Red 확인 → implementer Green/Refactor → 두 리뷰어 병렬 검토 → 수정 루프 → 게이트 통과. task 반복.
5. Phase 4: 전체 빌드 그린, 요약 보고.
6. 예상 결과: 테스트+구현 코드, 리뷰 리포트(`_workspace/`), tasks.md 갱신.

### 에러 흐름
1. Phase 3 에서 database-expert 가 "korean_name 인덱스 없음(Major)·엔티티↔Flyway 길이 불일치(Blocker)" 보고.
2. implementer 가 Flyway·엔티티 수정([fix]).
3. database-expert 재검증 → Blocker 0.
4. 동시에 code-reviewer 가 "lang 폴백 테스트 누락(Major)" → test-writer 가 테스트 추가 → implementer 보강 → 재검증.
5. 양 리뷰어 통과 후 게이트 통과, 요약에 발견·수정 내역 명시.
