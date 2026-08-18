# Data Model: 장소 검색 Google Places (New) 전환 (KB-350)

**영속 스키마 변경 없음** — enum 값 추가와 seam·응답 타입 변경뿐이다.

## seam (com.kbap.common.port.place)

### PlaceSearchClient (재정의)

| 메서드 | 현행 | 변경 |
|---|---|---|
| `searchNearby(query, lon, lat): List<FoundPlace>` | 키워드("음식점") 기반 주변 | `searchNearbyRestaurants(lon, lat, lang): List<FoundPlace>` — 타입 기반, query 소멸, `lang: LanguageCode` 추가 |
| `searchPage(query, lon, lat, page): PlaceSearchPage` | 페이지 조회 | `searchByKeyword(query, lon, lat, lang): List<FoundPlace>` — 단일 ≤20건, `lang: LanguageCode` 추가 |

- lang → 구글 `languageCode` 매핑은 어댑터 소유: `zh-Hans→zh-CN`·`zh-Hant→zh-TW`, 나머지 8종 동일.

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
| `PlaceSearchRequest` | query·좌표·page | `page` 삭제 + **`lang` 필수 추가**(빈 값 400·미지원 en 폴백 — 공통 규약) |
| nearby 요청 | 좌표 | **`lang` 필수 추가** (동일 규약) |
