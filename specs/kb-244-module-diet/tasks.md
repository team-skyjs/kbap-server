# Tasks: 스프링 모듈 구조 다이어트 — api·batch·common 3모듈로 통합

**Input**: Design documents from `/specs/kb-244-module-diet/`

**Prerequisites**: plan.md, spec.md, research.md(모듈 매트릭스 = Decision 2), quickstart.md(게이트 명령)

**Tests**: 이동 리팩터링이라 신규 기능 테스트는 없다 — **기존 전체 슈트 그린이 각 태스크의 통과 조건**이고,
유일한 신규 검증(ArchUnit 도메인 간 방향 규칙)은 Test-First 로 작성한다(위반 샘플로 Red 확인 후 샘플 제거).

**Organization**: 사용자 지시로 **PR 2개**로 나눈다 — **PR #1 = US2(common 분리)**, **PR #2 = US1(api·batch 완성) + US3(경계·문서·헌법)**.
US1(동작 동일성)의 게이트(전체 빌드·기동)는 두 PR 모두의 체크포인트에 포함된다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

**Purpose**: 이동 시작 전 기준선 고정

- [X] T001 기준선 확인 — `./gradlew clean build` 그린 확인·소요시간 기록, `./gradlew projects` 로 16개 모듈 목록을 tasks.md 하단 Notes 에 기록

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모듈 이동으로 Gradle 경계가 사라지기 **전에** ArchUnit 이 그 몫을 넘겨받고, `:common` 그릇을 만든다

**⚠️ CRITICAL**: T002 없이 이동을 시작하면 도메인 간 순환이 무검증 상태가 된다

- [X] T002 도메인 간 의존 방향 ArchUnit 규칙 추가(Test-First) — `app/api/src/test/kotlin/com/kbap/app/api/architecture/ModuleBoundaryTest.kt` 에 `com.kbap.domain.<ctx>` 패키지 간 허용 방향(scan→food·member·image / food→member·avoidance / member→avoidance, 역방향 금지)을 명시하는 규칙 추가. **Red 확인**: 임시 위반 코드(예: member 에서 food import)로 실패 확인 후 샘플 제거·Green
- [X] T003 buildSrc 아키타입 신설 — `buildSrc/src/main/kotlin/kbap.common-conventions.gradle.kts` 생성: 기존 `kbap.domain-conventions` 내용(kotlin-jpa·Boot BOM·`api`(data-jpa)·mysql runtimeOnly·테스트 공통)을 승계하되 `api(project(":core"))`·`testFixtures(project(":core"))` 참조 제거(자기 자신이 될 모듈용) + `java-test-fixtures` 플러그인과 core 의 testFixtures 의존(spring-boot-testcontainers·testcontainers-mysql 등) 포함
- [X] T004 `:common` 모듈 신설 — `settings.gradle.kts` 에 `":common"` 추가, `common/build.gradle.kts` 생성(`kbap.common-conventions` 적용), 빈 소스셋으로 `./gradlew :common:build` 그린 확인

**Checkpoint**: ArchUnit 이 도메인 방향을 감시하고 `:common` 이 비어 있는 채 빌드에 합류

---

## Phase 3: User Story 2 — 공유 코드의 common 단일화 (Priority: P2) 🎯 **PR #1**

**Goal**: core·food·member·avoidance·seam 인터페이스를 `:common` 으로 모으고 batch·infra·잔여 도메인의 의존을 재배선

**Independent Test**: quickstart.md "의존 방향 확인" — batch·infra 의 project 의존이 `:common` 뿐, `:core`/`:domain:food` 등 잔존 참조 0건, 전체 빌드 그린

- [X] T005 [US2] `:core` 소스 이동 — `git mv core/src/main/kotlin/com/kbap/core common/src/main/kotlin/com/kbap/core` (test·testFixtures 동일), `settings.gradle.kts` 에서 `":core"` 제거, `core/` 디렉터리 삭제
- [X] T006 [US2] `:core` 참조 전역 재배선 — 전 `build.gradle.kts` + `buildSrc/src/main/kotlin/kbap.domain-conventions.gradle.kts` 의 `project(":core")`·`testFixtures(project(":core"))` → `":common"` 치환(infra 4종·domain 4종·app 2종·application), `./gradlew build` 그린
- [X] T007 [US2] `:domain:avoidance` 소스 이동 — `git mv domain/avoidance/src/main/kotlin/com/kbap/domain/avoidance common/src/main/kotlin/com/kbap/domain/avoidance`(test 동일), settings 에서 제거, member·food·app:api(testImplementation 포함) 의 `:domain:avoidance` 참조 → 삭제(공용 `:common` 전이로 충족) 또는 `":common"` 치환, 그린 확인
- [ ] T008 [US2] `:domain:member` 소스 이동 — 동일 절차(`git mv` → settings 제거 → food·scan·infra:auth·app:api 의 참조 재배선), 그린 확인
- [ ] T009 [US2] `:domain:food` 소스 이동 — 동일 절차(`git mv` → settings 제거 → scan·app:api·app:batch 의 참조 재배선), 그린 확인
- [ ] T010 [US2] `:application` seam 분리 — `application/src/main/kotlin/com/kbap/application/` 에서 인터페이스·dto 만 `common/src/main/kotlin/com/kbap/application/` 로 `git mv`: `TokenIssuer`·`TokenParser`·`SocialTokenVerifier`·`RefreshTokenStore`·`PresignedUploadPort` + 이들 시그니처가 쓰는 dto(파일 단위로 식별). ApplicationService 구현은 남긴다
- [ ] T011 [US2] infra 4종 재배선 — `infra/{auth,redis,storage}/build.gradle.kts` 의 `project(":application")` → `":common"`, `infra/auth` 의 `project(":domain:member")` → `":common"`(T008 에서 미처리 시). `application/build.gradle.kts` 은 `:common` 의존 추가. `./gradlew build` 그린
- [ ] T012 [US2] PR #1 게이트 + draft PR — quickstart.md 전 명령 실행(빌드·projects·의존 그래프·양 앱 기동·ModuleBoundaryTest), `grep -rn 'project(":core")\|project(":domain:\(avoidance\|member\|food\)")' --include=build.gradle.kts .` 잔존 0건 확인 후 open-draft-pr-to-develop 절차로 **PR #1(common 분리)** 오픈

**Checkpoint**: 모듈 구성 core·food·member·avoidance 소멸 → `:common` 합류(16→13). batch·infra 는 `:common` 만 본다 — US2 수용 시나리오 충족

---

## Phase 4: User Story 1 — 3모듈 구조에서 동일 기능 유지 (Priority: P1) **PR #2 전반**

**Goal**: 잔여 도메인 4종과 application 서비스부를 `:app:api` 로 흡수해 애플리케이션 모듈을 api·batch·common 3개로 완성

**Independent Test**: `./gradlew projects` = 7모듈, `./gradlew clean build` 그린, 양 앱 기동 — US1 수용 시나리오 그대로

> PR #2 브랜치는 PR #1 머지 후 develop 에서 갈라낸다(또는 PR #1 브랜치 위에 stacked)

- [ ] T013 [US1] `:app:api` 에 kotlin-jpa 준비 — `app/api/build.gradle.kts` 에 kotlin-jpa(no-arg) 플러그인 추가(도메인 엔티티가 들어올 자리 — `kbap.spring-boot-application` 은 미포함), 그린 확인
- [ ] T014 [P] [US1] `:domain:scan` 소스 이동 — `git mv domain/scan/src/main/kotlin/com/kbap/domain/scan app/api/src/main/kotlin/com/kbap/domain/scan`(test 동일), settings 제거, app:api 의 `:domain:scan` 참조 삭제
- [ ] T015 [P] [US1] `:domain:bookmark`·`:domain:image`·`:domain:metering` 소스 이동 — T014 와 동일 절차 3종(각각 `git mv` → settings 제거 → app:api 참조 삭제), 그린 확인
- [ ] T016 [US1] `:application` 잔여부 흡수 — `git mv application/src/main/kotlin/com/kbap/application app/api/src/main/kotlin/com/kbap/application`(test 동일, T010 이후 잔여 = Home·Auth ApplicationService 등), settings 에서 `":application"` 제거, `application/` 삭제, app:api 의 `:application` 참조 삭제
- [ ] T017 [US1] 모듈 잔재 정리 — settings 에서 `":domain:review"` 제거·`domain/review/`·`domain/research/` 디렉터리 삭제, `domain/` 컨테이너 비면 삭제, `buildSrc/src/main/kotlin/kbap.domain-conventions.gradle.kts` 삭제, `./gradlew projects` = `:common`·`:app:api`·`:app:batch`·`:infra:{llm,auth,redis,storage}` 7개 확인
- [ ] T018 [US1] PR #2 전반 게이트 — quickstart.md 전 명령(빌드·projects·의존 그래프·양 앱 기동) + `git log --follow` 로 이동 파일 이력 보존 스팟체크

**Checkpoint**: 모듈 7개, 전체 그린, 양 앱 기동 — US1 완성. 이 시점부터 US3(문서·헌법)만 남음

---

## Phase 5: User Story 3 — 경계 규칙·문서의 새 구조 반영 (Priority: P3) **PR #2 후반**

**Goal**: ArchUnit 규칙 정합화 + 헌법 v6.0.0 + 문서 갱신

**Independent Test**: ModuleBoundaryTest 그린 + 문서·헌법이 실측 구조(7모듈)와 일치

- [ ] T019 [US3] ModuleBoundaryTest 전면 재검토 — `app/api/src/test/kotlin/com/kbap/app/api/architecture/ModuleBoundaryTest.kt` 의 기존 규칙(core Spring-free·`@Entity` 위치·도메인→상위 금지·application→infra 금지)을 새 구조에서 재확인: 패키지 기준이라 대부분 유효하나, "core Spring-free" 는 common 합류로 의미 재정의(도메인 서비스 `@Service` 는 허용, web 의존 금지로 완화), 죽은 규칙은 삭제·필요 규칙은 보강. 변경마다 Red(위반 샘플)→Green 확인
- [ ] T020 [P] [US3] 헌법 v6.0.0 개정 — `.specify/memory/constitution.md`: 원칙 II("컨텍스트별 모듈"→"컨텍스트별 패키지 + ArchUnit"), 원칙 III(모듈 그래프 → app→common·infra→common + 패키지 방향), 원칙 IV("소유 도메인 모듈"→"소유 도메인 패키지"), 기존 `:common` 서술 대체. Sync Impact Report 작성(MAJOR 5.0.0→6.0.0, 선례 KB-134·KB-220 형식)
- [ ] T021 [P] [US3] ADR 작성 — `docs/adr/0015-module-diet-three-modules.md`(다음 빈 번호 확인): 배경(도메인별 모듈의 관리 비용)·결정(3 앱/공유 + infra 유지·common 배치 기준)·결과(경계 강제는 ArchUnit 단독), ADR-0012·0014 와의 관계 명시
- [ ] T022 [P] [US3] 문서 갱신 — `CLAUDE.md` 모듈 구조 절(개요·모듈 구조·컨벤션의 모듈 서술 전부)과 `docs/architecture/kbap-conventions.md`·`docs/architecture/kbap-api-module-structure.md` 를 7모듈 실측 구조로 갱신
- [ ] T023 [US3] PR #2 오픈 — quickstart.md 최종 전 게이트 + `-Dkotest.tags` 아크 테스트 포함 전체 실행 후 open-draft-pr-to-develop 절차로 **PR #2(api·batch 완성 + 문서)** 오픈

**Checkpoint**: 전 스토리 완성 — 스펙 SC-001~004 충족

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T024 [P] Jira KB-244 DoD 체크 동기화 — 6개 DoD 항목을 실측 결과로 체크·코멘트(빌드 시간 before/after 포함)
- [ ] T025 시드-동기화 테스트 등 리소스 경로 하드코딩 스팟체크 — `AvoidanceCatalogSeedSyncTest` 의 `db/migration` 경로(리소스는 app/api 소속 그대로라 무영향 예상)와 유사 하드코딩 테스트가 이동 후에도 리소스를 실제로 읽는지 확인(빈 문자열 오진 함정)

---

## Dependencies & Execution Order

- **Phase 1 → 2 → 3(PR #1) → 4 → 5(PR #2) → 6** 순차가 기본 — 파일 이동이 settings.gradle.kts·빌드 파일을 공유하므로 이동 태스크(T005~T011, T014~T017)는 **동시 진행 금지**(같은 파일 충돌)
- T002(ArchUnit)는 T007~T009(도메인의 Gradle 경계 소멸) **이전** 필수
- T014·T015 는 서로 다른 디렉터리라 [P] 가능하나 settings 편집은 한 커밋으로 몰아도 됨
- T020~T022 는 서로 다른 파일 — [P] 병렬 가능
- US1 게이트(빌드·기동)는 T012(PR #1)·T018(PR #2) 두 체크포인트에서 반복 실행

## Implementation Strategy

- **PR #1 (T001~T012)**: `:common` 분리만으로 독립 리뷰·머지 가능 — batch·infra 관점 변화가 이 PR 에 전부 담긴다. 머지 후 develop 은 13모듈의 그린 상태
- **PR #2 (T013~T025)**: PR #1 머지 후 이어서 — api 흡수·모듈 제거·문서/헌법. 리뷰 diff 가 "api 로 모으기 + 문서" 로 좁혀진다
- 각 태스크 = 그린 빌드 커밋 1개 이상. `git mv` 로 이력 보존. 중단 지점은 항상 Checkpoint

## Notes

- **T001 기준선 (2026-07-28)**: `clean build` BUILD SUCCESSFUL **3m 21s**, 100 actionable tasks. 모듈 16개: `:core`, `:domain:{food,member,avoidance,review,scan,bookmark,image,metering}`, `:application`, `:infra:{llm,auth,redis,storage}`, `:app:{api,batch}`. 도메인 간 허용 그래프: scan→{food,member,image} · food→{member,avoidance} · bookmark→{food,member} · member→{avoidance} · image·metering·avoidance→{}. `:application` 에는 Home·Auth 외 foodimage(FoodImageBatchSubmitService 등)도 존재 — T016 이동 대상
- 이동 태스크의 "참조 재배선" = 해당 모듈을 project() 로 참조하는 모든 build.gradle.kts + buildSrc 아키타입
- 패키지명은 어떤 태스크에서도 바꾸지 않는다(plan Decision 4)
