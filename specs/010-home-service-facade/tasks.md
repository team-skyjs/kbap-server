# Tasks: 홈 화면 조합 서비스 퍼사드화

**Input**: Design documents from `/specs/010-home-service-facade/`

**Prerequisites**: plan.md, spec.md, research.md(D1~D7), data-model.md, quickstart.md

**Tests**: 이 기능은 Red→Green→**Refactor** 의 Refactor 단계다(plan.md Constitution Check I, research D6). 새 동작이 없어 실패 선행 테스트를 만들지 않고, 기존 `HomeControllerTest`·`HomeGuestTest` 를 **무수정** 회귀 안전망으로 쓴다. 각 체크포인트마다 `./gradlew :api:test` 그린이 Green 판정이다(Kotest 는 `--tests` 필터를 무시하므로 항상 모듈 전체를 돈다).

**Organization**: 스펙의 세 스토리 중 US1(응답 동일)은 구현이 없는 **수용 불변식**이다 — 시작 시 기준선을 잡고(Phase 1) 매 체크포인트와 마지막(Phase 5)에서 판정한다. 구현은 US3(리뷰 많은 음식 이관 — 가장 작고 독립)과 US2(퍼사드 완성) 두 증분으로 나눈다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·선행 미완 없음 → 병렬 가능
- **[Story]**: US1·US2·US3

## Path Conventions

api 모듈 단일: 소스 `api/src/main/kotlin/com/kbap/api/`, 테스트 `api/src/test/kotlin/com/kbap/api/`.

---

## Phase 1: Setup — 회귀 기준선 (US1 시작점)

**Purpose**: 리팩토링 전 홈 테스트가 그린임을 확정해, 이후 실패가 리팩토링 탓임을 보장한다.

- [X] T001 [US1] `./gradlew :api:test` 를 실행해 BUILD SUCCESSFUL 을 확인하고, `HomeControllerTest`·`HomeGuestTest` 가 실행·통과됐음을 `api/build/test-results/test/` 의 XML 로 확인한다
- [X] T002 [US1] `git diff --stat develop -- api/src/test/kotlin/com/kbap/api/home/` 가 비어 있음을 확인한다 — 이 세 파일(`HomeControllerTest.kt`·`HomeGuestTest.kt`·`HomeTestSeed.kt`)은 이후 어떤 태스크에서도 열지 않는다

**Checkpoint**: 기준선 그린. 이제부터 테스트 파일은 읽기 전용.

---

## Phase 2: Foundational

해당 없음 — 새 패키지·설정·스키마가 없다. US3 부터 바로 시작한다.

---

## Phase 3: User Story 3 — 리뷰 많은 음식 조회는 음식 서비스가 소유한다 (Priority: P3, 가장 작은 독립 증분)

**Goal**: `ReviewService.getMostReviewedFoodIds` 를 `FoodService.getMostReviewedFoods` 로 이관하고 홈이 그것을 호출 하나로 쓰게 한다. 홈의 `ReviewService` 의존이 사라진다.

**Independent Test**: `HomeControllerTest` 의 "홈 — 리뷰 많은 음식" given 3건(내림차순·0건 제외 / 빈 배열 / 회원·비회원 동일 순서)이 무수정으로 통과하고, `grep ReviewService api/src/main/kotlin/com/kbap/api/home/HomeService.kt` 가 0건.

### Implementation for User Story 3

- [X] T003 [US3] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` 생성자에 `private val reviewRepository: ReviewJpaRepository`(`com.kbap.common.domain.review.ReviewJpaRepository`) 를 추가하고, `@Transactional(readOnly = true) fun getMostReviewedFoods(memberId: Long?, lang: LanguageCode, size: Int): List<FoodSummaryView>` 를 추가한다 — 본문은 `summaryViews(loadInGivenOrder(reviewRepository.findMostReviewedFoodIds(PageRequest.of(0, size))), lang, memberId)` 한 줄(research D3·D4). 주석 금지
- [X] T004 [US3] `api/src/main/kotlin/com/kbap/api/home/HomeService.kt` 의 `mostReviewedFoods = reviewService.getMostReviewedFoodIds(...).let { ... }` 블록을 `mostReviewedFoods = foodService.getMostReviewedFoods(member?.id, lang, MOST_REVIEWED_SIZE)` 로 교체하고, 생성자에서 `reviewService` 와 `com.kbap.api.review.ReviewService` import 를 제거한다
- [X] T005 [US3] `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt` 에서 `getMostReviewedFoodIds(size: Int)` 메서드를 삭제한다(호출자 없음 — research 조사 표). 미사용 import 가 생기면 정리한다
- [X] T006 [US3] `./gradlew :api:test` 그린 확인 후 커밋 — 메시지 예: `refactor(home): 리뷰 많은 음식 조회를 FoodService.getMostReviewedFoods 로 이관`

**Checkpoint**: 홈이 `ReviewService` 를 모른다. 리뷰 많은 음식 시나리오 3건 통과.

---

## Phase 4: User Story 2 — 홈 조합 서비스는 섹션별 서비스 호출만 나열한다 (Priority: P2)

**Goal**: 기피 성분·인기 음식·최근 스캔 세 섹션도 서비스 호출 하나씩으로 바꾸고, 홈에서 `IngredientJpaRepository`·`ScanService` 의존과 조립 세부(재조회·순서 유지·카드 변환)를 없앤다. 최종 형태는 research D1 의 코드 블록 그대로.

**Independent Test**: `HomeControllerTest`·`HomeGuestTest` 전 시나리오 무수정 통과 + quickstart 의 SC-002·SC-003 grep 이 0건.

### Implementation for User Story 2

- [X] T007 [P] [US2] `api/src/main/kotlin/com/kbap/api/ingredient/AvoidedIngredientView.kt` 를 신설한다 — `data class AvoidedIngredientView(val code: String, val name: String)`(구 `home/AvoidedSubstanceView` 와 필드 동일, research D5)
- [X] T008 [P] [US2] `api/src/main/kotlin/com/kbap/api/food/FoodService.kt` 에 두 메서드를 추가한다(각 `@Transactional(readOnly = true)`): `getPopularFoods(memberId: Long?, lang: LanguageCode, size: Int): List<FoodSummaryView>` = `summaryViews(getRandomReadyFoods(size), lang, memberId)`; `getRecentScannedFoods(memberId: Long, lang: LanguageCode, size: Int): List<FoodSummaryView>` = `summaryViews(loadInGivenOrder(scanHistoryRepository.findRecentReadyFoodIds(memberId, size)), lang, memberId)`. 같은 파일에서 `getRandomReadyFoods` 를 `private` 으로 내리고 그 `@Transactional` 을 제거한다(private 프록시 무의미). T003 과 같은 파일이므로 T003 이후에 수행
- [X] T009 [US2] `api/src/main/kotlin/com/kbap/api/home/HomeResult.kt` 의 `avoidedSubstances: List<AvoidedSubstanceView>` 를 `List<AvoidedIngredientView>` 로 바꾸고 `com.kbap.api.ingredient.AvoidedIngredientView` 를 import 한다; `api/src/main/kotlin/com/kbap/api/home/HomeResponse.kt` 의 `AvoidedSubstanceResponse.from(view: AvoidedSubstanceView)` 파라미터 타입을 `AvoidedIngredientView` 로 바꾸고 import 를 추가한다(응답 DTO 이름·필드는 불변). 그런 뒤 `api/src/main/kotlin/com/kbap/api/home/AvoidedSubstanceView.kt` 를 삭제한다(T007 이후)
- [X] T010 [US2] `api/src/main/kotlin/com/kbap/api/ingredient/IngredientService.kt` 생성자에 `private val memberService: MemberService`(`com.kbap.api.member.MemberService`) 를 추가하고, `@Transactional(readOnly = true) fun getAvoidedIngredients(memberId: Long?, lang: LanguageCode): List<AvoidedIngredientView>` 를 추가한다 — `val chosen = memberService.getAvoidance(memberId).chosen; if (chosen.isEmpty()) return emptyList(); return ingredientRepository.findByCodeIn(chosen).map { AvoidedIngredientView(code = it.code.name, name = it.displayName(lang)) }`(research D2, T007 이후)
- [X] T011 [US2] `api/src/main/kotlin/com/kbap/api/home/HomeService.kt` 를 research D1 의 최종 형태로 교체한다 — 생성자 `(memberService, ingredientService, foodService)`; `getHome` 본문은 `val activeMemberId = memberId?.let { memberService.getMemberOrNull(it)?.id }` 한 줄 + `HomeResult(avoidedSubstances = ingredientService.getAvoidedIngredients(activeMemberId, lang), popularFoods = foodService.getPopularFoods(activeMemberId, lang, POPULAR_SIZE), mostReviewedFoods = foodService.getMostReviewedFoods(activeMemberId, lang, MOST_REVIEWED_SIZE), recentScans = activeMemberId?.let { foodService.getRecentScannedFoods(it, lang, RECENT_SCAN_SIZE) }.orEmpty())`. `IngredientJpaRepository`·`ScanService`·`FoodSummaryView`·`AvoidedSubstanceView` import 를 제거하고 컴패니언 상수 3개는 유지한다(T008·T009·T010 이후)
- [X] T012 [US2] `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt` 에서 `getRecentReadyFoodIds(memberId: Long, limit: Int)` 를 삭제한다(호출자 없음). 미사용 import 정리(T011 이후)
- [X] T013 [US2] `./gradlew :api:test` 그린 확인 후 커밋 — 메시지 예: `refactor(home): HomeService 를 섹션별 서비스 호출만 남긴 퍼사드로 정리`

**Checkpoint**: `HomeService` 의존 3개, 본문 = 회원 해석 1줄 + 서비스 호출 4개. 전 홈 시나리오 통과.

---

## Phase 5: Polish — US1 최종 판정 및 정리

**Purpose**: 스펙 SC-001~005 를 quickstart 명령으로 전부 확인한다.

- [X] T014 [US1] `specs/010-home-service-facade/quickstart.md` 의 명령을 위에서 아래로 전부 실행한다 — 홈 테스트 3파일 diff 없음(SC-001), `HomeService.kt` 에 `Repository|ReviewService|ScanService` 0건(SC-002), `associateBy|mapNotNull|FoodSummaryView.from|AvoidedIngredientView(` 0건(SC-003), `getMostReviewedFoodIds|getRecentReadyFoodIds|AvoidedSubstanceView` 가 `api/src/main` 에 0건, `HomeController.kt` diff 없음·`HomeResponse.kt` diff 는 import·파라미터 타입뿐(SC-005), `./gradlew :api:test` 그린(SC-004)
- [X] T015 [P] 변경 파일 전체에서 Kotlin 주석(`//`·`/* */`·KDoc)이 새로 추가되지 않았는지 `git diff develop -- api/src/main | grep '^+.*\(//\|/\*\)'` 로 확인한다(CLAUDE.md 주석 금지)
- [X] T016 `open-draft-pr-to-develop` 스킬로 base=develop draft PR 을 연다 — 본문에 spec/plan 경로와 "동작 변경 없음 · 홈 테스트 무수정 통과" 를 적는다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1(기준선)** → 선행 없음. 반드시 먼저.
- **Phase 3(US3)** → Phase 1 이후. 단독 머지 가능한 증분.
- **Phase 4(US2)** → Phase 3 이후(`FoodService.kt` 를 T003 과 T008 이 순차 수정, `HomeService.kt` 를 T004 와 T011 이 순차 수정).
- **Phase 5(Polish)** → Phase 4 이후.

### User Story Dependencies

- **US1(응답 동일)**: 구현 없음. T001·T002 로 시작, T014 로 종결. 매 체크포인트의 `./gradlew :api:test` 가 중간 판정.
- **US3(리뷰 이관)**: US1 기준선만 선행. 다른 스토리와 독립 — 여기까지만 머지해도 가치가 있다.
- **US2(퍼사드)**: US3 완료를 전제한다(같은 파일 순차 수정 + 최종 `HomeService` 형태가 US3 결과를 포함).

### Parallel Opportunities

- T007(뷰 신설)과 T008(FoodService 메서드 2개)은 다른 파일이라 병렬 가능.
- T015 는 T014 와 병렬 가능(둘 다 읽기 전용 검사).
- 그 외는 같은 파일을 순차 수정하므로 직렬.

---

## Parallel Example: User Story 2

```bash
# T007 과 T008 을 함께 시작 (다른 파일):
Task: "api/src/main/kotlin/com/kbap/api/ingredient/AvoidedIngredientView.kt 신설"
Task: "api/src/main/kotlin/com/kbap/api/food/FoodService.kt 에 getPopularFoods·getRecentScannedFoods 추가, getRandomReadyFoods private"

# 둘이 끝나면 T009 → T010 → T011 → T012 → T013 순차
```

---

## Implementation Strategy

### 최소 증분 (US3 만)

1. Phase 1 기준선 그린.
2. Phase 3 — `FoodService.getMostReviewedFoods` 추가 → 홈 교체 → `ReviewService` 메서드 삭제 → 테스트 그린 → 커밋.
3. 여기서 멈춰도 홈의 `ReviewService` 의존 제거라는 독립 가치가 있다.

### 전체 (US3 + US2)

1. Phase 3 완료 후 Phase 4 — 뷰 이동·`IngredientService` 메서드·`FoodService` 메서드 2개·`HomeService` 최종 형태·`ScanService` 메서드 삭제 → 테스트 그린 → 커밋.
2. Phase 5 — quickstart 전수 검증 → draft PR.

### 판정 규칙

- 어느 시점이든 `HomeControllerTest`·`HomeGuestTest` 가 실패하면 **테스트를 고치지 않고 구현을 되돌린다**(spec FR-007). 실패 원인은 research D4(READY 필터·순서 보존·활성 회원 해석) 중 하나일 가능성이 높다.

---

## Notes

- 태스크 총 16개: US1 3개(T001·T002·T014), US3 4개(T003~T006), US2 7개(T007~T013), 공통 2개(T015·T016).
- `HomeTestSeed.kt` 를 포함해 테스트 파일은 어떤 태스크도 수정하지 않는다.
- 커밋은 Phase 3·Phase 4 끝(T006·T013)에서 각각 한 번, 논리 단위 2개.
