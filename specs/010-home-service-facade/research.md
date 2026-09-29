# Research: 홈 조합 서비스 퍼사드화 — 설계 결정

Technical Context 에 NEEDS CLARIFICATION 은 없다. 사용자 요청("새로 추가할 서비스 클래스명과 메서드명 추천")에 답하는 결정 7건이다. 코드 조사 결과가 각 결정의 근거다.

## 조사 결과 요약

| 사실 | 영향 |
|------|------|
| `com.kbap.api.ingredient.IngredientService` 가 **이미 존재**한다(성분 목록·식이 매핑 조회, `IngredientJpaRepository` 보유). | 기피 성분 섹션을 위한 "새 서비스 클래스"는 필요 없다 — 메서드 하나만 추가한다. |
| `ReviewService` 는 `FoodService` 를 의존한다. `ScanService` 도 `FoodService` 를 의존한다. | `FoodService` 가 `ReviewService`·`ScanService` 를 주입받으면 순환이다. 리포지토리를 직접 쓴다(헌법 IV — 단순 영속 접근은 리포지토리 직접). |
| `FoodService` 는 이미 `ScanHistoryJpaRepository` 를 주입받아 스캔 음식 검색·페이지(`getScannedFoodPage`)를 소유한다. | 최근 스캔 섹션도 `FoodService` 가 소유하는 것이 기존 선례와 일치한다. |
| `FoodService` 에 `loadInGivenOrder(ids)`(id 순서 유지 재조회)·`summaryViews(rows, lang, memberId)`(기피 성분 반영 카드 변환) private 헬퍼가 이미 있다. | 홈에 흩어진 "id 재조회 → 순서 유지 → 카드 변환" 은 전부 이 두 헬퍼로 대체된다. |
| `findMostReviewedFoodIds`·`findRecentReadyFoodIds`·`findRandomReadyIds` 세 id 쿼리는 모두 **READY 조건을 쿼리 안에서** 건다. `getHome` 은 `@Transactional(readOnly = true)` 한 스냅샷이다. | 홈의 후행 `.filter { isReady() }` 는 중복 방어라 `loadInGivenOrder` 로 바꿔도 결과가 같다. |
| `MemberService` 는 `com.kbap.api` 하위를 전혀 의존하지 않는다. | `IngredientService` → `MemberService` 의존을 추가해도 순환이 없다. |
| 인증 필터는 회원 활성 여부를 검사하지 않는다. `getHome` 의 `getMemberOrNull` 은 탈퇴 회원 토큰을 비회원으로 강등하는 **살아 있는 동작**이다. | 활성 회원 해석은 홈 조합 서비스에 남긴다. |
| `getMostReviewedFoodIds`(ReviewService)·`getRecentReadyFoodIds`(ScanService)·`getRandomReadyFoods`(FoodService) 의 호출자는 홈 조합 서비스뿐이다. | 앞 둘은 삭제, 셋째는 private 으로 내린다. |
| `HomeControllerTest`·`HomeGuestTest` 가 네 섹션(순서·빈 배열·중복 제거·언어·위험도)을 모두 검증한다. | 새 메서드에 별도 테스트를 만들지 않아도 FR-008 을 충족한다. |

## D1. 홈 조합 서비스의 최종 형태 — 의존 3개, 섹션당 호출 1개

- **Decision**: `HomeService` 는 `MemberService`·`IngredientService`·`FoodService` 세 서비스만 의존한다. `getHome` 본문은 (1) 활성 회원 id 해석 한 줄, (2) 네 섹션을 서비스 호출 하나씩으로 채운 `HomeResult` 생성으로 끝난다. 섹션 크기 상수(5·10·10)는 홈 정책이므로 `HomeService` 컴패니언에 남기고 인자로 넘긴다.

  ```kotlin
  @Service
  class HomeService(
      private val memberService: MemberService,
      private val ingredientService: IngredientService,
      private val foodService: FoodService,
  ) {
      @Transactional(readOnly = true)
      fun getHome(memberId: Long?, lang: LanguageCode): HomeResult {
          val activeMemberId = memberId?.let { memberService.getMemberOrNull(it)?.id }
          return HomeResult(
              avoidedSubstances = ingredientService.getAvoidedIngredients(activeMemberId, lang),
              popularFoods = foodService.getPopularFoods(activeMemberId, lang, POPULAR_SIZE),
              mostReviewedFoods = foodService.getMostReviewedFoods(activeMemberId, lang, MOST_REVIEWED_SIZE),
              recentScans = activeMemberId
                  ?.let { foodService.getRecentScannedFoods(it, lang, RECENT_SCAN_SIZE) }
                  .orEmpty(),
          )
      }
  }
  ```

- **Rationale**: 스펙 FR-002·FR-003·SC-002·SC-003 을 그대로 코드로 옮긴 형태다. 비회원 분기(`?.let … .orEmpty()`)는 호출부가 소유한다 — `FoodService` 의 기존 스캔 메서드(`getScannedFoodPage(memberId: Long, …)`)가 non-null 회원 id 를 받는 계약과 맞춘다. `ScanService`·`ReviewService`·`IngredientJpaRepository` 의존이 사라진다.
- **Alternatives considered**: (a) 회원 해석까지 각 섹션 서비스에 내리기 — 탈퇴 회원의 최근 스캔이 노출되는 동작 변경. 기각. (b) 기피 성분을 홈에서 한 번 조회해 각 섹션에 `Set<String>` 으로 넘기기 — 회원 조회 횟수는 줄지만 `FoodService` 의 기존 `(memberId, lang)` 계약과 어긋나고 홈이 다시 조립 세부를 갖는다. 기각(비용은 스펙 Assumptions 에서 감수).

## D2. 새 서비스 클래스는 만들지 않는다 — `IngredientService` 에 메서드 추가

- **Decision**: 기피 성분 섹션은 기존 `com.kbap.api.ingredient.IngredientService` 에 **`getAvoidedIngredients(memberId: Long?, lang: LanguageCode): List<AvoidedIngredientView>`** 를 추가해 얻는다. `MemberService` 를 새로 주입받아 `getAvoidance(memberId).chosen` 을 얻고, 비어 있으면 빈 목록, 아니면 `findByCodeIn` 후 표시명으로 변환한다.
- **Rationale**: 사용자는 "서비스 클래스를 새롭게 추가"를 전제했지만, 성분 도메인의 api 서비스가 이미 있다. 같은 도메인에 두 번째 서비스를 만들면 "어느 쪽에 넣나"가 영구 질문이 된다(CLAUDE.md — 파일 수 적은 기능에 하위 분할 금지, 위임 전용 창구 금지). 메서드명은 서비스 네이밍 규칙의 목록 조회 `get~s` + 유비쿼터스 언어(`avoided ingredients` — KB-213 이후 "substance" 는 폐기 용어)를 따른다.
- **Alternatives considered**: (a) 신규 `AvoidedIngredientService` — 메서드 하나짜리 클래스. 기각. (b) `MemberService.getAvoidedIngredients` — 회원 서비스가 성분 리포지토리·표시명 변환을 알게 돼 방향이 뒤집힌다. 기각. (c) 사용자가 그래도 새 클래스를 원하면 `com.kbap.api.ingredient.AvoidedIngredientService` 로 만들되 `IngredientService` 와 리포지토리를 공유한다 — 플랜 보고에서 선택지로 제시.

## D3. `FoodService` 가 세 음식 섹션을 소유한다 — 메서드 3개

- **Decision**: `FoodService` 에 카드 목록(`List<FoodSummaryView>`)을 돌려주는 public 메서드 3개를 추가한다. 인자 순서는 기존 `getScannedFoodPage(memberId, lang, cursor)` 를 따라 `(memberId, lang, size)`.

  | 메서드 | 본문(요지) | 대체하는 기존 코드 |
  |--------|-----------|-------------------|
  | `getPopularFoods(memberId: Long?, lang, size): List<FoodSummaryView>` | `summaryViews(getRandomReadyFoods(size), lang, memberId)` | 홈의 `getRandomReadyFoods(...).map { FoodSummaryView.from(...) }` |
  | `getMostReviewedFoods(memberId: Long?, lang, size): List<FoodSummaryView>` | `summaryViews(loadInGivenOrder(reviewRepository.findMostReviewedFoodIds(PageRequest.of(0, size))), lang, memberId)` | `ReviewService.getMostReviewedFoodIds` + 홈의 재조회·순서 유지·변환 |
  | `getRecentScannedFoods(memberId: Long, lang, size): List<FoodSummaryView>` | `summaryViews(loadInGivenOrder(scanHistoryRepository.findRecentReadyFoodIds(memberId, size)), lang, memberId)` | `ScanService.getRecentReadyFoodIds` + 홈의 재조회·순서 유지·변환 |

  세 메서드 모두 `@Transactional(readOnly = true)`. `FoodService` 는 **`ReviewJpaRepository` 를 새로 주입**받는다. `getRandomReadyFoods` 는 호출자가 사라지므로 private 으로 내린다. `ReviewService.getMostReviewedFoodIds`·`ScanService.getRecentReadyFoodIds` 는 삭제한다.

- **Rationale**: 사용자 지시("reviewService 역할은 foodService 로 이관, 리뷰 많은 음식 조회 메서드로 지정")의 직접 반영. 결과가 음식 카드 목록이므로 음식 서비스 소유가 맞고, 리뷰·스캔 서비스는 `FoodService` 를 의존하므로 역방향 주입은 순환이다 — 리포지토리 직접 주입은 헌법 IV 가 허용하는 단순 영속 접근이다. 최근 스캔도 `FoodService` 에 두는 이유는 스캔 음식 검색·페이지가 이미 거기 있기 때문(선례 일치)이며, 홈의 `ScanService` 의존이 함께 사라진다. `getMostReviewedFoods` 라는 이름은 "리뷰가 많은 음식" 을 목록 조회 규칙(`get~s`)으로 드러낸다.
- **Alternatives considered**: (a) `ScanService.getRecentScannedFoods` — 카드 변환에 `FoodService` 의 private 헬퍼가 필요해 public 으로 열어야 한다(테스트·타 서비스용 가시성 확대 금지 원칙과 같은 냄새). 기각. (b) `List<Food>` 를 돌려주고 홈이 변환 — 홈에 카드 변환이 남아 FR-003 위반. 기각. (c) `size` 를 `FoodService` 상수로 — 섹션 크기는 홈 정책이라 홈에 남긴다. 기각.

## D4. 동작 동일성 — 후행 READY 필터 제거의 안전성

- **Decision**: 홈이 하던 `getReadyFoodsByIds`(findByIdIn → id 정렬 → `isReady` 필터) 대신 `loadInGivenOrder`(findByIdIn → id 순서 유지) 를 쓴다.
- **Rationale**: 세 id 쿼리가 이미 READY 를 조건으로 걸고, `getHome` 이 `readOnly` 트랜잭션 하나(MySQL REPEATABLE READ 일관 스냅샷)라 id 조회와 재조회 사이에 상태가 바뀔 수 없다. 소프트 삭제는 `BaseEntity` 의 `@SQLRestriction` 이 양쪽에 동일 적용된다. 따라서 결과 집합·순서가 같다. `getReadyFoodsByIds` 는 북마크·커뮤니티가 계속 쓰므로 그대로 둔다.
- **Alternatives considered**: `loadInGivenOrder` 뒤에 `.filter { isReady() }` 를 그대로 붙이기 — 동작은 같고 한 줄이 늘어난다. 스펙 SC-001(테스트 통과)이 판정하므로 굳이 남기지 않는다. 기각.

## D5. 뷰 타입 이동 — `AvoidedSubstanceView` → `com.kbap.api.ingredient.AvoidedIngredientView`

- **Decision**: `home/AvoidedSubstanceView` 를 `ingredient/AvoidedIngredientView` 로 옮기고 이름을 바꾼다(필드 `code`·`name` 불변). `HomeResult`·`HomeResponse` 의 import·참조 3곳을 갱신한다. HTTP 응답 DTO `AvoidedSubstanceResponse` 와 필드 `avoidedSubstances` 는 외부 계약이므로 그대로 둔다.
- **Rationale**: `IngredientService` 가 `home` 패키지 타입을 반환하면 하위 기능이 상위 조합 기능을 아는 역방향 import 가 생긴다. 뷰는 성분 소유다. 이름은 KB-213 이후 유비쿼터스 언어(ingredient)에 맞춘다. `HomeResult` 의 구조(네 섹션·필드 형태)는 변하지 않는다(FR-001).
- **Alternatives considered**: 이름 유지하고 패키지만 이동 — 가능하나 새 메서드명(`getAvoidedIngredients`)과 반환 타입명이 어긋난다. 사용자가 원하면 이 선택도 무방하다(diff 동일 규모).

## D6. 테스트 전략 — 기존 통합 테스트 무수정 + 신규 테스트 없음

- **Decision**: `HomeControllerTest`·`HomeGuestTest` 는 한 줄도 고치지 않는다. 새 메서드 4개에 별도 스펙을 만들지 않는다 — 네 섹션 각각의 순서·빈 결과·중복 제거·언어·위험도 시나리오가 이미 이 두 파일에 있고, 새 메서드는 그 시나리오의 유일한 실행 경로다.
- **Rationale**: 헌법 I 의 사이클에서 이 작업은 **Refactor 단계**다 — 새 동작이 없으므로 Red 가 없고, 기존 그린 스위트가 회귀 방어다(kb-220 선례와 같은 판정). 삭제되는 `getMostReviewedFoodIds`·`getRecentReadyFoodIds` 를 직접 부르는 테스트는 없다(리포지토리 테스트 `ScanHistoryRepositoryTest` 는 쿼리를 직접 검증하므로 영향 없음).
- **Alternatives considered**: `FoodService` 단위 스펙 추가 — 통합 테스트와 같은 것을 두 번 검증. 기각(요청 시 후속).

## D7. 트랜잭션·경계 규칙 — 변경 없음

- **Decision**: `getHome` 의 `@Transactional(readOnly = true)` 를 유지하고(외곽 스냅샷 — D4 의 전제), 새 서비스 메서드도 각각 `@Transactional(readOnly = true)` 를 선언한다(참여). ArchUnit 규칙은 손대지 않는다.
- **Rationale**: CLAUDE.md 트랜잭션 규약(서비스 public 메서드 전부 명시 선언). `ModuleBoundaryTest` 는 `common.domain` 간 방향과 어댑터 참조를 검사하며 `api.<feature>` 서비스가 리포지토리를 주입받는 것은 이미 허용된 형태(`FoodService` 가 스캔 리포지토리를 이미 주입)다.
