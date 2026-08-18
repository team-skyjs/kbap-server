# Implementation Plan: 리뷰 목록 조회 API 비회원 공개

**Branch**: `kb-348-guest-review-list` | **Date**: 2026-08-18 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-348-guest-review-list/spec.md` (Jira KB-348)

## Summary

`GET /api/reviews`(전체·음식별)를 비회원에게 연다. JWT 필터의 기존 **`GuestExemption(method, pathRegex)`** 메커니즘(community posts GET 선례)으로 같은 경로의 POST(작성)는 보호를 유지한 채 GET 만 면제하고, 컨트롤러 바인딩을 `@AuthMemberIdOrNull` 로, `ReviewService.getReviewPage` 의 viewer 를 nullable 로 완화한다. 응답 계약 불변 — 비회원은 `likedByMe` false·차단/신고 제외 미적용. KB-334 에서 공용화한 `toResponses`(viewer nullable)를 그대로 재사용한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1

**Primary Dependencies**: 기존 스택 — 신규 의존 없음

**Storage**: 무변경 (쿼리 재사용 — `findReviewPage` 의 exclusion 파라미터에 비회원 센티널 `[-1]`)

**Testing**: Kotest BehaviorSpec + `@SpringBootTest`(MySQL Testcontainers) — 기존 `ReviewListControllerTest`·`GlobalReviewListControllerTest` 확장

**Target Platform**: `:api` web bootJar

**Project Type**: web-service — 접근 제어 완화(계약·데이터 불변)

**Performance Goals**: 해당 없음 — 쿼리 형태 불변

**Constraints**: FR-003 — 같은 경로의 쓰기 계열(POST /api/reviews)·`/reviews/me`·수정·삭제·좋아요는 401 유지. 새 X-API-Version 없음

**Scale/Scope**: 3파일(WebConfig·ReviewController·ReviewService) + swagger 문서 + 테스트. 도메인·영속·batch 영향 0

## Constitution Check

- **I. Test-First**: PASS 예정 — 비회원 목록 조회(Red) → 면제·바인딩 완화(Green). 401 유지 시나리오도 함께 단언.
- **II. Bounded Contexts**: PASS — `api.review`·`api.core` 조립 계층만. 도메인 맵 무변경.
- **III. Layered Dependency Direction**: PASS — 새 의존 없음.
- **IV. Persistence Ownership**: PASS — 리포지토리·쿼리 무변경.
- **V. Language Policy**: PASS — lang 규칙 불변(비회원도 필수·400).

**위반 없음 — Complexity Tracking 불필요.**

## Project Structure

### Documentation (this feature)

```text
specs/kb-348-guest-review-list/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── review-list-access.md
└── tasks.md             # /speckit-tasks output
```

(data-model.md 없음 — 엔티티·응답 형태 변경이 전무한 접근 제어 변경)

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── core/config/WebConfig.kt        # GuestExemption("GET", "^/api/reviews$") 추가
├── review/ReviewController.kt      # listReviews: @AuthMemberId → @AuthMemberIdOrNull(Long?)
├── review/ReviewService.kt         # getReviewPage viewer nullable — 비회원 시 exclusion 센티널·toPage 전달
└── review/ReviewApi.kt             # swagger 문서 — 비회원 조회 가능 명시

api/src/test/kotlin/com/kbap/api/review/
├── ReviewListControllerTest.kt     # 비회원 음식별 목록 시나리오
├── GlobalReviewListControllerTest.kt # 비회원 전체 피드 시나리오
└── ReviewControllerTest.kt         # 쓰기 계열 401 유지 회귀 (기존 + 보강)
```

**Structure Decision**: 보호 예외의 단일 출처는 `WebConfig` 의 `guestExemptions` 목록이다 — community posts GET 과 같은 자리에 항목 하나를 추가한다(필터·컨트롤러에 예외 로직을 흩뿌리지 않는 기존 원칙). 면제 정규식은 `^/api/reviews$` 정확 일치라 `/reviews/me`·`/reviews/{id}`·`/reviews/{id}/like` 는 계속 필터를 탄다.

## 구현 방향

1. **필터 면제**: `WebConfig.jwtAuthenticationFilterRegistration` 의 `guestExemptions` 에 `GuestExemption("GET", Regex("^${ApiPaths.API}/reviews$"))` 추가. `shouldNotFilter` 가 method+URI 정확 일치로 판단하므로 POST /api/reviews·GET /api/reviews/me 는 영향 없음.
2. **컨트롤러**: `listReviews` 의 `@AuthMemberId memberId: Long` → `@AuthMemberIdOrNull memberId: Long?` (community 선례와 동일 패턴). 토큰이 있으면 회원 맥락 유지.
3. **서비스**: `getReviewPage(viewerMemberId: Long?, ...)` — `viewerMemberId?.let(::excludedMemberIds) ?: listOf(-1L)` 패턴(getRecentFoodReviews 와 동일)으로 exclusion 처리, `toPage` 의 viewer 파라미터를 nullable 로 완화(내부 `toResponses` 는 이미 nullable — KB-334).
4. **문서**: `ReviewApi.listReviews` swagger 설명에 비회원 조회 가능·likedByMe false·차단/신고 미적용 명시.

## 리스크 / 확인 사항

- `getMyReviewPage`(GET /reviews/me)는 경로가 달라 면제 정규식에 안 걸린다 — 테스트로 401 확인.
- 탈퇴 회원 토큰: 필터는 통과(유효 토큰), 리졸버가 memberId 를 주므로 그 id 기준 exclusion(빈 목록)·likedByMe 로 동작 — 기존 상세 recentReviews 취급과 동일, 별도 분기 불필요.
- CORS·버저닝(X-API-Version 필수)은 무변경 — 비회원도 헤더는 보내야 한다(기존 규약).
