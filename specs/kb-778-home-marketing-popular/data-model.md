# Data Model: 홈 인기 음식 마케팅 목록

DB 스키마 변경 없음. 신규 엔티티·마이그레이션 없음.

## 설정 모델 — `HomeMarketingPopularProperties` (`com.kbap.api.home`)

| 필드 | 타입 | 설정 키 | env | 기본 | 의미 |
|------|------|---------|-----|------|------|
| `foodIds` | `List<Long>` | `kbap.home.marketing-popular.food-ids` | `HOME_MARKETING_POPULAR_FOOD_IDS` | 빈 목록 | 마케팅 인기 풀, 콤마 구분 `6,4,5` |

- 형식 오류(숫자 아닌 id)는 바인딩 실패 → 앱 기동 실패.
- 빈 문자열 env 는 빈 목록으로 바인딩된다(단위 테스트로 고정).

## 버전 판정 — `ApiVersions` (`com.kbap.api.core`)

- `HOME_MARKETING_POPULAR = "1.2"` — `WebConfig.addSupportedVersions` 에 등록.
- 기존 객체(`AuthController` 의 기기 연결 1.1 분기가 사용). `isAtLeast(requested: String, minimum: String)` — `SemanticApiVersionParser` 로 파싱해 비교. 홈은 `@RequestHeader(X-API-Version)` 문자열을 넘긴다. 속성이 없거나 타입이 다르면 false(= 집계).

## 홈 조립 입력 — `HomeService.getHome(memberId, lang, marketingPopular)`

| `marketingPopular` | 인기 id 출처 | READY 필터 | 선택 |
|------|-------------|-----------|------|
| false | `PopularFoodIdCache.getPopularFoodIds(10)` | SQL(`findPopular`)이 적용 | 집계 순서 10 |
| true | `foodIds.distinct()` | `Food.isReady()` 후필터(`getRandomCuratedFoods`) | `shuffled().take(10)` |

## 기존 엔티티 — `Food` (변경 없음)

- 노출 가능 조건: `status = ACTIVE`(`@SQLRestriction` 자동) ∧ `contentStatus = READY`(`isReady()`).
- 조회: `FoodJpaRepository.findByIdIn(ids)`. 미존재·삭제 id 는 결과에 없어 자연 제외.
