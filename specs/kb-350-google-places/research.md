# Research: 장소 검색 Google Places (New) 전환 (KB-350)

## Decision 1: 페이징 제거 — 단일 ≤20건 (사용자 확정)

- **Decision**: 주변·검색 모두 단일 응답 최대 20건. 토큰(nextPageToken)은 요청·응답·서버 내부 어디에도 없다.
- **Rationale**: 기획 확정 — 식당 태그 화면에서 20건이면 충분. 구글 Text Search(New)는 페이지당 최대 20건·검색당 총 60건 상한·토큰 방식이라 page 번호 계약은 유지 불가였고, 40건(내부 2회 호출) 안은 비용 2배 대비 실익 없음으로 기각.
- **Alternatives considered**: 토큰 노출(클라이언트 페이징 유지 — 기획상 불필요), 40건 내부 합산(비용 2배 — 기각), page 번호 흉내(서버 토큰 캐시 — 다중 인스턴스에서 파손, 기각).
- **근거 문서**: [Text Search (New)](https://developers.google.com/maps/documentation/places/web-service/text-search) — "maximum of 60 results across all pages", `pageSize` 1~20. [Nearby Search (New)](https://developers.google.com/maps/documentation/places/web-service/nearby-search) — `maxResultCount` 1~20, 페이지네이션 없음.

## Decision 2: 용도별 엔드포인트 매핑

- **Decision**: 화면 진입(주변) = **Nearby Search(New)** `includedTypes: ["restaurant"]` + `rankPreference: DISTANCE` + `locationRestriction.circle(radius 500m)`. 키워드 검색 = **Text Search(New)** `textQuery` + `locationBias.circle(radius 2000m)`, 정렬 기본(관련도).
- **Rationale**: 기존 카카오 구현이 "음식점" 키워드 + distance 정렬로 주변을 흉내냈는데, 구글은 타입 기반 Nearby 가 정확히 그 용도다. 검색은 사용자 키워드 신뢰가 우선이라 타입 제한 없이 위치 바이어스만. 반경은 상수로 두고 조정 가능(주변 = 도보권 500m, 검색 = 상권 2km — 초기값, 실사용 피드백으로 튜닝).
- **Alternatives considered**: 검색에도 restaurant 타입 강제(식당명이 아닌 키워드 검색이 죽음 — 기각), 주변을 Text Search "음식점"으로(카카오 방식 복제 — Nearby 가 정확·과금 동일계열이라 기각).

## Decision 3: FieldMask 최소화

- **Decision**: `X-Goog-FieldMask: places.displayName,places.formattedAddress,places.location` 세 필드만.
- **Rationale**: FieldMask 는 필수 헤더(누락 시 구글 오류)이고, 우리 계약(`FoundPlace`: 이름·주소·좌표)에 필요한 것만 요청하면 과금 SKU 도 낮게 유지된다.

## Decision 4: 어댑터 골격 — 카카오 선례 승계 (Boot4 Jackson 함정 포함)

- **Decision**: RestClient + `@PostExchange` HTTP interface + 어댑터 소유 Jackson3 매퍼(`JsonMapper` + `kotlinModule`, `disableDefaults`), connect 3s/read 5s, 오류 → `PLACE_SEARCH_FAILED`(PLACE-001), 키 미설정 시 부팅 정상·호출만 실패.
- **Rationale**: agent-hub "Boot 4 Jackson 이중 클래스패스 함정" — 기본 컨버터가 Kotlin 모듈 없는 Jackson3 를 잡으면 전 필드 기본값 DTO 가 빈 값으로 조용히 성공한다. 카카오 어댑터가 이미 이 대응을 갖췄으므로 골격 그대로 교체가 최소 위험.

## Decision 5: PlaceSource.GOOGLE_PLACE 신설 (기존 값 보존)

- **Decision**: enum 에 `GOOGLE_PLACE` 추가. 기존 `KAKAO_PLACE` 행은 마이그레이션하지 않는다.
- **Rationale**: 출처는 저장 시점의 사실 기록 — 과거 리뷰는 카카오에서 고른 게 맞다. 클라이언트는 신규 작성에서만 `GOOGLE_PLACE` 를 보낸다.
- **Alternatives considered**: KAKAO_PLACE → EXTERNAL_PLACE 일반화(기존 데이터·클라이언트 분기 파손 — 기각).

## Decision 6: 검색 응답 봉투 단순화

- **Decision**: `{items, hasNext}` → `{items}`. 요청의 `page` 는 DTO 필드 삭제로 자연 무시(Spring 은 미지원 쿼리 파라미터를 400 없이 버림 — FR-003 충족).
- **Rationale**: 페이징이 없는데 `hasNext` 를 남기면 항상 false 인 죽은 필드가 된다.

## 코드 조사 결과

- seam: `PlaceSearchClient.searchNearby(query, lon, lat)` / `searchPage(query, lon, lat, page): PlaceSearchPage` — 페이지 제거로 재정의 대상. `PlaceSearchPage` dto 삭제.
- 소비자: `PlaceService`(유일) — `RESTAURANT_KEYWORD("음식점")` 상수는 Nearby 타입 기반 전환으로 소멸.
- 조립: `PlaceConfig` — `kbap.kakao.rest-api-key` → `kbap.google.places-api-key` 교체.
- 리뷰 연계: `ReviewPlace.source`(enum 문자열 저장) — `PlaceSource` 에 값 추가만으로 저장·응답 모두 동작(컬럼 길이 확인 필요).
- 테스트 선례: `KakaoPlaceSearchClientTest` 가 RestClient.Builder 주입 + MockRestServiceServer 패턴 — 구글 테스트가 동일 패턴 사용.
