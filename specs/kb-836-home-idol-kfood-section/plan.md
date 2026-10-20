# Implementation Plan: idol-picks 전용 API

**Branch**: `feat/kb836-home-idol-kfood-section` | **Date**: 2026-10-21 | **Spec**: [spec.md](spec.md)

## Summary

Flyway 하나로 `food_idol_video` 생성 + 시드 36행(이름 기준 INSERT … SELECT). `IdolPickService` 가 ACTIVE 행 → 음식별 position 최소 → D7 정렬 → 기존 `getReadyFoodSummariesInOrder` 로 카드 생성 → 북마크·평점 배치(`SelectedFoodsService` 패턴) → 항목에 `video` 를 붙인다. `FoodController` 에 `GET /idol-picks` 1개(version 속성 없음). 홈·상세 무변.

## Technical Context

**Language/Version**: Kotlin 2.3, Java 21 · **Dependencies**: Spring Boot 4.1, Spring Data JPA · **Storage**: MySQL, 신규 테이블 1 · **Testing**: Kotest BehaviorSpec + Testcontainers (`@IntegrationTest`) · **Performance Goals**: 요청당 쿼리 = 매핑 전체 조회 1 + 음식 IN 1 + 북마크 1 + 평점 1 (+회원 기피 성분) · **Scale**: 매핑 수십 행, 페이지네이션 없음

## Constitution Check

- I. Tests — 계약 엔드포인트 통합 테스트 + R-19. PASS
- II. Bounded Contexts — food 기능 안에서 조립, 북마크·리뷰는 각 서비스 호출. PASS
- III. Layered Dependency — 엔티티·리포지토리 `common.domain.food`. PASS
- IV. Persistence Ownership — `food_idol_video` 는 자기 리포지토리만 조회. PASS
- V. Content Language — 기존 `lang` 처리 재사용. PASS

## Project Structure

```text
api/src/main/resources/db/migration/V2026.10.21.02.20.22__food_idol_video_table.sql
common/src/main/kotlin/com/kbap/common/domain/food/model/FoodIdolVideo.kt      # + IdolVideoPlatform
common/src/main/kotlin/com/kbap/common/domain/food/FoodIdolVideoJpaRepository.kt
api/src/main/kotlin/com/kbap/api/food/
├── IdolPickService.kt          # 대표 규칙 + 조립
├── IdolPicksResponse.kt        # IdolPicksResponse / IdolPickResponse(@JsonUnwrapped) / IdolPickVideoResponse
├── FoodRequests.kt             # FoodIdolPicksRequest(lang)
└── FoodController.kt, FoodApi.kt
api/src/test/kotlin/com/kbap/api/food/IdolPickControllerTest.kt (+ HomeTestSeed.seedIdolVideo, TestTables)
```

## Complexity Tracking

없음.
