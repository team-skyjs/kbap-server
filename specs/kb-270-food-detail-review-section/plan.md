# Implementation Plan: 음식 상세 리뷰 섹션 응답 개편

**Branch**: `kb-270-food-detail-review-section` | **Date**: 2026-07-31 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-270-food-detail-review-section/spec.md`

## Summary

음식 상세 응답의 리뷰 관련 평탄 필드 3개(`averageRating`·`reviewCount`·`sameCountryAverageRating`)를 `review` 중첩 객체로 응집하고, 비회원 가림 표시(`blur`)와 평점 기본값(null→0.0) 계약을 추가한다. 리뷰 개수 조회 전략은 **현행 COUNT 집계 유지**로 결정한다 — count 는 avg 집계 쿼리에 무비용으로 함께 나오므로 비정규화해도 쿼리 수가 줄지 않고, 쓰기 경합·드리프트 비용만 추가되기 때문(비교 근거: [research.md](research.md)). DB 스키마 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), springdoc-openapi

**Storage**: MySQL (변경 없음 — 마이그레이션 불필요), 집계는 기존 `food_review` 인덱스(`idx_food_review_food_recent`, `idx_food_review_food_country`) 활용

**Testing**: Kotest BehaviorSpec + Spring MockMvc(@SpringBootTest, MySQL Testcontainers)

**Target Platform**: Linux server (api bootJar)

**Project Type**: web-service (기존 `:api` 모듈 내 기능 개편)

**Performance Goals**: 음식 상세 조회 응답 지연 기존과 동등(회원: 집계 쿼리 2회 유지, 비회원: 집계 0회로 오히려 감소)

**Constraints**: 리뷰 작성·삭제 즉시 반영(강한 정합 — 집계 방식이라 자동 충족), 비회원에게 실수치 서버측 차단

**Scale/Scope**: 터치 파일 ~5개(`FoodDetailResponse`·`FoodController`·`ReviewService` 또는 신규 응답 타입 1)·테스트 2~3개 파일. 음식당 리뷰 수십~수천 건 규모 가정

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 응답 구조·blur·0.0 계약을 MockMvc BehaviorSpec 실패 테스트로 먼저 고정 후 구현 (tasks 에서 Red→Green 순서 강제) |
| II. Bounded Contexts | PASS | 변경은 `com.kbap.api.food`·`com.kbap.api.review` 기능 패키지와 `common.domain.review` 기존 조회 재사용뿐 — 새 도메인 간 의존 없음. api 기능 패키지 간 사용(food→review)은 도메인 허용 맵 대상 아님 |
| III. Layered Dependency Direction | PASS | 모듈·패키지 의존 방향 변화 없음 (`:api` → `:common` 유지) |
| IV. Persistence Ownership | PASS | 엔티티·리포지토리·스키마 변경 없음(COUNT 집계 유지 결정). 조회 트랜잭션 경계 기존 `@Transactional(readOnly = true)` 유지 |
| V. Domain Content Language Policy | PASS | 리뷰 요약은 수치 데이터 — 언어 정책 무관 |

**추가 규율 확인**: 동시성 방어 수위(2026-07-30 고정 — 격리수준 미조정·비치명 경합 감수)가 비정규화 카운터의 원자 UPDATE·경합 방어 비용을 기각하는 근거로 작동한다(research.md 비교표 참조). API 응답 규약(`ResponseEntity<BaseResponse<T>>`)·엔드포인트 경로(`/api/v1` 유지, 신규 경로 없음) 준수.

**Post-Phase 1 재점검**: 설계 결과 신규 타입은 `com.kbap.api.food.FoodDetailResponse.ReviewSummaryResponse`(중첩) 1개, 도메인·인프라 접점 없음 — 위반 없음 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-270-food-detail-review-section/
├── plan.md              # This file
├── research.md          # Phase 0 — 리뷰 개수 조회 전략 비교·결정
├── data-model.md        # Phase 1 — 응답 모델·데이터 접점
├── quickstart.md        # Phase 1 — 검증 실행법
├── contracts/
│   └── food-detail-response.md   # Phase 1 — 상세 응답 계약(before/after)
└── tasks.md             # Phase 2 (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/
├── food/
│   ├── FoodDetailResponse.kt     # [수정] 평탄 3필드 제거 → review 중첩 객체(ReviewSummaryResponse 내장)
│   └── FoodController.kt         # [수정] 비회원 분기 — 회원만 집계 호출, 비회원은 blur 요약 고정값
└── review/
    ├── RatingSummary.kt          # [유지] 서비스 내부 집계 결과(nullable 유지 — 0.0 변환은 응답 경계)
    └── ReviewService.kt          # [유지] getFoodRatingSummary 현행 유지(COUNT 집계)

api/src/test/kotlin/com/kbap/api/food/
└── FoodDetailReviewSectionTest.kt  # [신규] 응답 구조·blur·0.0 계약 MockMvc BehaviorSpec
```

**Structure Decision**: 기존 `:api` 기능 패키지 구조를 그대로 따른다. 신규 파일은 테스트 1개뿐이며, 응답 타입은 `FoodDetailResponse` 의 중첩 data class 로 두어(파일 수 최소) ADR-0017 기능 패키지 원칙과 "파일 수가 적은 기능에 하위 패키지를 만들지 않는다" 컨벤션을 지킨다. DB·`:common`·`:infra` 는 건드리지 않는다.

## Complexity Tracking

> Constitution Check 위반 없음 — 해당 없음.
