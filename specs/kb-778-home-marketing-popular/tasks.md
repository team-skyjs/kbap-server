# Tasks: 홈 인기 음식 마케팅 임시 목록

**Input**: Design documents from `specs/kb-778-home-marketing-popular/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/home-marketing-popular.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리의 테스트를 구현보다 먼저 쓰고 Red 를 확인한다. 전부 Kotest `BehaviorSpec`, `given/when/then` 한국어. Kotest 는 Gradle `--tests` 필터를 무시하므로 실행은 항상 모듈 전체(`./gradlew :api:test`)다. Kotlin 소스 주석 금지.

**Organization**: 스토리별 묶음. 통합 테스트는 한 파일(`HomeMarketingPopularTest.kt`)에 스토리별 `given` 을 쌓고, 단위 테스트도 한 파일(`HomeMarketingPopularPropertiesTest.kt`)에 쌓는다.

> **2026-10-13 개정 이력**: ① Phase 3~6 — `2.1+` 오버로드 + 기간 + 설정 순서(커밋됨). ② Phase 8 — 사용자 정정 "별도 API 아님" 으로 버전 분기 제거. ③ Phase 9 — 사용자 재정정 "2.1 요청이면 무조건 지정 id 중 10개" — 핸들러 하나 + 헤더 분기, 기간 제거, 랜덤 10, 폴백 없음. ④ Phase 10 — 번호를 `1.2` 로(현재 앱 기본 헤더 1.1 의 다음). ⑤ Phase 11 — 풀을 yml/env 에서 테이블 `home_marketing_popular_food` 로. 아래 Phase 3~10 의 "2.1"·"설정" 은 당시 기록이다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·선행 의존 없음 → 병렬 가능
- **[Story]**: US1(임시 목록 노출), US2(인기 섹션 외 불변), US3(기간 밖·빈 목록 폴백), US4(비READY·미존재 제외)

## Path Conventions

모듈러 모놀리스 — 운영 `api/src/main/kotlin/com/kbap/api/{home,food,core/config}/`, 테스트 `api/src/test/kotlin/com/kbap/api/{home,...}/`, 공유 테스트 픽스처 `common/src/testFixtures/kotlin/com/kbap/common/core/testsupport/`.

---

## Phase 1: Setup (설정 키)

**Purpose**: 설정 키를 연다. Clock 빈·MutableClock 은 2026-10-13 사용자 결정으로 두지 않는다(기간 밖은 단위 테스트).

- [X] T001 `api/src/main/resources/application.yml` 의 `kbap:` 아래에 `home.marketing-popular` 블록을 추가한다 — `starts-at: ${HOME_MARKETING_POPULAR_STARTS_AT:}`, `ends-at: ${HOME_MARKETING_POPULAR_ENDS_AT:}`, `food-ids: ${HOME_MARKETING_POPULAR_FOOD_IDS:}`. yml 주석 3줄: 용도(KB-778, X-API-Version 2.1+ 만)·시각은 Asia/Seoul ISO(시작 포함·종료 제외)·비어 있으면 조회 수 집계. `api/src/test/resources/application.yml` 의 `kbap:` 에는 고정값 `starts-at: 2000-01-01T00:00:00`, `ends-at: 2100-01-01T00:00:00`, `food-ids: 3,1,999,2` 와 주석(통합 테스트는 항상 기간 안, 999 는 미존재 id).

---

## Phase 2: Foundational

해당 없음 — 스키마·공통 인프라 변경이 없다. 설정 홀더·서비스 분기는 US1 의 산출물이다.

---

## Phase 3: User Story 1 - 새 버전 앱은 마케팅 기간에 외국인 선호 음식을 인기 음식으로 본다 (Priority: P1) 🎯 MVP

**Goal**: `GET /api/home` 에 `2.1+` 매핑을 추가하고, 기간 안이면 설정 `food-ids` 순서대로 `popularFoods` 를 내려준다. 사용자별 값·언어는 기존 조립을 재사용한다.

**Independent Test**: 테스트 yml 창(2000~2100) 안으로 시각을 두고 음식 1~3 을 시드하면 `X-API-Version: 2.1` 응답의 `popularFoods` id 가 `[3, 1, 2]` 다. 같은 요청에서 회원의 기피 성분 위험도·일본어 이름이 기존대로 나온다.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T002 [P] [US1] `api/src/test/kotlin/com/kbap/api/home/HomeMarketingPopularPropertiesTest.kt` 를 Spring 없이 `BehaviorSpec` 으로 작성한다. given("마케팅 인기 음식 설정"): (1) when("기간 안에 조회하면") then("설정한 id 를 설정 순서대로 돌려준다") — `HomeMarketingPopularProperties(startsAt = 2026-11-01T00:00, endsAt = 2026-11-15T00:00, foodIds = [6, 4, 5]).activeFoodIds(2026-11-03T12:00) shouldBe [6, 4, 5]`, (2) when("같은 id 가 두 번 있으면") then("첫 등장만 남긴다") — `[6, 4, 6, 5]` → `[6, 4, 5]`. given("env 플레이스홀더 바인딩"): when("세 키가 전부 빈 문자열이면") then("시작·종료는 null, 목록은 빈 목록으로 바인딩된다") — `Binder(MapConfigurationPropertySource(mapOf("kbap.home.marketing-popular.starts-at" to "", ".ends-at" to "", ".food-ids" to ""))).bind("kbap.home.marketing-popular", HomeMarketingPopularProperties::class.java)` 결과 검증(`org.springframework.boot.context.properties.bind.Binder`·`source.MapConfigurationPropertySource`). when("콤마 목록과 ISO 시각이면") then("List<Long> 과 LocalDateTime 으로 바인딩된다"). `./gradlew :api:test` 로 **컴파일 실패(Red)** 확인.
- [X] T003 [P] [US1] `api/src/test/kotlin/com/kbap/api/home/HomeMarketingPopularTest.kt` 를 `@IntegrationTest` + `BehaviorSpec` 으로 작성한다. 주입: `MockMvc`·`DataSource`·`TokenIssuer`·`PopularFoodIdCache`. 헬퍼 `home(memberId: Long?, version: String, lang: String = "en")` 은 `mockMvc.get("/api/home?lang=$lang") { header("X-API-Version", version); memberId?.let { header("Authorization", "Bearer ...") } }`, `popularIds(memberId, version)` 은 `payload.popularFoods[].foodId`. `beforeContainer`: `HomeTestSeed.reset(dataSource)`, `popularFoodIdCache.invalidateAll()`. 테스트 yml 창(2000~2100)이라 항상 기간 안이다. given("2.1 홈 — 마케팅 기간 안"): (1) when("음식 1·2·3 이 READY 로 있으면") then("popularFoods 는 설정 순서 3, 1, 2 다") — `HomeTestSeed.seedReadyFoods(dataSource, 3)` 후 `popularIds(null, "2.1") shouldContainExactly listOf(3L, 1L, 2L)`, (2) when("기피 성분이 EGG 인 회원이 조회하면") then("음식 1 의 overallRiskStatus 는 DANGER 이고 목록 순서는 그대로다") — `HomePopularCacheTest` 의 시드(`seedFoodSubstance`·`seedMember`) 재사용, (3) when("ja 로 조회하면") then("첫 항목 이름이 メニュー3 이다"), (4) when("비회원이 조회하면") then("authenticated 는 false 이고 popularFoods 는 3, 1, 2 다"). `./gradlew :api:test` 로 Red 확인 — 2.1 요청이 400(COMMON-002: 미지원 버전)으로 실패해야 한다.

### Implementation for User Story 1

- [X] T004 [US1] `api/src/main/kotlin/com/kbap/api/home/HomeMarketingPopularProperties.kt` 를 생성한다 — `@ConfigurationProperties("kbap.home.marketing-popular") data class HomeMarketingPopularProperties(val startsAt: LocalDateTime? = null, val endsAt: LocalDateTime? = null, val foodIds: List<Long> = emptyList())` 와 `fun activeFoodIds(now: LocalDateTime): List<Long>` — `startsAt == null || endsAt == null || !startsAt.isBefore(endsAt)` 또는 `now.isBefore(startsAt) || !now.isBefore(endsAt)` 이면 `emptyList()`, 아니면 `foodIds.distinct()`. `api/src/main/kotlin/com/kbap/api/core/config/PropertiesConfig.kt` 클래스에 `@EnableConfigurationProperties(HomeMarketingPopularProperties::class)` 를 단다. `./gradlew :api:test` 로 T002 Green 확인.
- [X] T005 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` 에 `getCuratedFoods` 를 추가한다 — `@Transactional(readOnly = true) fun getCuratedFoods(memberId: Long?, lang: LanguageCode, ids: List<Long>): List<FoodSummaryView> = summaryViews(loadInGivenOrder(ids), lang, memberId)`(`getPopularFoods` 바로 아래, READY 필터는 US4 에서). `getPopularFoods` 본문은 손대지 않는다.
- [X] T006 [US1] `api/src/main/kotlin/com/kbap/api/home/HomeService.kt` 를 수정한다 — 생성자에 `private val marketingPopular: HomeMarketingPopularProperties` 추가. `getHome(memberId, lang)` 은 기존대로 캐시 id 로 `assembleHome(memberId, lang, popularFoodIds, curated = false)`. 신규 `fun getMarketingHome(memberId: Long?, lang: LanguageCode): HomeResult` — `val curated = marketingPopular.activeFoodIds(LocalDateTime.now(SEOUL)).take(POPULAR_SIZE)`(companion `SEOUL = ZoneId.of("Asia/Seoul")`); 비어 있으면 `return getHome(memberId, lang)`, 아니면 `readOnlyTransaction.execute { assembleHome(memberId, lang, curated, curated = true) }!!`. `assembleHome` 에 `curated: Boolean` 파라미터를 추가해 `if (curated) foodService.getCuratedFoods(member?.id, lang, popularFoodIds) else foodService.getPopularFoods(member?.id, lang, popularFoodIds)` 로 분기한다. 나머지 조립은 공유.
- [X] T007 [US1] `api/src/main/kotlin/com/kbap/api/home/HomeController.kt` 에 `@GetMapping(version = "2.1+") override fun homeWithMarketingPopular(@Valid @ModelAttribute request: HomeRequest, @AuthMemberIdOrNull memberId: Long?): ResponseEntity<BaseResponse<HomeResponse>>` 를 추가한다 — 본문은 `homeService.getMarketingHome(memberId, LanguageCode.from(request.lang))` 을 `HomeResponse.from(result, authenticated = memberId != null)` 으로 감싼다. 기존 `home` 매핑은 `version` 없이 그대로. `api/src/main/kotlin/com/kbap/api/home/HomeApi.kt` 에 같은 시그니처의 `homeWithMarketingPopular` 를 추가하고 `@Operation(operationId = "homeMarketingPopular", summary = "홈 화면 조회 — 마케팅 인기 음식(2.1+)", description = ...)` 으로 "적용 기간 안이면 popularFoods 가 운영 설정 목록(설정 순서, READY 만, 최대 10, 빈자리 미보충)이고 기간 밖·목록 없음이면 1.0 과 동일" 을 적는다. `@ApiResponses` 는 기존 `home` 과 같게. 기존 `home` 설명의 "인기 음식: 무작위 최대 10개" 문단을 "최근 30일 조회 수 상위 최대 10개(내림차순, 동률 id 내림차순)" 로 현행화한다.
- [X] T008 [US1] `api/src/main/kotlin/com/kbap/api/core/config/OpenApiConfig.kt` 에 `apiVersion21Doc` 빈(`versionDoc("2.1", ...)`, `apiVersion20Doc` 바로 아래 동일 형태)을 추가하고 `NOTICE` 의 그룹 안내 문구를 `X-API-Version 1.0/1.1/2.0/2.1` 로 바꾼다. `./gradlew :api:test` 전체 Green 확인 — T003 네 시나리오, `OpenApiSnapshotTest`(버전 그룹 드리프트 가드·operationId 유일), 기존 홈 테스트 3개 전부.

**Checkpoint**: 2.1 앱은 기간 안에서 설정 목록을 본다. 커밋: `feat(home): X-API-Version 2.1+ 홈에 마케팅 인기 음식 임시 목록을 내려준다`.

---

## Phase 4: User Story 2 - 구 버전 앱은 아무 영향을 받지 않는다 (Priority: P1)

**Goal**: 같은 설정·같은 시각에서 1.0 응답의 `popularFoods` 는 조회 수 집계이고, 인기 섹션 외 전 필드가 2.1 과 같음을 통합 테스트로 고정한다(FR-001·FR-007·FR-008).

**Independent Test**: 기간 안 시각에서 1.0 과 2.1 로 각각 조회해 `popularFoods` 만 다르고 `avoidedSubstances`·`mostReviewedFoods`·`recentScans`·`authenticated` 는 JSON 노드 단위로 같다.

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T009 [US2] `HomeMarketingPopularTest.kt` 에 given("1.0 홈 — 마케팅 기간 안") 을 추가한다. (1) when("1.0 헤더로 조회하면") then("popularFoods 는 조회 수 집계 결과이고 설정 순서가 아니다") — 음식 3개 시드 후 음식 2 에 `food_view_log` 2건을 추가로 INSERT(`HomeTestSeed` 의 INSERT 문 형식 재사용) 하면 집계는 `[2, 3, 1]`(조회 수 desc, 동률 id desc) 이므로 `popularIds(null, "1.0") shouldContainExactly listOf(2L, 3L, 1L)` 이고 `popularIds(null, "2.1") shouldContainExactly listOf(3L, 1L, 2L)`, (2) when("1.0 과 2.1 로 같은 회원이 조회하면") then("popularFoods 를 뺀 payload 가 같다") — 두 payload 를 `ObjectNode` 로 받아 `remove("popularFoods")` 후 `shouldBe`. 이 테스트는 US1 구현 위에서 **바로 Green 이어야 정상**이다(구조 보장). Red 가 나면 1.0 경로가 오염된 것이므로 구현을 고친다.

### Implementation for User Story 2

- [X] T010 [US2] 구현 변경 없음을 확인한다 — `git diff develop -- api/src/main/kotlin/com/kbap/api/food/FoodService.kt` 에서 `getPopularFoods` 본문이 무변경이고, `HomeController.home` 에 `version` 인자가 없는지 본다. `HomeControllerTest`·`HomeGuestTest`·`HomePopularCacheTest` 는 수정하지 않는다.

**Checkpoint**: 1.0 계약 불변 고정. 커밋은 US1 커밋에 포함하거나 `test(home): 1.0 홈이 마케팅 목록의 영향을 받지 않음을 고정한다`.

---

## Phase 5: User Story 3 - 기간이 끝나거나 목록이 비면 자동으로 조회 수 집계로 돌아간다 (Priority: P2)

**Goal**: 기간 전·후·미설정·시작≥종료·빈 목록이면 2.1 도 조회 수 집계를 내려준다(FR-004). 경계는 시작 포함·종료 제외.

**Independent Test**: 단위 테스트로 시작 전·종료 후·경계·미설정·역전 창·빈 목록이 전부 빈 목록(=폴백)임을 고정한다. 통합 검증은 하지 않는다(단일 컨텍스트에서 기간을 바꿀 수 없고 Clock 빈은 기각 — PR 본문에 명시).

### Tests for User Story 3 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T011 [P] [US3] `HomeMarketingPopularPropertiesTest.kt` 의 given("마케팅 인기 음식 설정") 에 추가: when("시작 전이면")·when("종료 이후면") then("빈 목록이다"); when("시작 시각 정각이면") then("활성이다"); when("종료 시각 정각이면") then("빈 목록이다"); when("시작·종료 중 하나가 null 이면") then("빈 목록이다"); when("시작이 종료보다 늦으면") then("빈 목록이다"); when("기간 안이지만 food-ids 가 비어 있으면") then("빈 목록이다").

### Implementation for User Story 3

- [X] T012 [US3] `./gradlew :api:test` 를 돌려 T011 가 T004 의 `activeFoodIds` 로 이미 Green 인지 확인한다. Red 인 분기(경계 포함/제외·null·역전)만 `HomeMarketingPopularProperties.activeFoodIds` 에서 고친다. `getMarketingHome` 의 폴백이 `getHome` 위임임을 유지한다(캐시 호출이 트랜잭션 밖에서 돈다는 KB-725 `check` 가 통과해야 한다).

**Checkpoint**: 설정만으로 켜고 끈다. 커밋: `feat(home): 마케팅 기간 밖·빈 목록이면 조회 수 집계로 폴백한다`(변경이 없으면 테스트 커밋).

---

## Phase 6: User Story 4 - 보여줄 수 없는 음식은 목록에서 조용히 빠진다 (Priority: P2)

**Goal**: READY 가 아니거나 존재하지 않는(삭제 포함) id 는 제외하고 순서를 유지하며, 빈자리를 채우지 않고, 전부 제외되면 빈 목록이다(FR-005·FR-006).

**Independent Test**: 음식 1 을 FAILED 로 바꾸면 2.1 `popularFoods` 는 `[3, 2]`. 999 는 시드가 없어 항상 빠진다. 전부 FAILED 면 `[]` 이고 집계로 폴백하지 않는다.

### Tests for User Story 4 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T013 [US4] `HomeMarketingPopularTest.kt` 에 given("2.1 홈 — 노출 불가 음식") 을 추가한다. (1) when("목록의 음식 1 이 READY 가 아니면") then("popularFoods 는 3, 2 이고 길이 2 다") — `execute("UPDATE food SET content_status = 'FAILED' WHERE id = 1")`, (2) when("목록의 음식 1 이 삭제되면") then("popularFoods 는 3, 2 다") — `UPDATE food SET status = 'DELETED' WHERE id = 1`, (3) when("목록의 음식이 전부 READY 가 아니면") then("popularFoods 는 빈 배열이고 조회 수 집계로 폴백하지 않는다") — 1·2·3 전부 FAILED, 조회 로그는 있는 상태에서 `popularIds(null, "2.1") shouldBe emptyList()` 이면서 `popularIds(null, "1.0")` 는 비어 있지 않아야 한다(집계 SQL 도 READY 만 보므로 1.0 도 빈 목록이 된다 — 그러면 1.0 비교 대신 음식 4 를 READY 로 추가 시드해 1.0 은 `[4]`, 2.1 은 `[]` 로 가른다). `./gradlew :api:test` 로 (1)·(3) Red 확인((2) 는 `@SQLRestriction` 으로 이미 Green 일 수 있다).

### Implementation for User Story 4

- [X] T014 [US4] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` 의 `getCuratedFoods` 본문을 `summaryViews(loadInGivenOrder(ids).filter { it.isReady() }, lang, memberId)` 로 바꾼다. `./gradlew :api:test` 전체 Green 확인.

**Checkpoint**: 설정 실수·콘텐츠 상태 변화에도 준비 안 된 음식이 노출되지 않는다. 커밋: `feat(home): 마케팅 인기 목록에서 READY 가 아닌 음식을 제외한다`.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T015 [P] quickstart.md 2절대로 로컬 실측한다 — env 세 개를 주고 `:api:bootRun`(8081, `--no-daemon`, `DB_USERNAME=root DB_PASSWORD=root`), `curl` 로 2.1 이 설정 순서·1.0 이 집계인지 확인. env 를 전부 비우고 다시 띄워 기동 성공 + 2.1 이 집계를 내려주는지 확인. Swagger 그룹 드롭다운에 `X-API-Version 2.1` 과 홈 오퍼레이션(`homeMarketingPopular`)이 보이는지 확인.
- [X] T016 [P] 지식 위키를 갱신한다 — `../../../../kbap-agenthub/wiki/api-versioning-policy.md` 의 버전 매핑 표에 홈 `/api/home` 무버전 / `2.1+`(마케팅 인기 목록) 행을 추가하고, `wiki/home-marketing-popular-config.md` 를 새로 써서 설정 키·env·켜고 끄는 절차·READY/미존재 제외·테이블 대신 설정을 고른 이유(2026-10-13)·dev 대조표 위치를 남긴 뒤 `INDEX.md` 에 한 줄 추가, 허브에서 커밋.
- [X] T017 `./gradlew build` 전체 Green(ArchUnit 포함 — `HomeMarketingPopularProperties` 가 `api.home` 에 있어 경계 위반 없음) 확인 후 `open-draft-pr-to-develop` 스킬로 PR 을 열고 ready 전환, Codex 리뷰 도착을 확인한다.

---

## Phase 8: 버전 분기 제거 — 기존 홈 응답에서 대체 (2026-10-13 정정)

**Purpose**: `2.1+` 매핑·오퍼레이션·Swagger 그룹을 지우고 기존 `GET /api/home` 의 `popularFoods` 를 활성 설정 목록으로 대체한다. Codex P2(집계 캐시 워밍)도 함께 반영.

- [X] T018 `HomeController` 의 `homeWithMarketingPopular` 오버로드, `HomeApi` 의 2.1 오퍼레이션, `OpenApiConfig` 의 `apiVersion21Doc` 빈·안내 문구 `2.1` 을 제거한다. `HomeApi` 홈 설명의 인기 음식 문단에 마케팅 기간 대체 규칙을 적는다.
- [X] T019 `HomeService.getHome` 이 집계 id(`popularFoodIdCache` — 활성 중에도 매 요청 호출해 데워 둠)와 `activeFoodIds(now).take(POPULAR_SIZE)` 를 트랜잭션 밖에서 구한 뒤, 활성 목록이 있으면 `curated = true` 로 조립한다. `getMarketingHome` 삭제.
- [X] T020 테스트 yml 창을 비활성(2000-01-01~01-02)으로 바꿔 기존 홈 테스트가 집계를 보게 하고, `HomeMarketingPopularTest` 를 실제 빈들로 `HomeService` 를 직접 조립(활성/종료 설정 인스턴스)하는 형태로 다시 쓴다 — 활성 순서·회원 위험도·언어·종료 폴백·HTTP 비활성 집계·비READY/삭제/전부 제외 8건. `./gradlew :api:test` Green, spec/plan/research/contracts/quickstart·위키·PR 본문을 정정한다.

---

## Phase 9: 요청 버전 분기 + 랜덤 풀 (2026-10-13 재정정)

**Purpose**: 핸들러 하나에서 `X-API-Version` 2.1 이상이면 설정 풀의 READY 음식 중 호출마다 무작위 10개, 미만이면 집계. 기간 설정 제거, 폴백 없음.

- [X] T021 `api/src/main/kotlin/com/kbap/api/core/ApiVersions.kt` 신규(`HOME_MARKETING_POPULAR = "2.1"`, `isAtLeast(resolved, version)` — `SemanticApiVersionParser.Version` 비교). `WebConfig.configureApiVersioning` 에 `addSupportedVersions(ApiVersions.HOME_MARKETING_POPULAR)`.
- [X] T022 `HomeController.home` 에 `@RequestHeader(ApiHeaders.API_VERSION) apiVersion: String` 추가(`AuthController` 선례, 기존 `ApiVersions.isAtLeast(String, String)` 재사용 — 덮어쓰기 사고 후 복원), `HomeApi.home` 시그니처 동기화, 인기 음식 문구를 "2.1 이상은 설정 풀에서 호출마다 무작위 최대 10개" 로. `HomeService.getHome(memberId, lang, marketingPopular)` — true 면 `foodIds.distinct()`, false 면 집계 캐시. `FoodService.getRandomCuratedFoods(memberId, lang, ids, size)` — `findByIdIn → isReady → shuffled().take(size)`. `HomeMarketingPopularProperties` 는 `foodIds` 만. yml 에서 `starts-at`/`ends-at` 제거.
- [X] T023 테스트 — `HomeMarketingPopularPropertiesTest` 는 바인딩 2건, `HomeMarketingPopularTest` 는 헤더 2.1(풀 전부·12→10·비READY/삭제/전부 제외·회원 위험도·언어·인기 외 payload 동일) + 1.0/2.0 집계. 테스트 yml `food-ids: 1..12,999`. `./gradlew :api:test` Green, spec/plan/research/data-model/contracts/quickstart·위키·PR 본문 정정.

---

## Phase 10: 버전 번호 2.1 → 1.2 (2026-10-13)

- [X] T024 현재 앱 전역 기본 헤더가 `1.1`(kbap-fe `client.ts`)이라 다음 릴리스 마커 `1.2` 로 바꾼다 — `ApiVersions.HOME_MARKETING_POPULAR = "1.2"`(`addSupportedVersions` 는 상수 참조라 자동), `HomeApi`·yml 주석, 테스트 헤더 `1.2`, "1.2 미만" 케이스는 1.0·1.1, `2.0` 은 1.2 이상이라 풀(테스트로 고정). spec/plan/research/contracts/quickstart·위키·PR·메모리 동기화. `./gradlew :api:test` Green.

---

## Phase 11: 풀을 DB 테이블로 (2026-10-13 사용자 요청)

- [X] T025 Flyway `V2026.10.13.20.42.36__home_marketing_popular_food_table.sql`(food_id UNIQUE + FK food, BaseEntity 컬럼), 엔티티 `common.domain.food.model.HomeMarketingPopularFood(foodId)`, 리포지토리 `HomeMarketingPopularFoodJpaRepository.findAllFoodIds()`. `FoodService.getRandomMarketingPopularFoods(memberId, lang, size)` 가 리포지토리에서 id 를 읽어 READY 필터·셔플. `HomeService` 는 풀이면 캐시를 생략하고 그 메서드를 호출. `HomeMarketingPopularProperties`·`PropertiesConfig` 애너테이션·yml 키(base/test)·바인딩 단위 테스트 삭제.
- [X] T026 `HomeTestSeed.reset` 에 `DELETE FROM home_marketing_popular_food`(food 보다 먼저). `HomeMarketingPopularTest` 는 `seedPool(...)` 로 행을 INSERT — 풀 전부·12→10·풀 밖 제외·비READY/삭제/풀 행 DELETED/빈 풀/전부 제외·회원 위험도·언어·인기 외 payload 동일·1.0/1.1 집계·2.0 풀(14건). `./gradlew build` Green. spec/plan/research/data-model/contracts/quickstart·위키·PR 동기화.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 하나.
- **Foundational (Phase 2)**: 없음.
- **US1 (Phase 3)**: T001 완료 뒤. T002·T003 병렬(Red) → T004 → T005 → T006 → T007 → T008(Green).
- **US2 (Phase 4)**: US1 완료 뒤(검증 전용).
- **US3 (Phase 5)**: US1 완료 뒤. T011 → T012.
- **US4 (Phase 6)**: US1 완료 뒤. US3 과 독립이나 같은 테스트 파일을 만지므로 순차 권장.
- **Polish (Phase 7)**: 전 스토리 완료 뒤. T015·T016 병렬.

### Within Each User Story

- 테스트 먼저 쓰고 Red 확인(US2 는 구조 보장이라 즉시 Green 이 정상).
- 설정 홀더 → 서비스 → 컨트롤러/문서 → Swagger 그룹 순.

### Parallel Opportunities

- US1 테스트: T002 ‖ T003.
- Polish: T015 ‖ T016.

---

## Parallel Example: User Story 1

```bash
# Red 먼저 — 두 테스트 파일은 서로 독립
Task: "HomeMarketingPopularPropertiesTest.kt 작성 (T002)"
Task: "HomeMarketingPopularTest.kt 작성 (T003)"

# Green — 운영 코드는 의존 순서대로 순차
T004 Properties → T005 FoodService → T006 HomeService → T007 Controller/Api → T008 OpenApiConfig
```

---

## Implementation Strategy

### MVP First (US1 + US2)

1. Phase 1 Setup(설정 키).
2. US1 — 2.1 매핑과 임시 목록 노출. 여기까지가 배포 가능한 최소 단위다(기간·목록은 설정 기본값이 비어 있어 폴백 로직이 US3 전이라도 `activeFoodIds` 가 빈 목록을 돌려주고 `getHome` 으로 위임되므로 안전).
3. US2 — 1.0 불변 고정 테스트.

### Incremental Delivery

4. US3 — 경계·미설정·빈 목록 단위 고정.
5. US4 — READY 필터.
6. Polish — 로컬 실측·위키·PR.

---

## Notes

- 통합 테스트는 테스트 yml 고정 창 덕에 항상 기간 안이다. 기존 홈 테스트는 1.0 기본 헤더라 영향받지 않는다.
- 테스트 yml 의 `food-ids: 3,1,999,2` 는 전 시나리오 공통 — 999 는 시드하지 않아 "미존재 제외"가 모든 2.1 시나리오에서 함께 검증된다.
- `OpenApiSnapshotTest` 가 operationId 유일성·버전 그룹 노출을 가드하므로 T007 의 `operationId` 와 T008 의 그룹 빈을 빠뜨리면 거기서 잡힌다.
- 커밋 메시지는 한국어 Conventional Commits, Co-Authored-By 라인 포함.
