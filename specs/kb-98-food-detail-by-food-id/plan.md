# Implementation Plan: 음식 상세 조회 foodId 정합 (menuName → foodId)

**Branch**: `kb-98-food-detail-by-food-id` | **Date**: 2026-07-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/kb-98-food-detail-by-food-id/spec.md`

## Summary

음식 상세 조회의 조회 진입점(식별자)만 한국어 메뉴명 → 안정적 숫자 식별자 **foodId** 로 교체한다. 상세 산출 로직(언어 해석·번역 폴백·성분/위험도 계산·응답 스키마)은 전부 그대로 재사용한다. 엔드포인트는 `GET /api/v1/foods/{foodId}` 로 두고 기존 `GET /api/v1/foods/detail?menuName=` 은 제거한다. 미존재·소프트삭제·형식오류 foodId 는 모두 400(잘못된 요청) — 기존 `FoodErrorCode.NOT_FOUND` 가 이미 status=400 이라 매핑 신설 불필요.

**핵심 지렛대(최소 변경)**: 영속 어댑터는 기존 fetch-join 쿼리 `findByIdInWithAvoidanceSubstances(ids)` 를 단건(`listOf(id)`)으로 재사용한다 — 새 JPA 쿼리 불필요. 소프트삭제는 `BaseEntity` 의 `@SQLRestriction("status = 'ACTIVE'")` 가 자동 필터하므로 삭제 음식은 null → 400.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain
**Primary Dependencies**: Spring Boot 4.1 (web/data-jpa), Spring Data JPA, Kotest BehaviorSpec + SpringExtension
**Storage**: MySQL (통합 테스트는 MySQL 8.4 Testcontainers)
**Testing**: Kotest BehaviorSpec — 영속 어댑터 테스트(Testcontainers) + web MockMvc 통합 테스트
**Target Platform**: Linux 서버 (web bootJar `:app:api`)
**Project Type**: 모듈러 모놀리스 web 서비스 (ADR-0008)
**Performance Goals**: PK 단건 조회 — 상수시간, 목록 대비 무시 가능
**Constraints**: 응답 스키마·BaseResponse 봉투·`/api/v1` 규약 불변 (SC-003)
**Scale/Scope**: 단일 read 유스케이스 조회 정합. 프로덕션 데이터 없음(더미 10종).

## Constitution Check

*GATE: Phase 0 이전 통과, Phase 1 이후 재확인.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | test-writer 가 실패 테스트(foodId 성공·미존재·삭제·형식오류) 선작성 후 구현. |
| II. Bounded Contexts | PASS | 변경이 food 컨텍스트 + application:client + app:api 로 국한. 도메인 간 결합 추가 없음. |
| III. Layered Dependency Direction | PASS | port(core:food) ← 구현(infra:persistence), usecase(application:client)는 port 로만 조회, controller(app:api). 방향 보존. |
| IV. Persistence Encapsulation | PASS | JPA 는 infra:persistence 에 그대로. usecase 는 Food 도메인 + FoodRepository port 만 사용. |
| V. Domain Content Language Policy | PASS | lang 해석·ko 폴백·지원 목록 밖 코드 400 거절 로직 그대로 재사용(변경 없음). |

**위반 없음** → Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-98-food-detail-by-food-id/
├── plan.md              # 본 문서
├── research.md          # Phase 0 — 설계 결정
├── data-model.md        # Phase 1 — 읽기 포트/엔티티(스키마 변경 없음)
├── contracts/
│   └── food-detail-by-id.md   # Phase 1 — 엔드포인트 계약
├── quickstart.md        # Phase 1 — 검증 방법
└── tasks.md             # /speckit-tasks 산출(본 명령 아님)
```

### Source Code (repository root) — 변경 대상

```text
core/food/src/main/kotlin/com/meogo/core/food/
└── FoodRepository.kt                     # + fun findById(id: Long): Food?

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/
└── FoodRepositoryAdapter.kt              # findById 구현 — 기존 findByIdInWithAvoidanceSubstances(listOf(id)).firstOrNull()

application/client/src/main/kotlin/com/meogo/application/client/food/
├── dto/GetFoodDetailInput.kt             # menuName: String → foodId: Long
└── usecase/GetFoodDetailUseCase.kt       # findByKoreanName(name) → findById(foodId) (한 줄)

app/api/src/main/kotlin/com/meogo/app/api/food/
├── FoodDetailApi.kt                      # Swagger: menuName → {foodId} 재작성, 400 시맨틱
└── FoodDetailController.kt               # @GetMapping("/{foodId}") @PathVariable foodId: Long

app/api/src/main/kotlin/com/meogo/app/api/common/
└── GlobalExceptionHandler.kt             # + MethodArgumentTypeMismatchException → 400 BaseResponse (비숫자 foodId)

# 테스트 갱신(menuName → foodId, 시드 id 사용)
app/api/src/test/kotlin/com/meogo/app/api/food/
├── FoodDetailControllerTest.kt           # /foods/{1} 로 교체
├── FoodDetailDescriptionTest.kt          # 〃
├── FoodDetailLangTest.kt                 # 〃
├── FoodDetailLanguageErrorTest.kt        # 〃 (lang 400 경로 유지)
├── FoodDetailErrorTest.kt                # 미존재/삭제/비숫자 foodId → 400
└── (CorsConfigTest.kt: /foods/detail 프로브 URL → 유효 경로로 교체)
infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/
└── FoodRepositoryAdapterTest.kt          # + findById 성공/미존재/소프트삭제 케이스
```

**Structure Decision**: 기존 모듈 경계를 그대로 따른다. 새 모듈·새 클래스 없음 — port 메서드 1개 + 어댑터 구현 1개 + usecase/DTO/controller 시그니처 교체 + 예외 핸들러 1개.

## Complexity Tracking

위반 없음 — 해당 없음.
