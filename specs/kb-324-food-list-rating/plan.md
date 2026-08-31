# Implementation Plan: 음식 목록 응답에 리뷰 평점·개수 추가

**Branch**: `kb-324-food-list-rating` | **Date**: 2026-08-11 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-324-food-list-rating/spec.md`

## Summary

음식 목록 아이템의 공유 표현(`FoodSummaryView` → `FoodSummaryResponse`)에 `averageRating: Double?`·`reviewCount: Long` 를 추가한다. 집계는 `ReviewJpaRepository` 에 foodIds 배치 집계 쿼리 1개(`aggregateRatingsByFoodIds` — group by)로 공급해 페이지당 쿼리 1회를 유지하고, `FoodSummaryView` 를 조립하는 5개 소비처(목록·검색 `FoodService.foodPage`, 홈 인기+최근 스캔 `HomeService`, 북마크 `BookmarkService`, 어드민 `AdminFoodService`)에 동일하게 합류한다. 상세의 전체(overall) 집계와 같은 규칙(소프트 삭제 리뷰 제외·소수 1자리 반올림)이라 목록·상세 수치가 항상 일치한다. 스키마 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Spring Boot 4.1)

**Primary Dependencies**: 기존 스택 그대로 — 신규 의존성 없음

**Storage**: MySQL — **스키마 변경 없음**. `food_review` 기존 테이블 집계 조회만 추가

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers. repository 배치 집계 테스트(`:common`) + 목록/검색·홈·북마크 MockMvc 통합 테스트(`:api`)

**Target Platform**: `:api` web bootJar

**Project Type**: 기존 모듈러 모놀리스 내 응답 확장 (`:common` review 쿼리 + food dto, `:api` 소비 서비스들)

**Performance Goals**: 페이지당 집계 쿼리 1회 고정(`food_id in (...) group by food_id`) — 리뷰 건수·음식 수 비례 N+1 금지

**Constraints**: 기존 목록 응답 필드 불변(추가만). 목록 집계 = 상세 overall 집계(값·반올림 동일)

**Scale/Scope**: repository 쿼리 1, dto 필드 2, 조립처 5, 응답 DTO 1

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | repository 배치 집계·API 응답 필드 모두 Red 테스트 선행 |
| II. Bounded Contexts | ✅ | food→review 방향 의존 없음 — 집계는 review 소유 리포지토리가 제공하고, api 조립 계층이 foodId 로 합류(도메인 맵 변경 없음). `FoodSummaryView` 는 값만 받는다 |
| III. Dependency Direction | ✅ | `:api` → `:common` 방향만. 신규 모듈·seam 없음 |
| IV. Persistence Ownership | ✅ | 집계 쿼리는 `common.domain.review.ReviewJpaRepository`(소유 도메인)에 추가. 소비 계층 직접 참조는 허용 규칙(KB-220) |
| V. Language Policy | ✅ | 언어 관련 변경 없음(평점은 언어 무관 수치) |

**게이트 통과** — 위반 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-324-food-list-rating/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   └── food-list-rating.md
└── tasks.md              # /speckit-tasks 산출물
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/review/
└── ReviewJpaRepository.kt            # aggregateRatingsByFoodIds 배치 집계 추가

common/src/main/kotlin/com/kbap/common/domain/food/dto/
└── FoodSummaryView.kt                # averageRating·reviewCount 필드 추가

common/src/test/kotlin/com/kbap/common/domain/review/
└── ReviewJpaRepositoryTest.kt        # 배치 집계 테스트 추가

api/src/main/kotlin/com/kbap/api/
├── food/FoodSummaryResponse.kt       # 응답 필드 추가
├── food/FoodController.kt            # (필요 시) 조립 경로 정리
├── home/HomeService.kt               # 인기·최근 스캔 합류
├── bookmark/BookmarkService.kt       # 북마크 목록 합류
└── admin/AdminFoodService.kt         # 어드민 그리드 합류

common/src/main/kotlin/com/kbap/common/domain/food/
└── FoodService.kt                    # foodPage(목록·검색 공통 조립)에 합류

api/src/test/kotlin/com/kbap/api/
├── food/FoodBrowseControllerTest 또는 기존 목록 테스트 보강
├── home/HomeControllerTest           # 평점 필드 검증 보강
└── bookmark/BookmarkControllerTest   # 평점 필드 검증 보강
```

**Structure Decision**: 신규 패키지·모듈 없음. `FoodSummaryView.from` 시그니처에 평점 값 2개를 추가하고, 각 조립처가 `aggregateRatingsByFoodIds` 결과 맵을 전달한다.

## Complexity Tracking

위반 없음 — 해당 없음.
