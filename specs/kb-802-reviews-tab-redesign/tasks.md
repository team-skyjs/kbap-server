---
description: "Task list — 리뷰 단건 조회 · 리뷰 음식 요약 한국어명 (KB-802 BE)"
---

# Tasks: 리뷰 단건 조회 · 리뷰 음식 요약 한국어명

**Input**: `/specs/kb-802-reviews-tab-redesign/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/reviews-detail.md, quickstart.md
**Tests**: 계획이 명시 → `ReviewDetailControllerTest` (BehaviorSpec 통합).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음.

## Phase 2: Foundational

- [X] T001 [P] [US3] `ReviewFoodResponse` 에 `koreanName: String?`(name 다음) + `from()` 에서 `food.displayName(LanguageCode.KO).takeIf { it != name }`, `@Schema` 설명 갱신, `ReviewResponse.food` 설명 "목록·단건 조회에서 채워지며" — `api/src/main/kotlin/com/kbap/api/review/ReviewResponse.kt`

## Phase 3: User Story 1·2 — 단건 조회 (P1)

- [X] T002 [P] [US1] `ReviewDetailRequest(@field:NotBlank lang)` — `api/src/main/kotlin/com/kbap/api/review/ReviewListRequest.kt`
- [X] T003 [US1] `ReviewService.getReview(viewerMemberId, reviewId, lang)` = `getVisibleReview` → `toResponses(listOf(review), viewer, lang, includeFood = true).single()` — `ReviewService.kt`
- [X] T004 [US1] `ReviewController.getReview` `@GetMapping("/reviews/{reviewId}")` + `ReviewApi` Swagger(`@ApiErrors(REVIEW_NOT_FOUND)`) — `ReviewController.kt`, `ReviewApi.kt`
- [X] T005 [US1] 게스트 예외 `GuestExemption("GET", Regex("^${ApiPaths.API}/reviews/\\d+$"))` — `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt`
- [X] T006 [US1][US2][US3] `ReviewDetailControllerTest` — 게스트·회원(likedByMe)·삭제·미존재·차단·본인·음식 삭제·lang 누락·비숫자 id·`/reviews/me` 401·koreanName en/ko·목록 필드 집합 동일 — `api/src/test/kotlin/com/kbap/api/review/ReviewDetailControllerTest.kt`

## Phase 4: Polish

- [X] T007 `./gradlew build` 그린
- [X] T008 quickstart curl 실측(로컬 compose)

## Dependencies

T001·T002 병렬 → T003 → T004 → T005 → T006 → T007 → T008.
