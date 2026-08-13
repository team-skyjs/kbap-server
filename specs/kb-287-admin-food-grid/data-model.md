# Data Model: 관리자 음식 목록 카드 그리드·상태 필터·상세 모달

**DB 스키마 변경 없음 — Flyway 마이그레이션 없음.** 기존 `food` 테이블·`content_status` 컬럼을 그대로 조회한다. 변경은 뷰 모델(:api)과 리포지토리 파생 쿼리(:common)뿐이다.

## 기존 엔티티 (변경 없음)

### Food (`com.kbap.common.domain.food.model.Food`)

사용 필드: `id`, `koreanName`, `description`, `spiciness`, `contentStatus`, `imageRef`, `nameTranslations`, `descriptionTranslations`, `avoidanceSubstances`, `version`, `createdAt`, `updatedAt`. 소프트삭제는 `BaseEntity` `@SQLRestriction("status = 'ACTIVE'")` 자동 적용.

### FoodContentStatus (`com.kbap.common.domain.food.model.FoodContentStatus`)

`INCOMPLETE · PENDING_IMAGE · PENDING_REVIEW · REVIEWED · REVIEW_REJECTED · READY` — 6종 전부가 필터 선택지이자 카드 배지. 배지 색 배정(신규 배정 2종 포함):

| 상태 | 배지 클래스 |
|------|------------|
| READY | `badge-ok` |
| INCOMPLETE | `badge-neutral` |
| PENDING_IMAGE, PENDING_REVIEW | `badge-progress` |
| REVIEW_REJECTED | `badge-warn` |
| REVIEWED | `badge-info` |

## 리포지토리 (`:common` — `FoodJpaRepository`)

추가 파생 쿼리 2개(둘 다 `Page<Food>` 반환):

```kotlin
fun findByContentStatus(contentStatus: FoodContentStatus, pageable: Pageable): Page<Food>
fun findByKoreanNameContainingAndContentStatus(
    koreanName: String, contentStatus: FoodContentStatus, pageable: Pageable,
): Page<Food>
```

## 뷰 모델 (`:api` — `AdminFoodService.kt` 내)

### AdminFoodListPageView — 필드 추가

| 필드 | 타입 | 변경 | 설명 |
|------|------|------|------|
| `status` | `FoodContentStatus?` | **추가** | 적용된 상태 필터(null = 전체). 템플릿이 select 선택 상태·링크 스레딩에 사용 |
| 나머지(`items`·`page`·`totalPages`·`totalCount`·`hasPrev`·`hasNext`·`query`) | | 유지 | |

### AdminFoodSummaryView — 필드 추가

| 필드 | 타입 | 변경 | 설명 |
|------|------|------|------|
| `imageUrl` | `String?` | **추가** | `ImageUrls.resolve(imagePublicBaseUrl, food.imageRef)` — 카드 썸네일. null 이면 플레이스홀더 |
| `hasImage` | `Boolean` | **제거** | `imageUrl` 로 대체(카드가 이미지 자체를 그리므로 ○/× 표기 불용) |
| 나머지(`id`·`koreanName`·`contentStatus`·`spiciness`·`updatedAt`) | | 유지 | `from(food)` → `from(food, imagePublicBaseUrl)` 시그니처 변경 |

### AdminFoodDetailView — 변경 없음

### getFoodPage 시그니처

```kotlin
fun getFoodPage(page: Int, query: String? = null, status: FoodContentStatus? = null): AdminFoodListPageView
```

분기: (keyword, status) → `findAll` / `findByKoreanNameContaining` / `findByContentStatus` / `findByKoreanNameContainingAndContentStatus`.

## 상태 전이

없음 — 이 기능은 조회·표시 개편이며, 저장 시 상태 전이는 기존 `food.transitionByContentState()`(KB-260) 그대로다.
