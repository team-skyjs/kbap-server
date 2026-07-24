# Data Model: 음식 리스트·상세 조회 응답에 북마크 여부 포함 (KB-153)

**DB 스키마·Flyway·엔티티 무변경.** `bookmark(member_id, food_id)` 테이블·`Bookmark` 엔티티는 KB-139 에서 만들어져 있고, 본 기능은 조회 창구와 응답 DTO 만 추가한다.

## 도메인 창구 (신규 public 1)

### BookmarkService.findBookmarkedFoodIds

```
findBookmarkedFoodIds(memberId: Long?, foodIds: Collection<Long>): Set<Long>
```

| 입력 | 반환 |
|------|------|
| memberId null (비회원) | `emptySet()` — 쿼리 없이 즉시 |
| foodIds 비어 있음 | `emptySet()` — 쿼리 없이 즉시 |
| 회원 + foodIds | 요청 집합 중 그 회원이 북마크한(ACTIVE) foodId 부분집합 |

- `@Transactional(readOnly = true)`. 소프트삭제 제외는 `@SQLRestriction` 자동.
- 내부: `BookmarkJpaRepository.findByMemberIdAndFoodIdIn(memberId, foodIds)` (internal derived query) → `map { it.foodId }.toSet()`.

## API 응답 DTO 변경 (`:app:api`)

### FoodSummaryResponse (리스트·검색·북마크 목록 공유)

| 필드 | 타입 | 값 규칙 |
|------|------|---------|
| `bookmarked` (신규) | `Boolean` (non-null) | 리스트·검색: `foodId in bookmarkedIds` / 북마크 목록: 상수 `true` / 비회원: `false` |

### FoodDetailResponse

| 필드 | 타입 | 값 규칙 |
|------|------|---------|
| `bookmarked` (신규) | `Boolean` (non-null) | 회원: 해당 foodId 북마크 여부 / 비회원: `false` |

`from(...)` 팩토리에 `bookmarked` 파라미터 추가(기본값 없음 — 호출부가 항상 명시).

## 무변경

- 도메인 dto: `FoodSummaryView`·`GetFoodDetailResult`·`BookmarkPage` — 북마크 여부는 food 도메인 관심사가 아니므로 담지 않는다.
- 상태 전이·관계·인덱스: 무변경.
