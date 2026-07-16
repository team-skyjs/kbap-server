# Tasks: E2E 시나리오 테스트 도입 — 핵심 사용자 여정 4종 인수 테스트

**Input**: Design documents from `/specs/kb-167-e2e-scenario-tests/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: 이 기능의 산출물 자체가 테스트다(헌법 원칙 I 충족 형태는 plan.md Constitution Check 참조). 프로덕션 코드 변경 0줄 — 모든 파일은 `app/api/src/test` 하위.

**Organization**: 사용자 스토리(여정)별 페이즈. 공통 기반(드라이버·페이크·시드·태그)은 Foundational 로 선행.

## Phase 1: Setup

이 기능은 신규 모듈·의존·빌드 설정이 없다(기존 `spring-boot-starter-test`·Kotest·Testcontainers·`-Dkotest.tags` 전달 재사용). Setup 태스크 없음.

## Phase 2: Foundational (모든 여정의 전제)

**⚠️ CRITICAL**: 여정 스펙 4개가 전부 이 기반 위에서 동작한다.

- [ ] T001 [P] 시나리오 전용 소셜 페이크 작성 — `app/api/src/test/kotlin/com/kbap/app/api/scenario/ScenarioSocialTokenVerifierConfig.kt`: `@TestConfiguration` + `@Primary` `SocialTokenVerifier`(verify 가 `SocialIdentity(GOOGLE, sub = idToken, email = null)` 반환 — idToken→sub 파생으로 여정별 신규 계정 보장) + no-op `SocialAccountDeleter` 페이크(탈퇴 여정용, 기존 `FakeSocialAccountDeleter` 패턴 참조)
- [ ] T002 [P] 시나리오 음식 시드 작성 — `app/api/src/test/kotlin/com/kbap/app/api/scenario/ScenarioFoodSeed.kt`: `ensureFood(dataSource, koreanName, spiciness, substances)` — food 는 id 명시 없이 INSERT(auto-increment), 기피물질은 code 기준 insert-if-absent(**DELETE 문 금지** — Flyway 카탈로그 81종 보존), food_avoidance_substance 매핑은 생성된 food id 로 INSERT. 기존 `FoodTestSeed`(`app/api/src/test/kotlin/com/kbap/app/api/food/FoodTestSeed.kt`)의 INSERT 컬럼 목록 참조하되 clear 계열은 만들지 않는다
- [ ] T003 시나리오 API 드라이버 작성 — `app/api/src/test/kotlin/com/kbap/app/api/scenario/ScenarioApiDriver.kt`: 생성자 `(mockMvc, 여정접두어)` 가 `"scenario-<접두어>-<UUID>"` idToken 을 만들고, 여정 상태 필드(accessToken·refreshToken·objectKey·foodId)를 보유. **한국어 스텝 메서드**로 MockMvc 호출·JSON 파싱을 전부 캡슐화 — data-model.md 의 스텝 메서드 계약 표(회원가입한다·재로그인한다·온보딩한다·홈을_조회한다·음식을_검색한다·음식_상세를_조회한다·북마크한다·북마크_목록을_조회한다·만료된_액세스토큰으로_프로필을_조회한다·토큰을_갱신한다·구_리프레시토큰으로_갱신을_시도한다·로그아웃한다·프로필을_조회한다·업로드URL을_발급받는다·업로드를_완료한다·스캔한다·탈퇴한다) 전체 구현. 만료 토큰은 `@Autowired AuthTokenProperties` 를 받아 `JwtTokenIssuer(properties.copy(accessTtl = 음수 Duration))` 로 생성(기존 `JwtAuthenticationFilterTest` 선례)

**Checkpoint**: 드라이버·페이크·시드가 컴파일된다(`./gradlew :app:api:compileTestKotlin`)

## Phase 3: User Story 1 — 해피패스 여정 (Priority: P1) 🎯 MVP

**Goal**: 가입→온보딩→홈→검색→상세→북마크가 한 시나리오로 통과

**Independent Test**: `./gradlew :app:api:test --tests "com.kbap.app.api.scenario.HappyPathScenarioTest"`

- [ ] T004 [US1] `HappyPathScenarioTest` 작성 — `app/api/src/test/kotlin/com/kbap/app/api/scenario/HappyPathScenarioTest.kt`: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@Import(MySqlContainerConfig, RedisContainerConfig, ScenarioSocialTokenVerifierConfig)` + `@Tags("scenario")`, BehaviorSpec(given/when/then 한국어). 본문: `ScenarioFoodSeed.ensureFood`(여정 고유 korean_name, 카탈로그 실존 code 사용) → `회원가입한다()`(newMember=true 단언) → `온보딩한다(기피 code 포함)` → `홈을_조회한다()`(authenticated=true·avoidedSubstances 반영) → `음식을_검색한다(고유명)`(foodId 발견) → `음식_상세를_조회한다()` → `북마크한다()` → `북마크_목록을_조회한다()`(해당 foodId 노출)
- [ ] T005 [US1] 실행·통과 확인 — `./gradlew :app:api:test --tests "com.kbap.app.api.scenario.HappyPathScenarioTest"` 통과. 실패 시 드라이버/시드 수정(프로덕션 코드는 건드리지 않는다 — 실패가 실제 회귀를 드러내면 보고)

**Checkpoint**: MVP — 해피패스 여정 안전망 확보

## Phase 4: User Story 2 — 인증 생명주기 여정 (Priority: P2)

**Goal**: 로그인→만료(AUTH-004)→갱신→로그아웃→재로그인 상태 전이 검증

**Independent Test**: `./gradlew :app:api:test --tests "com.kbap.app.api.scenario.AuthLifecycleScenarioTest"`

- [ ] T006 [P] [US2] `AuthLifecycleScenarioTest` 작성 — `app/api/src/test/kotlin/com/kbap/app/api/scenario/AuthLifecycleScenarioTest.kt`(구성 애너테이션 T004 와 동일): `회원가입한다()` → `만료된_액세스토큰으로_프로필을_조회한다()`(401 + code=AUTH-004) → `토큰을_갱신한다()`(rotation — 구 refreshToken 보관) → `프로필을_조회한다()`(새 accessToken 으로 성공) → `로그아웃한다()` → `구_리프레시토큰으로_갱신을_시도한다(로그아웃된 토큰)`(거절) → `재로그인한다()`(성공, 같은 계정=newMember false 단언)
- [ ] T007 [US2] 실행·통과 확인 — 해당 스펙 단독 실행 통과

## Phase 5: User Story 3 — 메뉴판 스캔 여정 (Priority: P3)

**Goal**: URL 발급→업로드 완료→스캔→위험도→최근스캔 노출 검증

**Independent Test**: `./gradlew :app:api:test --tests "com.kbap.app.api.scenario.MenuScanScenarioTest"`

- [ ] T008 [P] [US3] `MenuScanScenarioTest` 작성 — `app/api/src/test/kotlin/com/kbap/app/api/scenario/MenuScanScenarioTest.kt`(구성 T004 동일 + `@Autowired FakeMenuBoardVisionExtractor`·`FakeStorageObjectStore`): `ScenarioFoodSeed.ensureFood`(스캔 매칭용 고유 korean_name) → `회원가입한다()` → `온보딩한다()` → `업로드URL을_발급받는다(image/jpeg, size)`(objectKey 확보) → `FakeStorageObjectStore.put(objectKey, …)` 주입 후 `업로드를_완료한다()` → `FakeMenuBoardVisionExtractor.program(objectKey, ExtractedMenu(매칭 korean_name·matchedIdx·price))` 주입 후 `스캔한다(items)`(matched=true·foodId·riskLevel 단언) → `홈을_조회한다()`(recentScans 에 해당 음식 노출 — "스캔 히스토리 조회" 매핑, research.md R4)
- [ ] T009 [US3] 실행·통과 확인 — 해당 스펙 단독 실행 통과

## Phase 6: User Story 4 — 탈퇴 여정 (Priority: P4)

**Goal**: 탈퇴 후 토큰 무효·재가입 신규 회원 검증

**Independent Test**: `./gradlew :app:api:test --tests "com.kbap.app.api.scenario.WithdrawScenarioTest"`

- [ ] T010 [P] [US4] `WithdrawScenarioTest` 작성 — `app/api/src/test/kotlin/com/kbap/app/api/scenario/WithdrawScenarioTest.kt`(구성 T004 동일): `ScenarioFoodSeed.ensureFood` → `회원가입한다()` → `온보딩한다()` → `음식을_검색한다()` → `북마크한다()` → `탈퇴한다()` → `프로필을_조회한다()`(구 accessToken 실패 — 200 아님 단언) → `구_리프레시토큰으로_갱신을_시도한다(탈퇴 전 토큰)`(거절) → `재로그인한다()`(같은 idToken, newMember=true) → `북마크_목록을_조회한다()`(0건 — 이전 활동 미노출)
- [ ] T011 [US4] 실행·통과 확인 — 해당 스펙 단독 실행 통과

## Phase 7: Polish & 최종 검증

- [ ] T012 태그 선별/제외 동작 확인 — `./gradlew :app:api:test -Dkotest.tags="scenario"`(시나리오 4개만 실행, SC-005) 및 `-Dkotest.tags="!scenario"`(시나리오 0개 실행) 확인
- [ ] T013 반복 실행 안전 확인 — 시나리오 태그 실행을 `--rerun-tasks` 로 연속 2회 통과(SC-004, quickstart.md)
- [ ] T014 전체 스위트 확인 — `./gradlew :app:api:test` 전체 통과(FR-008·SC-002). 기존 테스트(특히 `AuthControllerTest`·`MemberFoodJourneyTest`)와의 간섭 없음 확인

## Dependencies & Execution Order

- Phase 2(T001~T003) → 모든 여정의 전제. T001·T002 는 병렬 가능, T003 은 독립 파일이나 가장 크다.
- Phase 3~6(각 여정): 서로 독립 — T004 로 드라이버가 실전 검증된 뒤 T006·T008·T010 은 병렬 작성 가능.
- Phase 7 은 전 여정 완료 후.

**MVP scope**: Phase 2 + Phase 3 (해피패스 1종).

## Implementation Strategy

1. 기반(T001~T003) → 해피패스(T004~T005)로 드라이버 계약을 실전 확정 — 여기서 드라이버 시그니처가 안정된다.
2. 나머지 여정 3종은 확정된 드라이버 위에 병렬 작성.
3. 여정별 통과 시점마다 커밋(개발 워크플로 — 작업/논리 단위 커밋).
4. 실패가 프로덕션 회귀를 드러내면 **고치지 않고 보고**한다(이번 범위는 프로덕션 0줄).
