# Tasks: 리뷰 평가 항목 추가 — 제공 속도·직원 친절도

**Input**: Design documents from `/specs/kb-347-review-extra-ratings/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/review-extra-ratings.md

**Tests**: Test-First (헌법 원칙 I) — 스토리마다 실패 테스트(Red) 선작성 후 구현(Green).

**Organization**: Setup 없음(기존 기능 확장). Foundational = 스키마+엔티티(두 스토리 공통 전제). US1(작성/수정) → US2(열람 노출) 순서 — US2 조립은 US1 의 `ReviewResponse.from` 변경에 올라탄다.

## Phase 1: Foundational (Blocking Prerequisites)

**Purpose**: 두 스토리가 공유하는 스키마·도메인 모델 — ddl-auto=validate 라 엔티티와 마이그레이션은 함께 가야 전체 테스트가 부팅된다.

- [ ] T001 Flyway 마이그레이션 추가 — `api/src/main/resources/db/migration/V<생성시각>__food_review_extra_ratings.sql`: `ALTER TABLE food_review ADD COLUMN serving_speed_rating TINYINT NOT NULL DEFAULT 0, ADD COLUMN staff_kindness_rating TINYINT NOT NULL DEFAULT 0` (파일명은 생성 시점 timestamp 포맷 `Vyyyy.MM.dd.HH.mm.ss__`)
- [ ] T002 `Review` 엔티티 확장 — `common/src/main/kotlin/com/kbap/common/domain/review/model/Review.kt`: `var servingSpeedRating: Int = 0`·`var staffKindnessRating: Int = 0`(`@Column(name=..., nullable=false, columnDefinition="TINYINT")`), `EXTRA_RATING_RANGE = 0..5` require(생성·update 공통), `update()` 시그니처에 두 파라미터 추가 — 컴파일 깨지는 호출부(`ReviewService.updateReview`)는 일단 0 전달로 봉합

**Checkpoint**: `./gradlew :api:test --tests "com.kbap.api.review.ReviewControllerTest"` 그린(기존 회귀 없음 — DEFAULT 0/require 하위호환 확인).

---

## Phase 2: User Story 1 — 리뷰 작성 시 세부 평가 2종을 남긴다 (P1)

**Goal**: 작성·수정 요청으로 두 항목(0~5)을 저장하고 작성/수정 응답에 노출한다.

**Independent Test**: 작성 요청에 두 항목 포함 → 응답에 그대로; 누락→0; 범위 밖→400; 수정 전체 교체.

- [ ] T003 [US1] **Red**: `api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt` 에 시나리오 추가 — (1) 작성 시 `servingSpeed=5, staffKindness=3` → 응답 동일 값, (2) 두 필드 누락 작성 → 응답 둘 다 0, (3) `servingSpeed=6`·`staffKindness=-1` → 400, (4) 수정에서 `servingSpeed=0` 으로 지움·`staffKindness` 누락 → 응답 0·0. 실행해 **실패(Red) 확인**
- [ ] T004 [P] [US1] 요청 DTO 확장 — `api/src/main/kotlin/com/kbap/api/review/ReviewCreateRequest.kt`: `ReviewCreateRequest`·`ReviewUpdateRequest` 에 `servingSpeed: Int? = null`·`staffKindness: Int? = null` + `@Min(0)`/`@Max(5)` + `@Schema`(0=평가 안 함·누락 시 0 명시)
- [ ] T005 [P] [US1] 응답 DTO 확장 — `api/src/main/kotlin/com/kbap/api/review/ReviewResponse.kt`: `servingSpeed: Int`·`staffKindness: Int` 필드 + `from()` 에 `review.servingSpeedRating`/`review.staffKindnessRating` 매핑 + `@Schema`(항상 0~5, 0=평가 안 함)
- [ ] T006 [US1] 서비스 스레딩 — `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt`: `createReview`·`updateReview` 에 `servingSpeed: Int?`·`staffKindness: Int?` 파라미터 추가, `?: 0` 해석 후 엔티티 생성·`update()` 전달(T002 봉합 제거)
- [ ] T007 [US1] 컨트롤러 전달 — `api/src/main/kotlin/com/kbap/api/review/ReviewController.kt`: create·update 에서 request 필드 전달. **Green 확인**: `./gradlew :api:test --tests "com.kbap.api.review.ReviewControllerTest"`

**Checkpoint**: US1 시나리오 전부 그린 — 작성/수정 경로 완결(MVP).

---

## Phase 3: User Story 2 — 리뷰 열람자가 세부 평가를 본다 (P1)

**Goal**: 목록(전체·음식별)·음식 상세 recentReviews·내 리뷰에서 두 항목이 항상 0~5 숫자로 노출된다(비회원 포함, 기존 리뷰는 0).

**Independent Test**: 두 항목 저장된 리뷰를 각 경로로 조회해 값 확인, 도입 전 리뷰는 0·0.

- [ ] T008 [P] [US2] **Red**: `api/src/test/kotlin/com/kbap/api/review/ReviewListControllerTest.kt`(음식별 목록)·`GlobalReviewListControllerTest.kt`(전체 피드·비회원) 에 두 항목 노출 시나리오 추가 — 저장값 노출 + 항목 없이 작성된 리뷰는 0·0. 실행해 실패 확인(조립이 US1 에서 끝났으면 즉시 그린일 수 있음 — 그 경우 assertion 이 실값을 검증하는지 확인하고 통과로 기록)
- [ ] T009 [P] [US2] **Red**: `api/src/test/kotlin/com/kbap/api/food/FoodDetailReviewSectionTest.kt` 의 recentReviews 시나리오에 `servingSpeed`·`staffKindness` 노출 assertion 추가(비회원 조회 포함)
- [ ] T010 [US2] **Green 확인**: `./gradlew :api:test --tests "com.kbap.api.review.*" --tests "com.kbap.api.food.FoodDetailReviewSectionTest"` — 실패 시 조립 누락 지점 보완(내 리뷰 `getMyReviewPage` 포함 전 경로가 `toResponses`→`ReviewResponse.from` 경유인지 확인)

**Checkpoint**: 전 열람 경로 노출 완결.

---

## Phase 4: Polish & Cross-Cutting

- [ ] T011 OpenAPI 스냅샷 정합 — `api/src/test/kotlin/com/kbap/api/openapi/OpenApiSnapshotTest.kt` 실행, 스키마 변경으로 깨지면 스냅샷 갱신 절차대로 재생성
- [ ] T012 전체 빌드 그린 — `./gradlew build` (ArchUnit·ddl-auto=validate·전 모듈 회귀 포함). 필요시 quickstart.md 수동 검증

---

## Dependencies

```text
T001, T002 (Foundational — T001 ∥ T002 후 checkpoint)
  → US1: T003(Red) → T004 ∥ T005 → T006 → T007(Green)
  → US2: T008 ∥ T009(Red) → T010(Green)   # US1 완료 후
  → Polish: T011 → T012
```

- US2 는 US1 의 `ReviewResponse.from` 변경에 의존 — 순차 진행.
- [P]: T004∥T005(다른 파일), T008∥T009(다른 파일).

## Implementation Strategy

- **MVP = Foundational + US1**: 저장·작성/수정 응답까지로 계약 검증 가능.
- US2 는 조립 재사용 확인 성격 — 대부분 테스트 추가로 끝나야 정상이며, 구현이 더 필요하면 설계(단일 조립 지점) 위반 신호로 본다.
- 커밋 단위: Foundational+US1 → US2 → Polish (파일 겹침 크면 단일 feature 커밋 허용).
