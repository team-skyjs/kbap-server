# Data Model: 장소 검색 Google Places (New) 전환 (KB-350)

**영속 스키마 변경 없음** — enum 값 추가와 seam·응답 타입 변경뿐이다.

## seam (com.kbap.common.port.place)

### PlaceSearchClient (재정의)

| 메서드 | 현행 | 변경 |
|---|---|---|
| `searchNearby(query, lon, lat): List<FoundPlace>` | 키워드("음식점") 기반 주변 | `searchNearbyRestaurants(lon, lat): List<FoundPlace>` — 타입 기반, query 파라미터 소멸 |
| `searchPage(query, lon, lat, page): PlaceSearchPage` | 페이지 조회 | `searchByKeyword(query, lon, lat): List<FoundPlace>` — 단일 ≤20건 |

### FoundPlace — 불변 (name·address?·latitude?·longitude?)

### PlaceSearchPage — **삭제** (hasNext 소멸)

## 도메인 (com.kbap.common.domain.review.model)

### PlaceSource

| 값 | 상태 |
|---|---|
| `KAKAO_PLACE` | 유지 — 기존 리뷰 데이터 보존(신규 저장은 더 이상 없음) |
| `GOOGLE_PLACE` | **신설** — 전환 후 검색 결과로 선택한 장소 |
| `AUTHOR_LOCATION`·`MANUAL` | 불변 |

- 저장: `food_review.place_source` 문자열 컬럼 그대로(값 추가만, 길이 수용 확인).

## API 응답 (com.kbap.api.place)

| 타입 | 현행 | 변경 |
|---|---|---|
| `PlaceNearbyResponse` | items | 불변 |
| `PlaceSearchPageResponse` | items + hasNext | `items` 만 (이름도 `PlaceSearchResponse` 계열로 정리 — 구현 재량) |
| `PlaceSearchRequest` | query·좌표·page | `page` 필드 삭제 (미지원 파라미터는 Spring 이 무시) |
