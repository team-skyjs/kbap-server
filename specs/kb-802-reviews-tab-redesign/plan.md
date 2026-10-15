# Implementation Plan: 리뷰 단건 조회 · 리뷰 음식 요약 한국어명

**Branch**: `feat/kb802-reviews-tab-redesign` | **Date**: 2026-10-16 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-802-reviews-tab-redesign/spec.md`

## Summary

`GET /api/reviews/{reviewId}?lang=` 를 추가해 리스트 항목과 같은 `ReviewResponse` 를 준다. 가시성은 번역 API 가 이미 쓰는 `ReviewService.getVisibleReview` 를, 응답 조립은 목록의 `toResponses(includeFood = true)` 를 재사용한다. 게스트는 `WebConfig.guestExemptions` 정규식 1줄로 허용한다. `ReviewFoodResponse` 에 `koreanName` 을 `FoodSummaryView` 와 같은 규칙으로 더한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / Java 21
**Primary Dependencies**: Spring Boot 4.1 (Web MVC, Validation, JPA), springdoc
**Storage**: MySQL — 스키마 변경 없음
**Testing**: Kotest BehaviorSpec + `@IntegrationTest`(MockMvc, Testcontainers MySQL)
**Target Platform**: Linux 서버(:api 모듈)
**Project Type**: web-service
**Performance Goals**: 단건 조회 = 리뷰 1 + 가시성 count 1 + 작성자·좋아요·음식 IN 조회(목록과 같은 경로, N+1 없음)
**Constraints**: 계약 C-1·C-2 그대로. REVIEW-001 HTTP 400 유지. 기존 목록 정렬·페이징 불변
**Scale/Scope**: :api `review` 패키지 4파일 + `WebConfig` 1줄 + 테스트 1파일

## Constitution Check

- **I. Tests on Request**: 계획이 BehaviorSpec 통합 테스트를 명시 → 작성한다. PASS
- **II. Bounded Contexts**: 리뷰 컨텍스트 안에서만 변경. 음식은 기존 `FoodRepository` 조회(이미 `toResponses` 가 사용). PASS
- **III. Layered Dependency Direction**: api 기능 패키지 → common.domain 방향 그대로. PASS
- **IV. Persistence Ownership**: 엔티티·리포지토리 변경 없음. PASS
- **V. Domain Content Language Policy**: 표시명은 `Food.displayName(lang)` 로만 해석. PASS

Post-design 재평가: 변화 없음, PASS.

## Project Structure

### Documentation (this feature)

```text
specs/kb-802-reviews-tab-redesign/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/reviews-detail.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── core/config/WebConfig.kt          # guestExemptions 1줄
└── review/
    ├── ReviewController.kt           # getReview
    ├── ReviewApi.kt                  # Swagger
    ├── ReviewListRequest.kt          # ReviewDetailRequest(lang)
    ├── ReviewResponse.kt             # ReviewFoodResponse.koreanName
    └── ReviewService.kt              # getReview
api/src/test/kotlin/com/kbap/api/review/
└── ReviewDetailControllerTest.kt
```

**Structure Decision**: 기존 `review` 기능 패키지에 추가. 새 패키지·파일 최소.

## Complexity Tracking

없음.
