# Tasks: 장소 검색 Google Places (New) 전환 (KB-350)

**Input**: Design documents from `/specs/kb-350-google-places/`

**Prerequisites**: plan.md, spec.md, research.md(결정 7건), data-model.md, contracts/place-search.md

**Tests**: Test-First NON-NEGOTIABLE (헌법 원칙 I) — 단계마다 실패 테스트 먼저(Red 확인).

**Organization**: Foundational(seam 재정의 + 구글 어댑터 — 전 스토리의 전제) → 스토리 3개 → Polish.

## Format: `[ID] [P?] [Story] Description`

---

## Phase 1: Foundational — seam 재정의·구글 어댑터·카카오 제거

- [X] T001 어댑터 단위 테스트(Red) — `api/src/test/kotlin/com/kbap/api/infra/place/GooglePlaceSearchClientTest.kt`(카카오 테스트의 MockRestServiceServer 패턴 승계): ① Nearby 요청 형식(`places:searchNearby`·includedTypes restaurant·maxResultCount 20·DISTANCE·circle 500m·FieldMask 3필드·X-Goog-Api-Key) ② Text 요청 형식(`places:searchText`·textQuery·pageSize 20·locationBias 2km) ③ `languageCode` 매핑(zh-Hans→zh-CN·zh-Hant→zh-TW·나머지 동일) ④ 응답 매핑(displayName.text→name·formattedAddress→address·location→좌표, name 없는 항목 제외) ⑤ 오류·파싱 실패 → PLACE-001 ⑥ 키 미설정 → PLACE-001. 컴파일 실패 = Red
- [X] T002 `common/src/main/kotlin/com/kbap/common/port/place/PlaceSearchClient.kt` — seam 재정의: `searchNearbyRestaurants(longitude, latitude, lang)`·`searchByKeyword(query, longitude, latitude, lang)` 둘 다 `List<FoundPlace>`, `PlaceSearchPage` 삭제
- [X] T003 `api/src/main/kotlin/com/kbap/api/infra/place/GooglePlacesApi.kt`(HTTP interface + 요청/응답 DTO)·`GooglePlaceSearchClient.kt`(자체 Jackson3 매퍼·타임아웃 3s/5s) 구현, `KakaoLocalApi`·`KakaoPlaceSearchClient`(+테스트) 삭제. T001 Green
- [X] T004 `api/src/main/kotlin/com/kbap/api/core/config/PlaceConfig.kt` — `kbap.google.places-api-key` 로 조립 교체, `application.yml` 의 kakao 키 항목·주석을 google 로 교체

## Phase 2: User Story 1 - 화면 진입 주변 식당 (Priority: P1) 🎯 MVP

- [X] T005 [US1] 장소 API 통합 테스트(Red) — 주변 조회: lang 별 요청이 구글 languageCode 로 전달, 응답 최대 20건 기존 형태, lang 누락 400, 좌표 오류 400 (기존 place 통합 테스트 파일 갱신 — 구글 목 서버 또는 seam 페이크로)
- [X] T006 [US1] `PlaceService`·nearby 요청 DTO(lang 필수 추가)·`PlaceController` 갱신 — `RESTAURANT_KEYWORD` 삭제. T005 Green

## Phase 3: User Story 2 - 키워드 검색 (Priority: P1)

- [X] T007 [US2] 통합 테스트(Red) — 검색: 단일 ≤20건, 응답에 `hasNext` 키 부재, 구 `page` 파라미터 전송 시 400 아님(무시), lang 필수 400
- [X] T008 [US2] `PlaceSearchRequest`(page 삭제·lang 추가)·`PlaceSearchResponse`(items 단일 봉투)·`PlaceService`·`PlaceApi` swagger(제공자·20건 단일·lang 규칙·커서 없음) 갱신. T007 Green

## Phase 4: User Story 3 - 리뷰 장소 출처 (Priority: P2)

- [X] T009 [US3] 리뷰 테스트(Red) — `place.source = GOOGLE_PLACE` 로 작성 → 저장·조회 응답에 GOOGLE_PLACE, 기존 KAKAO_PLACE 리뷰 조회 불변, MANUAL·AUTHOR_LOCATION 불변
- [X] T010 [US3] `common/src/main/kotlin/com/kbap/common/domain/review/model/PlaceSource.kt` — `GOOGLE_PLACE` 추가(+리뷰 swagger allowableValues 갱신). T009 Green

## Phase 5: Polish & Cross-Cutting

- [X] T011 [P] `rg -in "kakao" api common` 잔재 0건 확인(코드·yml·문서 주석), 리뷰 `ReviewPlaceResponse` 등 source 문서 갱신 여부 점검
- [X] T012 전체 빌드·테스트 그린(`./gradlew test`), quickstart 대조, PR 생성(운영 후속 — GOOGLE_PLACES_API_KEY 등록·KAKAO 키 제거 명시)

---

## Dependencies & Execution Order

- Phase 1 이 전 스토리의 전제(seam·어댑터). 이후 US1 → US2(같은 컨트롤러·DTO 파일) → US3(독립 — 병렬 가능하나 순차 권장).
- 각 단계 Red → Green 절대 순서. T001 의 Red 는 컴파일 실패로 확인.

## Implementation Strategy

- MVP = Phase 1+2 (주변 목록이 화면 진입 기본 경로). 단일 세션 직접 구현, 논리 단위 커밋.
- 실키 없는 테스트 전제 — 어댑터는 MockRestServiceServer, API 통합은 seam 페이크 빈 또는 목 서버(구현 시 카카오 선례 확인 후 동일 방식).
