# Implementation Plan: 비회원 음식 상세 조회 응답 개편

**Branch**: `kb-334-guest-food-detail` | **Date**: 2026-08-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/kb-334-guest-food-detail/spec.md` (Jira KB-334)

## Summary

비회원의 `GET /api/foods/{foodId}` 응답에서 회원 전제 필드가 기본값으로 뭉개지는 문제를 계약으로 정리한다: `overallRiskStatus` 는 null(판별 안 함 — 클라이언트의 비회원 판별 기준), `review.overall` 은 실수치 공개, `review.sameCountry` 는 null, `blur` 필드는 회원·비회원 공통 제거. `bookmarked: false`·`ingredients: []` 는 현행 유지. 무버전 매핑을 즉시 변경한다(새 X-API-Version 없음). 변경은 전부 `:api` 응답 조립 계층 — 도메인·영속 무변경.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1

**Primary Dependencies**: 기존 스택 그대로 — 신규 의존 없음

**Storage**: 무변경 (MySQL — 스키마·엔티티·리포지토리 손대지 않음)

**Testing**: Kotest BehaviorSpec + `@SpringBootTest`(MySQL Testcontainers) — 기존 `FoodDetail*Test` 스위트 확장

**Target Platform**: `:api` web bootJar

**Project Type**: web-service (기존 모듈러 모놀리스의 api 기능 패키지)

**Performance Goals**: 해당 없음 — 응답 조립 분기 변경뿐, 쿼리 수 불변(비회원 리뷰 집계 1회는 기존 회원 경로와 동일 쿼리)

**Constraints**: 회원 응답은 blur 제거 외 불변(SC-004). 무버전 매핑 즉시 변경 — 구 계약 공존 없음

**Scale/Scope**: `com.kbap.api.food` 3파일(`FoodService`·`GetFoodDetailResult`·`FoodDetailResponse`) + `FoodController` 조립 + 테스트. 도메인 맵·마이그레이션·batch 영향 0

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Test-First**: PASS 예정 — 비회원 시나리오(위험도 null·리뷰 공개·sameCountry null·blur 부재) 실패 테스트를 먼저 쓰고 구현한다. 기존 `FoodDetailReviewSectionTest` 의 blur 단언은 Red 단계에서 새 계약으로 갱신.
- **II. Bounded Contexts**: PASS — 변경은 `com.kbap.api.food`(+`api.review` 소비) 응답 조립뿐. `common.domain` 허용 맵 무변경.
- **III. Layered Dependency Direction**: PASS — api → common 방향 그대로. 새 의존 없음.
- **IV. Persistence Ownership**: PASS — 엔티티·리포지토리·스키마 무변경. `Food.overallRisk` 도메인 메서드도 불변(호출부에서 비회원 분기).
- **V. Language Policy**: PASS — lang 처리 무변경.

**위반 없음 — Complexity Tracking 불필요.**

## Project Structure

### Documentation (this feature)

```text
specs/kb-334-guest-food-detail/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── food-detail-response.md
└── tasks.md             # /speckit-tasks output (NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/food/
├── FoodService.kt            # getDetail — 비회원(memberId null)이면 overallRiskStatus null
├── GetFoodDetailResult.kt    # overallRiskStatus: RiskLevel → RiskLevel?
├── FoodDetailResponse.kt     # overallRiskStatus String? · ReviewSummaryResponse 재정의(blur 삭제·sameCountry nullable)
└── FoodController.kt         # reviewSummaryOf — 비회원도 실수치 조회, sameCountry null 조립

api/src/test/kotlin/com/kbap/api/food/
├── FoodDetailControllerTest.kt      # 비회원 위험도 null·bookmarked false 시나리오 추가
├── FoodDetailReviewSectionTest.kt   # blur 단언 제거, 비회원 overall 실수치·sameCountry null 시나리오
└── FoodDetailRatingTest.kt          # 회원 불변 회귀 확인
```

**Structure Decision**: 기존 `com.kbap.api.food` 기능 패키지 안에서만 수정한다. 도메인 서비스(`Food.overallRisk`)와 `ReviewService.getFoodRatingSummary(foodId, viewerCountryCode: String?)` 시그니처는 그대로 재사용 — 비회원 분기는 조립 계층(FoodService.getDetail / FoodController.reviewSummaryOf)이 소유한다.

## 구현 방향 (Phase 1 설계 근거)

1. **위험도 null**: `FoodService.getDetail` 에서 `input.memberId == null` 이면 `overallRiskStatus = null`. `GetFoodDetailResult.overallRiskStatus` 를 `RiskLevel?` 로, `FoodDetailResponse.overallRiskStatus` 를 `String?` 로 완화. 회원 경로는 기존 `food.overallRisk(userAvoidedCodes)` 그대로.
2. **리뷰 공개**: `FoodController.reviewSummaryOf` 의 비회원 조기 반환(`blurred()`)을 제거하고, 비회원도 `reviewService.getFoodRatingSummary(foodId, null)` 로 overall 을 집계한다. `sameCountry` 응답 필드는 비회원이면 null — 국적 없는 회원은 기존대로 `{0.0, 0}` (구분 지점: memberId 유무).
3. **blur 삭제**: `ReviewSummaryResponse` 에서 `blur` 프로퍼티와 `blurred()` 팩토리를 삭제, `sameCountry` 를 nullable 로. swagger `@Schema`(nullable) 동기화.

## 리스크 / 확인 사항

- `blurred()`·`blur` 참조가 다른 곳에 있는지 전수 확인 후 삭제(현재 파악: `FoodController`·테스트뿐).
- 비회원 리뷰 집계로 상세 조회 쿼리가 1회 늘어난다(기존 비회원은 집계 스킵) — 회원과 동일 쿼리라 수용.
