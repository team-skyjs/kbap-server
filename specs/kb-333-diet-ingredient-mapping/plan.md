# Implementation Plan: diet 카테고리별 회피 재료 매핑 조회

**Branch**: `kb-333-diet-ingredient-mapping` | **Date**: 2026-08-14 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-333-diet-ingredient-mapping/spec.md`

## Summary

15종 diet 카테고리(비건·할랄·글루텐 프리 등)마다 회피 재료 집합을 서버에 정의하고, 조회 1회로 전체 카테고리와 매핑 재료(id·이름)를 돌려주는 API `GET /api/ingredients/diets` 를 신설한다(신규 기능 패키지 없이 기존 `ingredient` 기능 패키지·컨트롤러에 통합 — 사용자 결정 2026-08-14). 매핑은 DB 가 아니라 **`DietCategory` enum 상수(코드 + 한국어 표시명 + `IngredientCode` 집합)** 로 소유한다 — 기획 표의 재료 번호(시드 1-based 행 번호)는 설계 시점에 코드로 변환해 박제하고, 번호표↔enum 정합은 시드 SQL 파싱 테스트(기존 `IngredientCatalogSeedSyncTest` 패턴)가 배포 전에 강제한다. 재료 id·다국어 이름은 기존 `ingredients` 카탈로그(DB)에서 코드로 조회해 채운다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1(web·validation·data-jpa), springdoc-openapi. 신규 외부 의존 없음.

**Storage**: MySQL — 기존 `ingredients` 테이블 재사용(읽기 전용). **신규 테이블·Flyway 마이그레이션 없음**(매핑은 코드 상수).

**Testing**: Kotest BehaviorSpec(given/when/then 한국어) + `@SpringBootTest`·MockMvc + MySQL Testcontainers. 매핑 정합은 시드 SQL 파싱 단위 테스트.

**Target Platform**: `:api` bootJar (linux 서버, 프로필 local/dev/staging/prod)

**Project Type**: web-service (기존 모듈러 모놀리스 내 기능 추가)

**Performance Goals**: 단일 조회 요청 = `ingredients` 전건(81행) 1쿼리 + 메모리 그룹핑 — 추가 튜닝 불요.

**Constraints**: `X-API-Version` 헤더 필수(전 `/api/**` 공통), JWT 보호 경로 등록 필수(FR-007), `lang` 필수·미지원 코드 en 폴백(헌법 V).

**Scale/Scope**: 카테고리 15종 × 재료 최대 41종. 엔드포인트 1개, 신규 파일 ~6개(+테스트 2개).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | tasks 단계에서 매핑 정합 테스트·MockMvc 통합 테스트를 구현보다 먼저 작성(Red 확인) 후 구현한다. |
| II. Bounded Contexts | PASS | 신규 코드는 기존 `com.kbap.api.ingredient` 기능 패키지 + `IngredientCode`(common.domain.ingredient) 참조뿐. 도메인 간 신규 의존 없음 — api 기능 패키지는 도메인 방향 맵 대상이 아니다. |
| III. Layered Dependency Direction | PASS | `:api` → `:common` 단방향만 사용. `DietCategory` 는 api 밖(배치·인프라)이 컴파일 의존하지 않으므로 `:common` 이 아니라 `:api` 에 둔다(배치 기준 충족 — research R1). |
| IV. Persistence Ownership | PASS | 신규 엔티티·리포지토리 없음. 기존 `IngredientJpaRepository` 를 api 서비스가 직접 사용(허용 패턴), `@Transactional(readOnly = true)` 명시. |
| V. Domain Content Language Policy | PASS (예외 1건 정당화) | 재료명은 기존 DB 단일 출처(ko 원문+9개 번역, `displayName` 폴백 규칙) 재사용. `lang` 필수(`@field:NotBlank`)·미지원 코드 en 폴백은 `LanguageCode.from` 재사용. **카테고리 표시명은 taxonomy DB-단일출처 패턴을 따르지 않고 enum 상수에 둔다** — 번역 리소스·소프트삭제·운영 편집이 전무한 코드+한국어 표시명뿐이라 DB 이관 이득이 없다(research R2, 스펙 Assumptions 합치). 다국어가 생기면 avoidance 카탈로그 패턴으로 승격한다. |

**Post-Phase 1 재평가**: PASS — 설계 산출물(data-model·contracts)이 위 판정을 바꾸지 않는다. Complexity Tracking 해당 없음.

## Project Structure

### Documentation (this feature)

```text
specs/kb-333-diet-ingredient-mapping/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output (번호→코드 변환표 = 매핑 단일 출처)
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── diet-api.md      # GET /api/diets 계약
└── tasks.md             # Phase 2 output (/speckit-tasks — 이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/ingredient/
├── IngredientApi.kt             # 수정 — diets 오퍼레이션 swagger 문서 추가
├── IngredientController.kt      # 수정 — GET /api/ingredients/diets 핸들러 추가
├── IngredientQueryService.kt    # 수정 — getDietIngredientMappings(lang) 추가
├── DietCategory.kt              # 신규 — 카테고리 enum: 코드·한국어 표시명·IngredientCode 집합
├── DietListRequest.kt           # 신규 — lang 필수 요청 DTO
└── DietListResponse.kt          # 신규 — 카테고리·재료(id·이름) 응답 DTO

api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt   # 수정 — JWT 보호 경로에 /api/ingredients/diets 추가

api/src/test/kotlin/com/kbap/api/ingredient/
├── DietCategoryMappingSyncTest.kt  # 신규 — 기획 번호표 ↔ enum 매핑 정합 (시드 SQL 행 순서 기반)
└── IngredientControllerTest.kt     # 수정 — diets 시나리오 추가: 응답 구조·lang 폴백·401·400
```

**Structure Decision**: 신규 기능 패키지를 만들지 않고 기존 `com.kbap.api.ingredient` 에 통합한다(사용자 결정 — diet 매핑은 재료 카탈로그의 분류 뷰이므로 ingredient 기능의 일부로 본다). 엔드포인트도 `/api/ingredients/diets` 하위 리소스로 둔다. `:common` 변경 없음(배치·인프라가 소비하지 않으므로 — 헌법 III 배치 기준).

## Complexity Tracking

해당 없음 — Constitution Check 위반 없음.
