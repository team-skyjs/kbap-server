# Implementation Plan: 리뷰 평가 항목 추가 — 제공 속도·직원 친절도

**Branch**: `kb-347-review-extra-ratings` | **Date**: 2026-08-18 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-347-review-extra-ratings/spec.md`

## Summary

리뷰에 세부 평가 2종(제공 속도·직원 친절도)을 추가한다. `food_review` 에 `TINYINT NOT NULL DEFAULT 0` 컬럼 2개를 추가하고, 값은 요청·저장·응답 전부 **0~5 정수 단일 규약**(0=평가 안 함, null 없음)으로 흐른다. 응답 조립은 전 열람 경로가 공유하는 `ReviewResponse.from` 한 곳만 바꾸면 목록·상세 동봉·내 리뷰·작성/수정 응답에 일괄 반영된다. 집계·노출 규칙·API 버전은 불변.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (기존 스택 그대로)

**Primary Dependencies**: Spring Boot 4.1 (web·validation·data-jpa), Flyway — 신규 의존 없음

**Storage**: MySQL `food_review` 테이블 — 컬럼 2개 추가(additive, DEFAULT 0, 백필 불필요)

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers (`@SpringBootTest`+MockMvc, ddl-auto=validate 로 엔티티↔스키마 정합 자동 검증)

**Target Platform**: `:api` 모듈만 (batch 무관 — 리뷰는 배치 소비 없음)

**Project Type**: web-service (기존 모놀리스 내 기능 확장)

**Performance Goals**: 해당 없음 — 기존 쿼리 형태 불변(select r 엔티티 조회에 컬럼만 추가)

**Constraints**: 무버전 additive 계약(KB-334 선례) · 블루/그린 공존 안전(DEFAULT 0) · 기존 별점 집계 불변

**Scale/Scope**: 파일 ~6개 수정 + 마이그레이션 1개 + 테스트 — 소형

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design. — **통과(위반 없음)***

- **I. Test-First**: ReviewControllerTest·FoodDetailReviewSectionTest 에 실패 테스트 선작성(Red) 후 구현(Green). 통과.
- **II. Bounded Contexts**: 변경은 review 컨텍스트(`common.domain.review`)와 api 기능 패키지(`api.review`) 내부 — 도메인 간 의존 변화 없음. 통과.
- **III. Layered Dependency Direction**: api → common 방향 그대로. seam·port 무관. 통과.
- **IV. Persistence Ownership**: 엔티티=도메인 모델 — 검증(require)·update 를 `Review` 가 소유. JPA 연관 추가 없음(값 컬럼 2개). Flyway 가 스키마 owner. 통과.
- **V. Domain Content Language Policy**: 숫자 평가값 — 번역 대상 콘텐츠 아님. 검증은 요청 DTO 소유. 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-347-review-extra-ratings/
├── plan.md              # This file
├── research.md          # Phase 0 — 결정 6건(범위·0규약·컬럼·계약·검증 위치·쿼리 무변경)
├── data-model.md        # Phase 1 — Review 확장·Flyway
├── quickstart.md        # Phase 1 — 수동 검증 시나리오
├── contracts/
│   └── review-extra-ratings.md  # 요청/응답 additive 계약
└── tasks.md             # /speckit-tasks 산출(이 커맨드 아님)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/review/model/Review.kt   # 필드 2개·requireValid 0..5·update 시그니처
api/src/main/kotlin/com/kbap/api/review/
├── ReviewCreateRequest.kt   # servingSpeed·staffKindness (Int?, @Min(0) @Max(5)) — Create·Update 둘 다
├── ReviewResponse.kt        # servingSpeed·staffKindness (Int) + from 매핑
├── ReviewService.kt         # createReview·updateReview 파라미터 스레딩(null→0)
└── ReviewController.kt      # request 필드 전달
api/src/main/resources/db/migration/
└── V<timestamp>__food_review_extra_ratings.sql   # ALTER TABLE ADD COLUMN ×2
api/src/test/kotlin/com/kbap/api/review/ReviewControllerTest.kt        # 작성/수정/범위 400/기본 0/목록 노출
api/src/test/kotlin/com/kbap/api/food/FoodDetailReviewSectionTest.kt   # recentReviews 노출
```

**Structure Decision**: 기존 review 기능 패키지·도메인 패키지 안에서만 확장한다 — 신규 패키지·모듈·서비스 없음. 응답 조립 단일 지점(`ReviewResponse.from`) 재사용으로 열람 경로 4곳이 자동 커버된다.

## 구현 노트 (Phase 1 설계 확정)

- `Review`: `var servingSpeedRating: Int = 0`·`var staffKindnessRating: Int = 0`, `EXTRA_RATING_RANGE = 0..5` require, `update()` 에 두 파라미터 추가(전체 교체).
- `ReviewService.createReview`/`updateReview`: `servingSpeed: Int?`·`staffKindness: Int?` 받아 `?: 0` 로 엔티티에 전달 — 누락=0 해석은 서비스 경계 한 곳.
- 응답 필드명은 `servingSpeed`/`staffKindness`(API), 엔티티는 `~Rating` 접미 — swagger description 에 "0 = 평가 안 함" 명시.
- 마이그레이션 파일명은 생성 시점 timestamp — 시드-결합 테스트 없음(food_review 는 리소스 경로 하드코딩 대상 아님).
- 기존 테스트 시드(HTTP POST /api/reviews 경유)는 필드 누락→0 이라 무수정 통과 — SC-002 회귀 0건 확인용.

## Complexity Tracking

> 위반 없음 — 해당 없음.
