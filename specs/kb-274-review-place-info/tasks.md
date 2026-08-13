# Tasks: 리뷰 작성 시 식당(장소) 검색·선택 저장

**Input**: Design documents from `/specs/kb-274-review-place-info/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/{place-search-api,review-place-api}.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패 테스트 선작성 → Red 확인 → 구현(Green). 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).

**Organization**: 검색(US1)과 저장 계열(US2~US4)은 코드가 겹치지 않아 독립 구현 가능. US2 구현(`ReviewResponse.from`)이 US3 를 대부분 충족한다 — US3 는 회귀 고정 테스트 중심.

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

Gradle 멀티모듈 — `:common`(도메인·seam)·`:infra:place`(신규)·`:api`(web). 경로는 워크트리 루트 기준.

---

## Phase 1: Setup

- [X] T001 신규 모듈 `:infra:place` 뼈대 — `settings.gradle.kts` include 추가, `infra/place/build.gradle.kts`(`kbap.spring-conventions` + `"implementation"(project(":common"))`), api `build.gradle.kts` 에 `"implementation"(project(":infra:place"))` 추가, 빈 소스 디렉터리 구성. `settings.gradle.kts`, `infra/place/build.gradle.kts`, `api/build.gradle.kts`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 저장 계열(US2~4)이 공유하는 도메인 모델·스키마. US1(검색)은 이 단계와 무관하게 T001 후 시작 가능.

- [X] T002 `ReviewPlace` 값 객체 단위 테스트 작성 → **Red 확인** — 길이(100/200/30) 초과·위도(-90~90)/경도(-180~180) 범위 밖 `require` 실패, 정상 생성, 전 항목 null 허용. `common/src/test/kotlin/com/kbap/common/domain/review/ReviewPlaceTest.kt`
- [X] T003 `ReviewPlace` `@Embeddable` 값 객체 구현(Green) — 전 필드 nullable(`name`·`address`·`kakaoPlaceId`·`latitude`·`longitude`), 컬럼명·길이 상수·`init` 검증은 data-model.md 표 그대로. `common/src/main/kotlin/com/kbap/common/domain/review/model/ReviewPlace.kt`
- [X] T004 [P] Flyway 마이그레이션 — `food_review` 에 nullable 컬럼 5개 ADD(`place_name` varchar(100)·`place_address` varchar(200)·`kakao_place_id` varchar(30)·`place_latitude`/`place_longitude` decimal(10,7)), 인덱스 없음. 파일명은 **생성 시점 timestamp**: `api/src/main/resources/db/migration/Vyyyy.MM.dd.HH.mm.ss__food_review_place_columns.sql`
- [X] T005 `Review` 엔티티에 `@Embedded val place: ReviewPlace?` 추가 + `update(rating, content, imageRefs, place)` 시그니처 확장(전량 교체). 기존 호출부 `ReviewService.updateReview` 는 컴파일 정합을 위해 `place = null` 로 우선 전달(US4 에서 요청값 연결). `common/src/main/kotlin/com/kbap/common/domain/review/model/Review.kt`, `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt`

**Checkpoint**: `./gradlew :common:test` 통과 + `:api:test` 기존 무회귀(`ddl-auto=validate` 가 스키마 정합 검증)

---

## Phase 3: User Story 1 - 리뷰 작성 화면에서 식당 검색 (Priority: P1) 🎯 MVP

**Goal**: 별도 컨트롤러 `GET /api/v1/places` — 서버가 카카오 키워드 검색을 대신 호출해 결과 목록 반환. 인증 필수.

**Independent Test**: 키워드 검색 → 결과 목록(페이크 seam) / 빈 query 400 / 미인증 401 / 카카오 실패 PLACE-001.

### Tests for User Story 1 (Test-First — 작성 후 반드시 Red 확인) ⚠️

- [X] T006 [P] [US1] `PlaceControllerTest` 작성 → **Red 확인**: (1) 정상 검색 — `PlaceSearchClient` 페이크(`@TestConfiguration` `@Primary`)가 준 결과가 `payload.items`·`hasNext` 로 매핑, (2) 결과 없음 → 빈 배열, (3) `query` 누락·공백 → 400, (4) 토큰 없음 → 401, (5) 페이크가 `BusinessException(PLACE-001)` 던지면 502 + `code=PLACE-001`. `api/src/test/kotlin/com/kbap/api/place/PlaceControllerTest.kt`
- [X] T007 [P] [US1] `KakaoPlaceSearchClientTest` 작성 → **Red 확인**: 카카오 응답 JSON(정상·항목 결측·`is_end`) → `PlaceSearchResult` 매핑, 카카오 4xx/5xx·키 미설정 → `PLACE-001`. `MockRestServiceServer`(또는 `RestClient.Builder` 목) 사용 — 실호출 없음. `infra/place/src/test/kotlin/com/kbap/infra/place/KakaoPlaceSearchClientTest.kt`

### Implementation for User Story 1

- [X] T008 [P] [US1] seam 계약 — `PlaceSearchClient`(fun search(query, page): PlaceSearchResult) + `PlaceSearchResult`·`FoundPlace` 값 타입(Spring-free, ArchUnit port 규칙 준수). `common/src/main/kotlin/com/kbap/common/port/place/PlaceSearchClient.kt`
- [X] T009 [P] [US1] `ErrorCode` 에 `PLACE_SEARCH_FAILED`(`PLACE-001`, HTTP 502) 채번(`ErrorCodeStatusTest` 정합). `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt`
- [X] T010 [US1] `KakaoPlaceSearchClient` 구현(Green — T007 통과) — `RestClient` 로 카카오 키워드 검색(`size=15`), `Authorization: KakaoAK` 헤더, 실패·키 미설정 → `PLACE-001`. `infra/place/src/main/kotlin/com/kbap/infra/place/KakaoPlaceSearchClient.kt`
- [X] T011 [US1] api 검색 창구(Green — T006 통과) — `PlaceController`(GET `ApiPaths.V1 + "/places"`, `@AuthMemberId`, `query` 필수·`page` 기본 1)·`PlaceApi`(swagger 문서 인터페이스)·`PlaceSearchResponse` + `PlaceConfig` 조립(`kbap.kakao.rest-api-key` 프로퍼티) + `WebConfig` 보호 경로 `/api/v1/places`·`/api/v1/places/*` 등록. `api/src/main/kotlin/com/kbap/api/place/`, `api/src/main/kotlin/com/kbap/api/core/config/{PlaceConfig,WebConfig}.kt`

**Checkpoint**: 검색 독립 검증 완료 — 저장 없이도 데모 가능

---

## Phase 4: User Story 2 - 선택한 식당 정보와 함께 리뷰 작성 (Priority: P2)

**Goal**: 작성 요청의 선택 `place` 저장 + 작성 응답 반환. 미포함 작성은 기존과 동일 — **리뷰 컨트롤러 변경은 이것 하나**.

**Independent Test**: place 포함 작성 → 응답·DB 보존 / 미포함 → 기존 동작 + null / 검증 위반 400 (검색 API 없이 값 직접 구성으로 검증).

### Tests for User Story 2 (Test-First — 작성 후 반드시 Red 확인) ⚠️

- [X] T012 [US2] `ReviewControllerTest` 작성 케이스 추가 → **Red 확인**: (1) place 포함 작성 → 응답 `payload.place` 전 항목 일치, (2) place 미포함 작성 → 기존 동작 + `place == null`, (3) 항목 일부 결측 → 결측만 null, (4) `name` 101자·`latitude` 91 → 400. `api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt`

### Implementation for User Story 2

- [X] T013 [P] [US2] `ReviewPlaceRequest` 중첩 DTO(`@Size` 3종·`@DecimalMin/Max` 좌표) + `ReviewCreateRequest` 에 `@field:Valid val place: ReviewPlaceRequest? = null`. `api/src/main/kotlin/com/kbap/api/review/ReviewCreateRequest.kt`
- [X] T014 [P] [US2] `ReviewResponse` 에 `place: ReviewPlaceResponse?` + `from` 매핑(작성·수정·목록 공용 단일 지점). `api/src/main/kotlin/com/kbap/api/review/ReviewResponse.kt`
- [X] T015 [US2] `ReviewService.createReview` 에 place 전달·엔티티 생성 연결 + 컨트롤러 DTO→도메인 변환(Green — T012 통과). `api/src/main/kotlin/com/kbap/api/review/{ReviewService,ReviewController}.kt`

**Checkpoint**: 검색→선택→저장 E2E 성립

---

## Phase 5: User Story 3 - 리뷰 조회 시 식당 정보 확인 (Priority: P3)

**Goal**: 리뷰가 노출되는 모든 조회에 place 포함, 없는 리뷰(기존 리뷰)는 null.

**Independent Test**: place 있는/없는 리뷰 목록 조회 → 각각 place 객체/null.

### Tests for User Story 3 (Test-First) ⚠️

- [X] T016 [US3] 목록 조회 노출 테스트: 음식별 리뷰 목록·내 리뷰 목록에서 place 저장 리뷰는 전 항목 반환, 미저장 리뷰는 `place == null`. `ReviewResponse.from` 단일 지점 특성상 US2 완료 시 **즉시 Green 일 수 있음** — 이 경우 회귀 고정 테스트로 유지(Red 미확인 사유를 커밋 메시지에 명시). `api/src/test/kotlin/com/kbap/api/review/ReviewListControllerTest.kt`

### Implementation for User Story 3

- [X] T017 [US3] (불필요 — T016 이 US2 완료 시점에 Green. `from` 미경유 조회 경로 없음) T016 이 Red 인 경우에만: `from` 미경유 조회 경로의 place 매핑 보완(예상 지점 없음). `api/src/main/kotlin/com/kbap/api/review/` 해당 파일

**Checkpoint**: US1~US3 독립 검증 완료

---

## Phase 6: User Story 4 - 리뷰 수정 시 식당 정보 변경 (Priority: P4)

**Goal**: 수정 요청으로 place 교체·제거(전량 교체 — content·imagePaths 와 동일 규칙).

**Independent Test**: 새 place 로 수정 → 교체 / 생략 → 제거 / 없던 리뷰에 추가.

### Tests for User Story 4 (Test-First — 작성 후 반드시 Red 확인) ⚠️

- [X] T018 [US4] `ReviewControllerTest` 수정 케이스 추가 → **Red 확인**: (1) 다른 place 로 수정 → 교체, (2) place 생략 → 제거, (3) 없던 리뷰에 추가, (4) 수정에서도 검증 위반 400. `api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt`

### Implementation for User Story 4

- [X] T019 [US4] `ReviewUpdateRequest` 에 `@field:Valid val place: ReviewPlaceRequest? = null` 추가 + `ReviewService.updateReview` 의 `place = null` 임시 전달을 요청값 연결로 교체(Green — T018 통과). `api/src/main/kotlin/com/kbap/api/review/{ReviewCreateRequest,ReviewService,ReviewController}.kt`

**Checkpoint**: 전 스토리 독립 검증 완료

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T020 [P] swagger 문서 보강 — `PlaceApi`·`ReviewApi` 에 검색/place 요청·응답 설명(파라미터 애너테이션 위치 규약: 문서만 인터페이스에). `api/src/main/kotlin/com/kbap/api/{place/PlaceApi,review/ReviewApi}.kt`
- [X] T021 [P] 프로필별 설정 — `kbap.kakao.rest-api-key` 를 `application-{local,dev,staging,prod}.yml`/환경변수 배선(키 값 자체는 커밋 금지, `.env.example` 항목 추가). `api/src/main/resources/`, `.env.example`
- [X] T022 전체 회귀 — `./gradlew build`(신규 모듈·ArchUnit `ModuleBoundaryTest`(port Spring-free 포함)·Flyway↔`ddl-auto=validate` 정합·기존 테스트 무회귀) + quickstart.md 검증 절차 수행

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: 즉시 — US1 의 인프라 모듈 전제
- **Phase 2 (Foundational)**: 즉시(Phase 1 과 병렬 가능) — US2~4 를 블록
- **Phase 3 (US1 검색)**: Phase 1 완료 후 — Phase 2 와 무관(파일 비겹침)
- **Phase 4 (US2 저장)**: Phase 2 완료 후 — US1 과 병렬 가능
- **Phase 5 (US3 조회)** / **Phase 6 (US4 수정)**: US2 완료 후 순차 권장(T016 판정·`ReviewPlaceRequest` 재사용)
- **Phase 7 (Polish)**: 전 스토리 완료 후

### Within Each Story

- 테스트 선작성·Red 확인 → 구현(Green) → 필요 시 리팩터
- US1: (T006 ∥ T007) → (T008 ∥ T009) → T010 → T011
- Foundational: T002→T003(Red→Green), T004 는 병렬, T005 는 T003 후
- US2: T013 ∥ T014 → T015

### Parallel Opportunities

- Phase 1 ∥ Phase 2 / US1 ∥ US2(다른 개발자·다른 파일)
- T004(마이그레이션) ∥ T002→T003 / T006 ∥ T007 / T008 ∥ T009 / T013 ∥ T014 / T020 ∥ T021

---

## Implementation Strategy

**MVP First**: T001 → US1(검색)이 1차 데모 지점, Phase 2 → US2(저장)까지가 E2E MVP. 이후 US3(조회 회귀 고정)·US4(수정) 증분. 단일 개발자 기준 Phase 1→2→US1→US2→US3→US4 순차 진행 권장, task(또는 Red→Green) 단위 커밋.

---

## Notes

- 총 22 tasks — Setup 1 · Foundational 4 · US1 6 · US2 4 · US3 2 · US4 2 · Polish 3
- 리뷰 컨트롤러 변경은 US2·US4 의 place 전달뿐 — 검색은 전부 별도 창구(`api.place`)
- `ErrorCode` 신규 채번은 `PLACE-001` 하나(검색 전용). 저장 쪽 validation 은 기존 공통 400
- 테스트 시드(`FoodTestSeed`·`HomeTestSeed`)의 `food_review` INSERT 는 컬럼 명시라 무영향
- `:batch` 는 review·place 미사용 — 영향 없음
