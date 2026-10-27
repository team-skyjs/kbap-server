# Implementation Plan: 홈 topRatedFoods

**Branch**: `feat/kb800-home-top-rated-foods` | **Date**: 2026-10-16 | **Spec**: [spec.md](spec.md)

## Summary

`X-API-Version 1.3`(신규 `ApiVersions.HOME_TOP_RATED`) 이상 홈 응답에 `topRatedFoods` 를 붙인다. native 집계 쿼리 1개로 상위 20개 food id·평균을 얻고, 기존 순서 보존 요약 로더로 음식 카드를 만든 뒤, 북마크·리뷰 요약 배치에 id 를 합친다. 기존 단일 읽기 트랜잭션 안에서 처리한다.

## Technical Context

**Language/Version**: Kotlin 2.3, Java 21 · **Primary Dependencies**: Spring Boot 4.1, Spring Data JPA · **Storage**: MySQL (스키마 변경 없음) · **Testing**: Kotest BehaviorSpec + Testcontainers (`@IntegrationTest`) · **Project Type**: web-service (모듈러 모놀리스 `:common` ← `:api`) · **Performance Goals**: 홈 요청당 추가 쿼리 ≤ 2 · **Constraints**: 1.2 이하 응답 무변 · **Scale/Scope**: 섹션 1개

## Constitution Check

- I. Tests on Request — 계약 엔드포인트·집계 쿼리에 통합 테스트를 둔다. PASS
- II. Bounded Contexts — 리뷰 집계는 ReviewService, 음식 카드는 FoodService, 조합은 HomeService. PASS
- III. Layered Dependency — 리포지토리·프로젝션은 `common.domain.review`. PASS
- IV. Persistence Ownership — 리뷰 테이블은 리뷰 리포지토리만 조회(join food 는 필터 용도, 기존 `findReviewBotCandidatePage` 선례). PASS
- V. Content Language — 기존 `lang` 처리 재사용. PASS

## Project Structure

```text
api/src/main/kotlin/com/kbap/api/
├── core/ApiVersions.kt                 # HOME_TOP_RATED
├── core/config/WebConfig.kt            # 지원 버전 등록
├── food/FoodService.kt                 # getFoodSummariesInOrder
├── review/ReviewService.kt             # getTopRatedFoods, TopRatedFoodRating
└── home/{HomeController,HomeService,HomeResult,HomeResponse,HomeApi}.kt
common/src/main/kotlin/com/kbap/common/domain/review/ReviewJpaRepository.kt   # findTopRatedFoods + TopRatedFoodAggregate
api/src/test/kotlin/com/kbap/api/home/HomeTopRatedTest.kt (+ HomeTestSeed 확장)
```

## Complexity Tracking

없음.
