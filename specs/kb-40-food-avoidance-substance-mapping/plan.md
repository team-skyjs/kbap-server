# Implementation Plan: 음식별 81종 기피 성분 포함 여부·포함 확률 저장 (레시피/재료 모델 제거)

**Branch**: `kb-40-food-avoidance-substance-mapping` | **Date**: 2026-07-04 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-40-food-avoidance-substance-mapping/spec.md`

## Summary

음식(Food) 도메인이 레시피/재료(`FoodIngredient`·`Ingredient`)를 통해 기피 성분을 간접 도출하던 구조를, **음식이 81종 기피 성분(부분집합)을 포함 확률(1~100%)과 함께 직접 보유**하는 구조로 바꾼다. 재료 관련 테이블·엔티티·도메인·포트를 전부 제거하고, 음식↔기피성분 직접 연결(`food_avoidance_substance`)을 신설한다.

**핵심 제약(사용자 지시)**: 기존 음식 상세 조회 API(`GET /api/v1/foods/detail`)의 **응답 필드(JSON 계약)를 동결**한다. 응답의 `ingredients` 배열과 각 항목의 `{name, iconRef, inclusionPercent, riskStatus}` 필드명·타입을 그대로 유지하되, **데이터 원천만 재료 → 포함 기피 성분으로 교체**한다. 특히 `inclusionPercent`(Int) 키가 그대로 "포함 확률(1~100)"을 담아 프런트 계약 변경 없이 의미만 이관된다. 내부 application DTO 는 실체에 맞게 개명하고 controller 매핑에서 동결된 외부 키로 변환한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1 (web/validation/data-jpa), Flyway(+flyway-mysql), springdoc-openapi

**Storage**: MySQL(prod) / H2(test, create-drop, Flyway off) — 스키마 owner=`:app:api`

**Testing**: JUnit5 platform + Kotest `BehaviorSpec`(given/when/then 한국어), `kotest-extensions-spring`(MockMvc·H2 통합)

**Target Platform**: Linux server (web bootJar `:app:api`)

**Project Type**: web-service (Gradle 멀티모듈 모듈러 모놀리스, ADR-0008)

**Performance Goals**: 음식 상세 조회 1회 = 포함 성분 개수와 무관한 상수 횟수 조회(N+1 없음; ko=fetch join 1회, 비-ko=+번역 batch)

**Constraints**: 도메인 ORM-free·완전 Spring-free 유지; 컨텍스트 간 직접 의존 금지(코드/ID 참조); 외부 API JSON 계약 무변경

**Scale/Scope**: 음식(메뉴) 도메인·영속·음식상세 유스케이스/컨트롤러 한정. 메뉴 스캔·회피 프로필·기피성분 카탈로그(81종) 자체는 불변.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 준수 방법 | 판정 |
|------|-----------|------|
| **I. Test-First** | 도메인(`Food`·`FoodAvoidanceSubstance`)·영속(H2)·유스케이스·컨트롤러 각 층에서 실패 테스트 선작성 → Green → Refactor. tasks 에서 층별 테스트 태스크를 구현 앞에 배치. | ✅ |
| **II. Bounded Contexts** | `:core:food` 는 `:core:avoidance` 를 의존/‐import 하지 않는다. Food 는 기피 성분을 **`substanceCode: String`(코드)로만** 참조(enum·객체 미참조). food+avoidance 조합은 **`:application:client` 유스케이스에서만** 수행. | ✅ |
| **III. Layered Dependency** | 의존 방향 불변(app→application→core→kernel). 신규 junction 엔티티는 `:infra:persistence`. 유스케이스는 port(`FoodRepository`·`AvoidanceSubstanceRepository`)로만 접근. | ✅ |
| **IV. Persistence Encapsulation** | 모든 JPA(신규 `FoodAvoidanceSubstanceJpaEntity` 포함)는 `:infra:persistence`. 도메인 ORM-free. 삭제 대상 엔티티/리포지토리도 이 모듈 안. | ✅ |
| **V. Language Policy** | 성분 표시명은 `avoidance_substance`의 ko 원문+9개 번역(JSON)에서 `displayName(lang)`으로 해석(ko 폴백). 미지원 언어코드 → 기존 `LanguageResolver`/`LanguageCode.from` 이 400. 81종은 고정 reference taxonomy(식별자 enum + DB 단일출처) 그대로. 포함 확률은 콘텐츠 번역 대상 아님. | ✅ |
| **추가: 도메인/영속 모델 직접 노출 금지** | 응답은 별도 DTO(`FoodDetailResponse`)로 감싸고 도메인/엔티티를 직접 반환하지 않음(현행 유지). | ✅ |

**결과**: 위반 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-40-food-avoidance-substance-mapping/
├── plan.md              # (this file)
├── research.md          # Phase 0 — 설계 결정(참조 키·확률 시드·계약 동결·삭제 순서)
├── data-model.md        # Phase 1 — 도메인/엔티티/스키마
├── quickstart.md        # Phase 1 — 검증 시나리오
├── contracts/
│   └── food-detail-api.md   # 동결된 GET /api/v1/foods/detail 계약(+의미 재정의)
├── checklists/requirements.md
└── tasks.md             # /speckit-tasks 산출(이 명령이 만들지 않음)
```

### Source Code (repository root) — 영향 범위

```text
core/food/src/main/kotlin/com/meogo/core/food/
├── Food.kt                       # 변경: ingredients → avoidanceSubstances, ingredientsByInclusion → avoidanceSubstancesByProbability
├── FoodAvoidanceSubstance.kt     # 신규: (substanceCode: String, inclusionProbability: Int 1..100) 값 객체
├── FoodRepository.kt             # 변경: findIngredientNameTranslations 제거
├── FoodIngredient.kt             # 삭제
└── Ingredient.kt                 # 삭제

core/avoidance/src/main/kotlin/com/meogo/core/avoidance/
└── IngredientAvoidanceSubstanceRepository.kt   # 삭제(포트)

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/
├── FoodJpaEntity.kt              # 변경: foodIngredients → foodAvoidanceSubstances(OneToMany)
├── FoodAvoidanceSubstanceJpaEntity.kt   # 신규: table food_avoidance_substance
├── FoodJpaRepository.kt          # 변경: fetch join → foodAvoidanceSubstances
├── FoodRepositoryAdapter.kt      # 변경: ingredientNameTranslation 의존/메서드 제거
├── FoodIngredientJpaEntity.kt / IngredientJpaEntity.kt          # 삭제
├── IngredientJpaRepository.kt                                    # 삭제
├── IngredientNameTranslationJpaEntity.kt / ...JpaRepository.kt   # 삭제
infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/
├── IngredientAvoidanceSubstanceJpaEntity.kt / ...JpaRepository.kt   # 삭제
└── IngredientAvoidanceSubstanceRepositoryAdapter.kt                 # 삭제

application/client/src/main/kotlin/com/meogo/application/client/food/
├── usecase/GetFoodDetailUseCase.kt   # 변경: 재료→포함 기피성분 원천, avoidance 카탈로그로 표시명 해석
├── usecase/MockIngredientRiskMarker.kt → MockAvoidanceRiskMarker.kt   # 변경/개명: 성분 코드 기준 mock 위험도
├── usecase/FoodAvoidanceSubstanceResolver.kt   # 삭제(구 재료 경유 resolver) 또는 카탈로그 표시명 resolver 로 대체
└── dto/GetFoodDetailResult.kt        # 변경: ingredients/IngredientView → avoidanceSubstances/AvoidanceSubstanceView(내부만)

app/api/src/main/kotlin/com/meogo/app/api/food/
├── FoodDetailResponse.kt         # 필드 동결(ingredients[]{name,iconRef,inclusionPercent,riskStatus}); from() 매핑 내부 개명 반영; @Schema 설명만 갱신
├── FoodDetailController.kt / FoodDetailApi.kt   # 무변경(계약 동결)

app/api/src/main/resources/db/migration/
└── V7__replace_recipe_with_food_avoidance_substance.sql   # 신규: 테이블 생성 + 시드 이행 + 재료 테이블 DROP
```

**Structure Decision**: 기존 모듈러 모놀리스 레이아웃을 그대로 사용한다. 신규 파일은 소유 계층 규칙에 따라 배치(도메인 값 객체→`:core:food`, JPA→`:infra:persistence`, 마이그레이션→`:app:api`). 새 모듈·새 컨테이너는 만들지 않는다.

## Complexity Tracking

> 해당 없음 — Constitution Check 위반 없음.
