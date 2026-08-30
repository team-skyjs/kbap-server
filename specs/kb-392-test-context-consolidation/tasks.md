# Tasks: 테스트 Spring 컨텍스트 통합

**Input**: `/specs/kb-392-test-context-consolidation/` (plan · research · data-model · quickstart) · Jira KB-392

**Tests**: Test-First (헌법 I). Red = 대표 클래스 2개의 헤더를 아직 없는 `@IntegrationTest` 로 바꿔 컴파일 실패 → 애너테이션 신설로 Green. 기존 93개 통합 테스트가 회귀 테스트. 테스트 본문은 바꾸지 않는다(FR-009).

**Organization**: US1 api → US2 common → US3 batch → US4 문서 → Polish. Setup/Foundational 없음.

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

- api 테스트 `api/src/test/kotlin/com/kbap/api/`, common `common/src/test/kotlin/com/kbap/common/`, batch `batch/src/test/kotlin/com/kbap/batch/`
- 일괄 치환 스크립트는 스크래치패드에 두고 `python3` 로 실행(레포에 남기지 않음)

---

## Phase 1: User Story 1 - api 통합 테스트를 한 가지 설정으로 통일 (Priority: P1) 🎯 MVP

**Goal**: api 76개 헤더 → `@IntegrationTest`, 페이크 통일, 통계 프로퍼티 전역화. 컨텍스트 8 → 2.

**Independent Test**: `./gradlew :api:test` 그린 + 실행 중 MySQL 컨테이너 ≤ 2 (quickstart §1).

### Tests for User Story 1 (Test-First) ⚠️

- [x] T001 [US1] Red: `api/src/test/kotlin/com/kbap/api/food/FoodDetailControllerTest.kt`(MockMvc 有)와 `api/src/test/kotlin/com/kbap/api/food/FoodServiceTest.kt`(MockMvc 無·`properties` 변형) 두 파일의 `@SpringBootTest…`·`@AutoConfigureMockMvc`·`@Import(…)` 줄을 `@IntegrationTest` 한 줄로 바꾸고 `./gradlew :api:compileTestKotlin` 이 "unresolved reference IntegrationTest" 로 실패함을 확인.

### Implementation for User Story 1

- [x] T002 [US1] 페이크 이동·통일: `api/src/test/kotlin/com/kbap/api/auth/FakeSocialTokenVerifierConfig.kt` 신규 — `AuthControllerTest.kt` 바닥의 `FakeSocialTokenVerifier`·`FakeSocialAccountDeleter`·`@TestConfiguration FakeSocialTokenVerifierConfig` 를 그대로 옮기되 `verify()` 를 `SocialIdentity(SocialProvider.GOOGLE, idToken, DEFAULT_EMAIL)` 로, companion 에 `DEFAULT_EMAIL = "user@gmail.com"` 추가. `AuthControllerTest.kt` 에서 해당 정의 삭제. `api/src/test/kotlin/com/kbap/api/scenario/ScenarioSocialTokenVerifierConfig.kt` 삭제.
- [x] T003 [US1] `api/src/test/kotlin/com/kbap/api/IntegrationTest.kt` 신규 — plan.md 의 합성 애너테이션(`@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(MySqlContainerConfig, RedisContainerConfig, FakeSocialTokenVerifierConfig, FakePlaceSearchConfig)`). `api/src/test/resources/application.yml` 에 `spring.jpa.properties.hibernate.generate_statistics: true` 추가.
- [x] T004 [US1] Green 확인: `./gradlew :api:test --tests '*FoodDetailControllerTest*' --tests '*FoodServiceTest*'` 그린이고 실행 중 `docker ps --filter ancestor=mysql:8.4 | wc -l` = 1(두 클래스가 한 컨텍스트). 1 이 아니면 R-1 의 메타 애너테이션 가정이 틀린 것 — 원인 확인 후 진행.
- [x] T005 [US1] 인증/회원 3개 — `api/src/test/kotlin/com/kbap/api/auth/AuthControllerTest.kt`·`member/MemberControllerTest.kt`·`member/MemberProfileUpdateVersionTest.kt`: 로그인 헬퍼 기본 토큰 `"valid-token"` → `FakeSocialTokenVerifier.DEFAULT_SUB`(파일 내 `"valid-token"` 리터럴 전부 같은 상수로). 헤더는 T006 에서 일괄.
- [x] T006 [US1] 나머지 api 통합 테스트 74개 헤더 일괄 치환 — 스크래치패드 `headers.py`: `@SpringBootTest…`(properties 포함, `StructuredConsoleLoggingTest` 제외)·`@AutoConfigureMockMvc`·`@Import(...)`(여러 줄 가능) 블록을 `@IntegrationTest` 로 바꾸고, 헤더 외에 쓰이지 않는 import(`SpringBootTest`·`AutoConfigureMockMvc`·`Import`·`MySqlContainerConfig`·`RedisContainerConfig`·`ScenarioSocialTokenVerifierConfig`·`FakeSocialTokenVerifierConfig`·`FakePlaceSearchConfig`) 제거, `import com.kbap.api.IntegrationTest` 추가(같은 패키지 `com.kbap.api` 직속 파일은 불필요). `git diff --stat` 로 파일당 변경이 헤더·import 뿐인지 확인. `StructuredConsoleLoggingTest.kt` 는 손대지 않는다.
- [x] T007 [US1] `./gradlew :api:test` 전체 그린 + 실행 중 MySQL 컨테이너 수 기록(목표 2). 순서 의존 실패가 나오면 그 클래스에만 `beforeSpec`/`afterSpec` 정리 추가(R-6) 후 재실행.
- [x] T008 [US1] 커밋 `test(api): 통합 테스트 헤더를 @IntegrationTest 하나로 통일하고 소셜 인증 페이크 통합`.

**Checkpoint**: api 컨텍스트 2, 76개 그린, 본문 diff 없음.

---

## Phase 2: User Story 2 - common 부트 클래스 단일화 (Priority: P2)

**Goal**: `*TestApp` 7 → `CommonTestApp` 1, 헤더 통일. 컨텍스트 7 → 1.

**Independent Test**: `./gradlew :common:test` 그린 + MySQL 컨테이너 1.

### Tests for User Story 2 (Test-First) ⚠️

- [x] T009 [US2] Red: `common/src/test/kotlin/com/kbap/common/domain/block/BlockTestApp.kt` 삭제 + `MemberBlockJpaRepositoryTest.kt` 헤더를 `@SpringBootTest` / `@Import(MySqlContainerConfig::class)` 로(`classes=` 제거) → `./gradlew :common:test --tests '*MemberBlockJpaRepositoryTest*'` 가 "Unable to find a @SpringBootConfiguration" 으로 실패 확인.

### Implementation for User Story 2

- [x] T010 [US2] `common/src/test/kotlin/com/kbap/common/CommonTestApp.kt` 신규(plan.md) → T009 테스트 Green.
- [x] T011 [US2] 나머지 `*TestApp` 6개 삭제(`admin/AdminTestApp`·`food/FoodTestApp`·`ingredient/IngredientTestApp`·`member/MemberServiceTestApp`·`report/ReportTestApp`·`review/ReviewTestApp`), 11개 테스트 헤더를 `@SpringBootTest` / `@Import(MySqlContainerConfig::class)` 로 통일(`classes = [...]` 제거, `*TestApp` import 제거).
- [x] T012 [US2] `./gradlew :common:test` 그린 + MySQL 컨테이너 1 확인. 커밋 `test(common): 도메인별 TestApp 7개를 CommonTestApp 하나로 통일`.

---

## Phase 3: User Story 3 - batch 설정 통일 (Priority: P3)

**Goal**: 6개 헤더 → `@BatchIntegrationTest`. 컨텍스트 3 → 1.

**Independent Test**: `./gradlew :batch:test` 그린 + MySQL 컨테이너 1.

### Tests for User Story 3 (Test-First) ⚠️

- [x] T013 [US3] Red: `batch/src/test/kotlin/com/kbap/batch/KbapBatchApplicationTests.kt` 헤더를 `@BatchIntegrationTest` 로 → 컴파일 실패 확인.

### Implementation for User Story 3

- [x] T014 [US3] `batch/src/test/kotlin/com/kbap/batch/trigger/SlowJobTestConfig.kt` 신규(`BatchJobTriggerControllerTest.kt` 바닥에서 이동) + `batch/src/test/kotlin/com/kbap/batch/BatchIntegrationTest.kt` 신규(plan.md). 나머지 5개 헤더 치환, 미사용 import 정리.
- [x] T015 [US3] `./gradlew :batch:test` 그린 + MySQL 컨테이너 1 확인. 커밋 `test(batch): 배치 테스트 헤더를 @BatchIntegrationTest 하나로 통일`.

---

## Phase 4: User Story 4 - 규칙 문서화 (Priority: P4)

- [x] T016 [P] [US4] `CLAUDE.md` 테스트 스타일 절(BehaviorSpec 항목 아래)에 규칙 추가: "**통합 테스트 헤더는 `@IntegrationTest`(api)·`@BatchIntegrationTest`(batch) 하나로 고정** — `@SpringBootTest`·`@AutoConfigureMockMvc`·`@Import`·`properties` 를 클래스에 직접 쓰지 않는다. 설정 조합이 한 글자라도 다르면 Spring 이 새 컨텍스트(= 새 MySQL 컨테이너 + Flyway 재실행)를 만들어 캐시에 살려 두기 때문(KB-392: api 8→2, common 7→1, batch 3→1). 유일 예외 `StructuredConsoleLoggingTest`(콘솔 형식 전환). common 리포지토리 테스트는 `@SpringBootTest` + `@Import(MySqlContainerConfig::class)` 로 통일하고 부트 클래스는 `CommonTestApp` 하나. 페이크(소셜 인증·장소 검색)는 모든 api 통합 컨텍스트에 기본 포함 — 상태는 `beforeSpec` 에서 `reset()`." 85행의 `@ServiceConnection` 문구는 유지(KB-391 몫).
- [x] T017 [P] [US4] `../kbap-agenthub/wiki/test-context-consolidation.md` 신설(캐시 키 구성 요소 표·조합 관측치 before/after·페이크 통일 결정·ecs 예외·컨테이너 공유는 KB-391) + `INDEX.md` 한 줄. 허브 커밋 `docs(wiki): 테스트 컨텍스트 통합 규칙(KB-392)`.
- [x] T018 [US4] kbap 커밋 `docs: CLAUDE.md 에 통합 테스트 단일 헤더 규칙 기록`.

---

## Phase 5: Polish

- [x] T019 quickstart §3 — `git diff develop -- '*Test.kt'` 에서 헤더·import·페이크 파일·기본 토큰 상수 외 변경이 없는지 확인, Kotlin 주석 0건.
- [x] T020 quickstart §4 — `./gradlew clean build && ./gradlew build --rerun-tasks` 2회 그린, §1 수치 기록.
- [x] T021 `open-draft-pr-to-develop` 스킬로 draft PR(base develop, `Refs KB-392`, 기능 흐름 섹션 삭제). 본문에 before/after 컨텍스트 수 표.

---

## Dependencies & Execution Order

- US1: T001 → T002‖T003 → T004 → T005‖T006 → T007 → T008
- US2: T009 → T010 → T011 → T012 (US1 과 독립, 다른 모듈)
- US3: T013 → T014 → T015 (독립)
- US4: T016‖T017 → T018 (수치는 T007·T012·T015 뒤)
- Polish: 전부 뒤

## Implementation Strategy

- MVP = US1(api 8→2 가 효과의 대부분). US2·US3 는 같은 패턴의 기계 치환.
- 커밋 4 + 위키 1.

## Notes

- 치환 스크립트는 헤더 블록만 건드리고 본문은 절대 손대지 않는다 — 실행 후 `git diff` 육안 확인 필수(KB-375 에서 정규식이 파일을 망친 전례).
- 컨테이너 수 측정은 KB-391 이전이라 유효(컨테이너 = 컨텍스트).
