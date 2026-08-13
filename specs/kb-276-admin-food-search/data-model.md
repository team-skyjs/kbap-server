# Data Model: 관리자 음식 목록 음식명 검색

## 스키마 변경

**없음.** 기존 `food` 테이블의 `korean_name` 컬럼을 조회 조건으로만 사용한다.
Flyway 마이그레이션·엔티티 변경 없음.

## 조회 모델

### FoodJpaRepository (common.domain.food) — 파생 쿼리 추가

```kotlin
fun findByKoreanNameContaining(koreanName: String, pageable: Pageable): Page<Food>
```

- `Containing` = `LIKE %?%` (Spring Data 가 `%`·`_` 이스케이프)
- 소프트 삭제 제외는 `BaseEntity`의 `@SQLRestriction("status = 'ACTIVE'")` 자동 적용

### AdminFoodListPageView (api.admin) — 필드 추가

| 필드 | 타입 | 설명 |
|------|------|------|
| `query` | `String?` | 확정된(트림된) 검색어. 없으면 null — 템플릿 링크·검색 폼 초기값에 사용 |

기존 필드(`items`·`page`·`totalPages`·`totalCount`·`hasPrev`·`hasNext`)는 불변.
검색 시 `totalPages`·`totalCount`·`hasNext`는 검색 결과 기준으로 계산된다(기존 조립 로직 재사용).

## 상태 전이

없음 — 읽기 전용 기능.
