# Tasks: 홈 인기 음식 목록 캐시

**Input**: Design documents from `specs/kb-725-popular-food-cache/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리의 테스트를 구현보다 먼저 쓰고 Red 를 확인한다. 테스트는 전부 Kotest `BehaviorSpec`, `given/when/then` 한국어. Kotest 는 Gradle `--tests` 필터를 무시하므로 실행은 항상 `./gradlew :api:test` 모듈 전체다.

**Organization**: 스토리별 묶음. 변경 파일이 적어(운영 2·테스트 4·빌드 2) 병렬 여지는 Setup 두 건뿐이다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·선행 의존 없음 → 병렬 가능
- **[Story]**: US1(캐시 재사용·TTL), US2(사용자별 값 비캐시), US3(동시 미스 집계 1회)

## Path Conventions

모듈러 모놀리스 `:api` — 운영 `api/src/main/kotlin/com/kbap/api/food/`, 테스트 `api/src/test/kotlin/com/kbap/api/{food,home}/`.

---

## Phase 1: Setup (Caffeine 의존성)

**Purpose**: api 모듈에 Caffeine 을 붙인다. 버전은 Spring Boot BOM 관리(카탈로그에 버전 없음).

- [ ] T001 [P] `gradle/libs.versions.toml` 의 `[libraries]` 에 `caffeine = { module = "com.github.ben-manes.caffeine:caffeine" }` 를 추가한다(Spring Boot BOM 관리 주석 한 줄 포함 — 기존 항목 주석 관례 따름)
- [ ] T002 [P] `api/build.gradle.kts` 의 dependencies 에 `"implementation"(libs.caffeine)` 를 추가하고 `./gradlew :api:compileKotlin` 으로 좌표가 해석되는지 확인한다

---

## Phase 2: Foundational

해당 없음 — 스키마·공통 인프라 변경이 없다. 캐시 클래스 자체가 US1 의 산출물이다.

---

## Phase 3: User Story 1 - 홈을 여는 사람이 많아져도 인기 음식 때문에 홈이 느려지지 않는다 (Priority: P1) 🎯 MVP

**Goal**: 인기 음식 id 목록을 Caffeine 에 2일간 보관해 집계를 TTL 당 1회로 줄이고, `FoodService.getPopularFoods` 가 캐시된 id 로 음식을 다시 읽어 기존 응답을 그대로 만든다.

**Independent Test**: 가짜 Ticker·카운팅 로더로 캐시 클래스를 직접 구성해 두 번째 호출이 로더를 부르지 않고, Ticker 를 2일 전진시키면 다시 부르는 것을 확인한다. 기존 홈 통합 테스트가 검증 본문 수정 없이 통과한다.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [ ] T003 [US1] `api/src/test/kotlin/com/kbap/api/food/PopularFoodIdCacheTest.kt` 를 Spring 없이 `BehaviorSpec` 으로 작성한다. 픽스처: `AtomicLong` 기반 가짜 `Ticker`(`nanos` 를 더해 전진), 호출 횟수를 세는 로더 `(Int) -> List<Long>`. 시나리오 — given("인기 음식 id 캐시"): (1) when("같은 크기로 두 번 조회하면") then("로더는 1회만 호출되고 두 결과가 같다"), (2) when("TTL(2일)이 지난 뒤 조회하면") then("로더를 다시 호출해 새 목록을 돌려준다"), (3) when("로더가 예외를 던지면") then("예외가 전파되고 다음 조회가 로더를 다시 호출한다"), (4) when("로더가 빈 목록을 돌려주면") then("빈 목록이 캐시돼 두 번째 조회는 로더를 부르지 않는다"). 생성자는 `PopularFoodIdCache(ticker, loader)`, 조회는 `getPopularFoodIds(size)`. `./gradlew :api:test` 로 **컴파일 실패(Red)** 를 확인한다.

### Implementation for User Story 1

- [ ] T004 [US1] `api/src/main/kotlin/com/kbap/api/food/PopularFoodIdCache.kt` 를 생성한다. `@Component`, 주 생성자 `internal constructor(ticker: Ticker, private val loader: (Int) -> List<Long>)`, `@Autowired` 보조 생성자 `(foodRepository: FoodJpaRepository)` 가 `Ticker.systemTicker()` 와 `{ size -> foodRepository.findPopular(LocalDateTime.now().minusDays(WINDOW_DAYS), size).map { it.id } }` 를 넘긴다. 필드 `cache: Cache<Int, List<Long>> = Caffeine.newBuilder().expireAfterWrite(TTL).ticker(ticker).build()`. `getPopularFoodIds(size)` 는 `cache.getIfPresent(size) ?: loader(size).also { cache.put(size, it) }`(락은 US3 에서). `invalidateAll()` 공개. companion: `WINDOW_DAYS = 30L`, `TTL: Duration = Duration.ofDays(2)`. 주석 금지. `./gradlew :api:test` 로 T003 시나리오 Green 확인.
- [ ] T005 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` 를 수정한다 — 생성자에 `private val popularFoodIdCache: PopularFoodIdCache` 주입, `getPopularFoods` 본문을 `summaryViews(loadInGivenOrder(popularFoodIdCache.getPopularFoodIds(size)), lang, memberId)` 로 교체(`@Transactional(readOnly = true)` 유지), companion 의 `POPULAR_WINDOW_DAYS` 삭제(참조처 없음 — grep 으로 재확인).
- [ ] T006 [US1] `api/src/test/kotlin/com/kbap/api/home/HomeControllerTest.kt` 와 `api/src/test/kotlin/com/kbap/api/home/HomeGuestTest.kt` 에 `@Autowired private lateinit var popularFoodIdCache: PopularFoodIdCache` 를 추가하고 `beforeContainer` 의 `HomeTestSeed.reset(dataSource)` 다음 줄에 `popularFoodIdCache.invalidateAll()` 을 넣는다. 검증 본문(given/when/then 내부)은 한 글자도 바꾸지 않는다. `./gradlew :api:test` 전체 Green 확인(공유 컨텍스트에서 캐시가 테스트 간 새지 않는지가 핵심).

**Checkpoint**: 집계는 인스턴스당 TTL 당 1회. 홈 응답·기존 테스트 불변. 커밋: `feat(home): 인기 음식 id 목록을 Caffeine 캐시로 2일간 재사용한다`.

---

## Phase 4: User Story 2 - 사용자마다 다른 정보는 캐시와 무관하게 정확하다 (Priority: P1)

**Goal**: 캐시 히트에서도 기피 성분 안전 여부·언어별 이름이 요청 사용자 기준으로 계산되고, 캐시 기간 중 삭제된 음식은 노출되지 않음을 통합 테스트로 고정한다(FR-005·FR-008·FR-010).

**Independent Test**: EGG 기피 회원 A 가 홈을 열어 캐시를 채운 뒤 기피 없는 회원 B 가 열면 인기 목록은 같고 안전 여부만 다르다. 캐시된 목록의 음식 하나를 소프트 삭제하면 다음 홈에서 빠진다.

### Tests for User Story 2 (REQUIRED — Test-First) ⚠️

- [ ] T007 [US2] `api/src/test/kotlin/com/kbap/api/home/HomePopularCacheTest.kt` 를 `@IntegrationTest` + `SpringExtension` 으로 작성한다. 주입: `MockMvc`·`DataSource`·`TokenIssuer`·`PopularFoodIdCache`. `beforeContainer` 에서 `HomeTestSeed.reset` + `popularFoodIdCache.invalidateAll()`. 헬퍼는 `HomeControllerTest` 의 `token/home/payload` 와 같은 형태(`/api/home?lang=…`, `X-API-Version` 은 기존 테스트 관례대로). 시나리오 — given("인기 음식 목록이 캐시된 뒤"): (1) `seedReadyFoods(2)` + `seedFoodSubstance(1, "EGG", 100)` + `seedMember(11, ["EGG"])` + `seedMember(12, [])`: when("기피 성분이 다른 두 회원이 연속으로 조회하면") then("인기 목록 id 는 같고 회원 11 의 음식 1 은 DANGER, 회원 12 의 음식 1 은 DANGER 가 아니다"); (2) `seedReadyFoods(3)`, 비회원 조회로 캐시 채운 뒤 `UPDATE food SET status = 'DELETED' WHERE id = 2`: when("캐시된 목록의 음식이 삭제되면") then("다음 홈 응답의 popularFoods 에 2 가 없고 1·3 은 남는다"); (3) `seedReadyFoods(1)`, `lang=ko` 로 캐시 채운 뒤 `lang=ja`: when("다른 언어로 조회하면") then("같은 음식이 `メニュー1` 로 나온다"). `./gradlew :api:test` 실행 — id 캐시 설계상 바로 Green 일 수 있다. 그 경우 Red 확인 대신 **(2) 가 의미 있는 검증인지**를 `invalidateAll()` 호출을 잠시 빼고 돌려 캐시 히트 경로를 실제로 타는지 확인한 뒤 복원한다.

### Implementation for User Story 2

- [ ] T008 [US2] T007 이 Green 이 아니면 `FoodService.getPopularFoods`(`api/src/main/kotlin/com/kbap/api/food/FoodService.kt`) 의 `loadInGivenOrder` 경로를 점검해 고친다(기대: `findByIdIn` 의 `@SQLRestriction` 이 삭제 음식을 거르고 `summaryViews` 가 요청마다 `getAvoidance(memberId)` 를 평가). 추가 코드가 필요 없으면 이 작업은 "변경 없음"으로 닫는다.

**Checkpoint**: 사용자별 값·언어·삭제 제외가 테스트로 고정. 커밋: `test(home): 캐시 히트에서도 사용자별 안전 여부·언어·삭제 제외가 유지됨을 고정한다`.

---

## Phase 5: User Story 3 - 유효 기간이 끝나는 순간 몰린 요청도 집계는 한 번만 한다 (Priority: P2)

**Goal**: 빈 캐시·만료 캐시에 동시에 들어온 요청 N 개 중 하나만 로더를 실행하고 나머지는 락 해제 후 캐시 값을 읽는다(FR-004).

**Independent Test**: 로더가 200ms 자고 호출 횟수를 세는 상태에서 50개 스레드가 동시에 `getPopularFoodIds` 를 호출하면 로더 호출 1회, 결과 50개 모두 동일.

### Tests for User Story 3 (REQUIRED — Test-First) ⚠️

- [ ] T009 [US3] `api/src/test/kotlin/com/kbap/api/food/PopularFoodIdCacheTest.kt` 에 given("빈 캐시에 동시 요청") when("50개 스레드가 동시에 조회하면") then("로더는 1회만 호출되고 모든 결과가 같다") 를 추가한다. 구성: 로더는 `Thread.sleep(200)` 후 카운트 증가·`listOf(1L, 2L)` 반환, `Executors.newVirtualThreadPerTaskExecutor()`(또는 50 고정 풀) + `CountDownLatch(1)` 로 출발선을 맞추고 `Future.get()` 으로 수집, 단언 `calls.get() shouldBe 1`·`results.toSet().size shouldBe 1`. `./gradlew :api:test` 로 **Red**(락 없는 T004 구현은 여러 번 로드) 확인.

### Implementation for User Story 3

- [ ] T010 [US3] `api/src/main/kotlin/com/kbap/api/food/PopularFoodIdCache.kt` 에 `private val refresh = ReentrantLock()` 을 두고 `getPopularFoodIds` 를 더블 체크로 바꾼다: `cache.getIfPresent(size)?.let { return it }; return refresh.withLock { cache.getIfPresent(size) ?: loader(size).also { cache.put(size, it) } }`. `synchronized` 금지(가상 스레드 핀 고정). `./gradlew :api:test` Green 확인.

**Checkpoint**: 스탬피드 방어 완료. 커밋: `feat(home): 인기 음식 캐시 미스를 ReentrantLock 으로 묶어 동시 집계를 1회로 막는다`.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T011 `./gradlew build` 전체(ArchUnit `arch` 태그 포함)를 돌려 `ModuleBoundaryTest`·`RepositoryLikeEscapeTest` 등 아키텍처 스펙이 통과하는지 확인한다. 실패 시 원인은 신규 import 의 패키지 위치일 가능성이 높다 — `com.kbap.api.food` 에 두었는지 재확인.
- [ ] T012 `specs/kb-725-popular-food-cache/quickstart.md` 2절대로 로컬 bootRun 후 `GET /api/home` 두 번 호출해 SQL 로그에 집계(`food_view_log … group by`)가 첫 호출에만 찍히는지 확인한다(워크트리 bootRun 은 메모리의 `.env` source·`DB_USERNAME=root` 레시피 사용). 확인 결과를 PR 본문에 한 줄 남긴다.
- [ ] T013 `open-draft-pr-to-develop` 스킬로 develop 대상 PR 을 열고(제목 `feat(home): 홈 인기 음식 조회를 Caffeine 캐시로 — DB 집계 1회/TTL, 동시 갱신 방지`, 본문에 Jira KB-725·설계 요지·id 캐시 선택 근거·TTL 2일), Jira DoD 중 코드 항목 3개를 체크한다. dev 부하 테스트 재실행·Notion 기록(DoD 4번째)은 배포 후 사용자가 수행한다고 PR 본문에 명시한다.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001·T002 병렬. 둘 다 끝나야 T004 컴파일 가능(T003 테스트 작성 자체는 먼저 해도 된다 — 어차피 Red).
- **Foundational (Phase 2)**: 없음.
- **US1 (Phase 3)**: T003 → T004 → T005 → T006 순차(같은 파일 또는 직접 의존).
- **US2 (Phase 4)**: T006 완료 후. T007 → T008.
- **US3 (Phase 5)**: T004 완료 후면 가능하나, T009 가 T003 과 같은 파일이므로 US1 뒤에 한다. T009 → T010.
- **Polish (Phase 6)**: 전 스토리 완료 후 T011 → T012 → T013.

### User Story Dependencies

- **US1**: 독립. MVP.
- **US2**: US1 의 `PopularFoodIdCache` 빈과 `invalidateAll` 에 의존(테스트 격리). 운영 코드 추가는 없을 가능성이 높다.
- **US3**: US1 의 캐시 클래스를 수정. 테스트 파일도 US1 과 공유.

### Parallel Opportunities

- T001 ‖ T002 만 병렬. 이후는 파일이 겹쳐 순차.

---

## Parallel Example: Setup

```bash
# 동시에 진행 가능:
Task: "gradle/libs.versions.toml 에 caffeine 좌표 추가"
Task: "api/build.gradle.kts 에 implementation(libs.caffeine) 추가"
```

---

## Implementation Strategy

### MVP First (US1)

1. Phase 1 → Phase 3(T003~T006) → 전체 테스트 Green → 커밋.
2. 이 시점에 배포해도 집계는 TTL 당 1회로 떨어진다(스탬피드 방어만 없음).

### Incremental Delivery

1. US2 로 사용자별 값·삭제 제외를 테스트로 고정 → 커밋.
2. US3 로 락 추가 → 커밋.
3. Polish: 전체 build·로컬 확인·PR.

### 변경 범위 요약 (plan.md 와 일치)

| 종류 | 파일 |
|------|------|
| 빌드 | `gradle/libs.versions.toml`, `api/build.gradle.kts` |
| 운영 신규 | `api/src/main/kotlin/com/kbap/api/food/PopularFoodIdCache.kt` |
| 운영 수정 | `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` |
| 테스트 신규 | `api/src/test/kotlin/com/kbap/api/food/PopularFoodIdCacheTest.kt`, `api/src/test/kotlin/com/kbap/api/home/HomePopularCacheTest.kt` |
| 테스트 수정 | `api/src/test/kotlin/com/kbap/api/home/HomeControllerTest.kt`, `api/src/test/kotlin/com/kbap/api/home/HomeGuestTest.kt` |

---

## Notes

- 모든 테스트는 `BehaviorSpec` + 한국어 given/when/then. 단위 테스트는 Spring 을 띄우지 않는다.
- 통합 테스트 헤더는 `@IntegrationTest` 하나만. `@SpringBootTest(...)`·`@Import` 직접 사용 금지(KB-392).
- Kotlin 운영 코드에 주석을 쓰지 않는다. 설계 근거는 research.md·커밋 메시지에 있다.
- TTL·윈도우는 companion 상수. 프로퍼티화하지 않는다.
- 리뷰 인기(`findMostReviewed`) 캐시는 범위 밖 — 손대지 않는다.
