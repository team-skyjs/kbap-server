# Implementation Plan: 장소 검색을 Google Places API (New) 로 전환

**Branch**: `kb-350-google-places` | **Date**: 2026-08-19 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-350-google-places/spec.md` (Jira KB-350)

## Summary

`common.port.place.PlaceSearchClient` seam 뒤의 카카오 어댑터를 Google Places API (New) 어댑터로 교체한다. 주변 조회는 Nearby Search(New — `includedTypes: restaurant`·`rankPreference: DISTANCE`·≤20건), 키워드 검색은 Text Search(New — `textQuery`+`locationBias`·≤20건 단일 응답)로 매핑하고, **seam 에서 페이지 개념을 제거**한다(`searchPage` → 단일 목록). **표시 언어(lang)를 필수로 받아**(헌법 V 규약 — 빈 값 400·미지원 en 폴백) 구글 `languageCode` 로 전달한다 — `zh-Hans/zh-Hant` 는 구글 표기 `zh-CN/zh-TW` 로 어댑터가 매핑. API 응답에서 `hasNext`·`page` 가 사라지고, 리뷰 장소 출처에 `GOOGLE_PLACE` 를 추가한다(기존 값 보존). 어댑터는 카카오 구현과 같은 골격(RestClient + HTTP interface + **자체 Jackson3 매퍼** — Boot4 이중 클래스패스 함정 대응)으로 작성한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1

**Primary Dependencies**: 기존 스택 — 신규 의존 없음 (RestClient·HTTP interface 는 카카오 어댑터가 이미 사용)

**Storage**: 스키마 무변경 — `PlaceSource` 는 문자열 저장(enum 값 추가만, 컬럼 길이 여유 확인)

**Testing**: Kotest BehaviorSpec — 어댑터 단위 테스트(MockRestServiceServer, 카카오 테스트 선례) + 컨트롤러 통합 테스트

**Target Platform**: `:api` web bootJar (place 어댑터는 api 전용 — `api.infra.place`)

**Project Type**: web-service — 외부 제공자 교체 + 소폭 계약 변경(페이징 제거)

**Performance Goals**: 검색당 구글 호출 1회 (기존 카카오와 동일 횟수)

**Constraints**: 클라이언트 계약 — 응답 항목 형태(이름·주소·좌표) 유지, `page` 파라미터는 400 없이 무시(전환기), 새 X-API-Version 없음. 키 미설정 시 부팅 정상 + 호출만 PLACE-001 실패(기존 카카오 패턴 유지)

**Scale/Scope**: `api.infra.place` 어댑터 교체 2파일 + seam 1파일 + place API 4파일 + `PlaceSource` enum + 테스트. 도메인·영속·batch 영향 0 (리뷰 enum 제외)

## Constitution Check

- **I. Test-First**: PASS 예정 — 어댑터(요청 형식·응답 매핑·오류)와 API(단일 20건·page 무시·GOOGLE_PLACE 저장) Red → Green.
- **II. Bounded Contexts**: PASS — seam 계약 수정은 `common.port.place`, 구현은 `api.infra.place`, 조립은 `PlaceConfig`(ADR-0018 구조 그대로). `PlaceSource` 는 review 도메인 모델 소속 유지.
- **III. Layered Dependency Direction**: PASS — 새 의존 없음.
- **IV. Persistence Ownership**: PASS — 엔티티·스키마 무변경(enum 값 추가는 코드 값·컬럼 그대로).
- **V. Language Policy**: PASS — 표시 언어를 받는 API 규약을 그대로 따른다: `lang` 필수(빈 값 400), 미지원 코드는 en 폴백, 검증은 요청 경계(DTO `@NotBlank` + `LanguageCode.from`)가 소유하고 seam·어댑터는 확정된 `LanguageCode` 를 받는다.

**위반 없음.**

## Project Structure

### Documentation (this feature)

```text
specs/kb-350-google-places/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── place-search.md
└── tasks.md             # /speckit-tasks output
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/port/place/
└── PlaceSearchClient.kt        # seam 재정의 — searchNearbyRestaurants(lon,lat,lang)·searchByKeyword(query,lon,lat,lang)
                                #   둘 다 List<FoundPlace>(≤20), lang: LanguageCode. PlaceSearchPage dto 삭제

api/src/main/kotlin/com/kbap/api/infra/place/
├── GooglePlacesApi.kt          # (신규) HTTP interface — POST places:searchText·places:searchNearby + 응답 DTO
├── GooglePlaceSearchClient.kt  # (신규) seam 구현 — 자체 Jackson3 매퍼·FieldMask·오류 → PLACE-001
├── KakaoLocalApi.kt            # 삭제
└── KakaoPlaceSearchClient.kt   # 삭제

api/src/main/kotlin/com/kbap/api/place/
├── PlaceService.kt             # RESTAURANT_KEYWORD 삭제 — nearby 는 타입 기반, 검색은 단일 목록, lang 전달
├── PlaceSearchRequest.kt       # page 제거 + lang 필수 추가(nearby 요청 DTO 에도 — 헌법 V 규약)
├── PlaceSearchResponse.kt      # PlaceSearchPageResponse(hasNext) → 단일 items 응답
└── PlaceApi.kt                 # swagger — 제공자·20건 단일·page 제거 문서화

api/src/main/kotlin/com/kbap/api/core/config/PlaceConfig.kt
                                # kbap.google.places-api-key 로 GooglePlaceSearchClient 조립

common/src/main/kotlin/com/kbap/common/domain/review/model/PlaceSource.kt
                                # GOOGLE_PLACE 추가 (기존 값 불변)
```

**Structure Decision**: seam(`common.port.place`)·구현(`api.infra.place`)·조립(`PlaceConfig`) 3분할은 ADR-0018 구조 그대로 유지 — 바뀌는 건 구현체와 seam 시그니처(페이지 제거)뿐. 카카오 어댑터는 폴백 없이 삭제한다(FR-005).

## 핵심 설계

### 1. 구글 호출 매핑 (공식 문서 검증 완료)

| 용도 | 엔드포인트 | 핵심 요청 | 정렬 |
|---|---|---|---|
| 주변 식당 | `POST /v1/places:searchNearby` | `includedTypes: ["restaurant"]`·`maxResultCount: 20`·`locationRestriction.circle(center, radius 500m)`·`languageCode: <lang 매핑>` | `rankPreference: DISTANCE` (카카오 sort=distance 동작 보존) |
| 키워드 검색 | `POST /v1/places:searchText` | `textQuery`·`pageSize: 20`·`locationBias.circle(center, radius 2000m)`·`languageCode: <lang 매핑>` | 기본(RELEVANCE) |

- **다국어(FR-007)**: 요청 `lang`(LanguageCode 10종)을 구글 `languageCode` 로 전달. 코드 매핑은 어댑터 소유 — `zh-Hans → zh-CN`, `zh-Hant → zh-TW`, 나머지 8종(ko·en·ja·vi·id·th·ru·es)은 동일 표기([구글 지원 언어 문서](https://developers.google.com/maps/faq#languagesupport) 검증). 결과 식당명·주소가 해당 언어로 내려온다(구글이 번역 미보유 시 현지어 반환 — 그대로 수용).

- 공통 헤더: `X-Goog-Api-Key`, **`X-Goog-FieldMask: places.displayName,places.formattedAddress,places.location`** (FieldMask 누락 시 구글이 오류 — 필수. 필드를 좁게 유지해 과금 SKU 도 최소화)
- 응답 매핑: `displayName.text` → name, `formattedAddress` → address, `location.latitude/longitude` → 좌표. `FoundPlace` 형태 불변.
- 페이징 없음 — `nextPageToken` 을 읽지 않는다.

### 2. 어댑터 골격 — 카카오 선례 + Boot4 Jackson 함정

- RestClient + `@PostExchange` HTTP interface + **어댑터 소유 Jackson3 매퍼**(`JsonMapper + kotlinModule`, `disableDefaults`) — 위키의 "Boot 4 Jackson 이중 클래스패스 함정"(기본 컨버터가 Kotlin 모듈 없는 Jackson3 를 잡아 전 필드 기본값 DTO 가 빈 성공으로 조용히 바인딩) 대응을 카카오 구현에서 그대로 승계.
- 오류 처리: 키 미설정 경고 + `PLACE_SEARCH_FAILED`(PLACE-001, 502), RestClientException·파싱 실패 동일 — 기존 패턴 유지.
- 타임아웃: connect 3s / read 5s (기존 값).

### 3. 계약 변경 (클라이언트 공유 필요)

- 검색 응답: `{items, hasNext}` → `{items}` — `hasNext` 삭제(페이징 제거). 항목 형태 불변.
- 요청: `page` 파라미터 삭제 — DTO 에서 필드 제거하면 Spring 이 미지원 파라미터를 무시하므로 전환기 클라이언트도 400 없음(FR-003 자동 충족).
- 리뷰 작성 `place.source` 에 `GOOGLE_PLACE` 허용 — 응답 `source` 에도 신설 값 노출. 기존 값 불변.

## 리스크 / 확인 사항

- **구글 API 키 발급·과금 설정은 운영 선행 작업** — 키 없이도 부팅·다른 기능 정상, 장소 검색만 PLACE-001(기존 카카오와 동일한 완충).
- `place_source` 컬럼 길이가 `GOOGLE_PLACE`(12자)를 수용하는지 Flyway DDL 확인(카카오 KAKAO_PLACE 11자와 유사 — 확인만).
- 기존 `KakaoPlaceSearchClientTest` 는 삭제하고 같은 검증 수준(요청 형식·매핑·오류·키 미설정)의 구글 테스트로 대체.
- `kbap.kakao.rest-api-key` 프로퍼티·배포 환경변수 제거는 배포 저장소(SSM 등) 후속 정리 필요 — PR 본문에 명시.
