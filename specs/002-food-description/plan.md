# Implementation Plan: 음식 상세 조회에 음식 설명(간단·자세) 추가

**Branch**: `002-food-description` | **Date**: 2026-06-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/002-food-description/spec.md`

## Summary

음식 상세 조회(`GET /api/v1/foods/detail`) 응답에 **간단 설명(brief)·자세한 설명(detailed)** 2종을 더한다(가산적 변경). 두 설명은 기존 음식명(name) 다국어 read-model을 그대로 따른다 — **ko 원문은 `food` 테이블 컬럼**, **9개 대상 언어는 번역 테이블**, 조회 시 `lang` 번역본 + 미지원/미지정/번역부재 시 **ko 폴백**(설명별·필드별 독립). 기존 동작(매칭 키·400 규칙·재료·mock 위험도)은 불변.

기술 접근: 도메인 `Food`에 `briefDescription`·`detailedDescription`(둘 다 non-null) 추가, 번역은 **단일 `food_description_translation` 테이블 + `kind`(BRIEF/DETAILED) 판별 컬럼**으로 저장. 영속(JPA 엔티티·번역 리포지토리·adapter)은 중앙 `:meogo-api:persistence`(ADR-0006), 도메인 port 확장은 `:meogo-api:food`, 유스케이스·결과는 `:meogo-api:application`, 응답 DTO는 `:meogo-api:presentation`. 신규 Flyway **V4**가 `food` 컬럼 2개 + 번역 테이블 + seed를 추가한다(V1~V3 불변).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1 (web, validation, data-jpa), Flyway(+flyway-mysql), springdoc-openapi. 테스트: JUnit 5 platform + Kotest(`BehaviorSpec`) + `kotest-extensions-spring` + spring-boot-starter-test

**Storage**: MySQL(prod/local) + H2(test, `create-drop`, flyway off). JPA/MySQL만 사용(MongoDB 미사용)

**Testing**: `./gradlew test` — 도메인 단위(`:meogo-api:food`), 영속(RepositoryAdapter, H2, `:meogo-api:persistence`), application 단위(use case, fake repo), web(`@SpringBootTest` + `@AutoConfigureMockMvc`, `:meogo-api:presentation`)

**Target Platform**: Linux server (web bootJar `:meogo-api:presentation`, 진입점 `com.meogo.api.MeogoApiApplication`)

**Project Type**: Web service (Gradle 멀티모듈)

**Performance Goals**: 동기 조회, 사용자 체감 즉시. 외부 호출 없음. 설명 번역 조회는 음식명 번역과 동일하게 단일 lang 1쿼리(N+1 회피)

**Constraints**: 외부 LLM/네트워크 없음. 단일 읽기 트랜잭션. 도메인/영속 모델 직접 노출 금지(DTO 매핑). 도메인 모듈 Spring/ORM-free 유지. 설명 컬럼은 MySQL 길이 명시(`VARCHAR`, TEXT 미사용)

**Scale/Scope**: seed 음식 소수(데모). 변경 엔드포인트 1개(`foods/detail`), 도메인 컨텍스트 1개(food). 설명 2종 × (ko 원문 + 9개 언어)

### 해소된 결정 (spec clarify 2026-06-29 + plan)

- **설명 2종**: 간단(brief)·자세(detailed). 둘 다 한 응답에 동시 포함.
- **필수성**: 두 설명 ko 원문 non-null — `food` 컬럼 `NOT NULL`, 도메인 `require(notBlank)`, 응답 필드 non-null `String`.
- **길이**: 간단 `VARCHAR(255)`(≤255자), 자세 `VARCHAR(1024)`(≤1024자). 원문·번역 컬럼 동일. TEXT 미사용(길이 명시 규약).
- **번역 저장 구조(plan 결정)**: **단일 `food_description_translation`(food_id, kind, lang_code, content)** + `kind ∈ {BRIEF, DETAILED}` 판별 컬럼, `UNIQUE(food_id, kind, lang_code)`. 종류별 별도 테이블(2개) 대신 1개 — "한 행 = 한 번역 문자열" 모델 유지·종류 확장 용이·테이블 수 절감. ko 는 번역 테이블에 저장 안 함(원문 컬럼에서).
- **폴백**: 설명별·필드별 **독립**. 간단 번역 부재 시 간단만 ko, 자세·음식명·재료명 무영향.
- **마이그레이션**: 신규 **V4**. 기존 seed 행(V3) 보존을 위해 컬럼을 nullable 로 추가 → seed UPDATE/번역 INSERT → `NOT NULL` 로 조이는 순서(아래 data-model).
- **콘텐츠(mock)**: 간단·자세 ko/9개 언어 텍스트는 데모 placeholder(실제 편집 콘텐츠는 기획 확정 시 교체 — spec Dependencies).

## Constitution Check

*GATE: Phase 0 전 통과 필수. Phase 1 설계 후 재점검.*

| 원칙 | 적용 | 상태 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | 도메인(Food 불변·notBlank) → 영속(설명 컬럼·번역 adapter, H2) → application(use case 폴백) → web(MockMvc 응답 필드) 각 단계 실패 테스트 먼저. Kotest `BehaviorSpec`(given/when/then 한국어) | ✅ PASS (tasks 강제) |
| **II. Bounded Contexts** | `food` 단일 컨텍스트만 변경. 컨텍스트 조합 없음 | ✅ PASS |
| **III. Layered Dependency Direction** | `presentation → application → food`. persistence 가 도메인 port 구현, presentation 이 `runtimeOnly` 조립. application 은 port 인터페이스에만 의존 | ✅ PASS |
| **IV. Persistence Encapsulation** | JPA Entity·Spring Data·adapter 를 중앙 `:meogo-api:persistence`(`com.meogo.api.persistence.food`)에 둠. application/presentation 은 import 안 함. 도메인은 model+port 만 | ✅ PASS (헌법 IV 문구 "도메인 내부 패키지"↔ADR-0006 중앙 모듈 차이는 001과 동일한 의도된 진화, 원칙 의도 충족) |
| **V. Domain Content Language Policy** | 설명 ko 원문 + 9개 대상 언어 저장, 응답은 `lang` 한 언어(미지원→ko 폴백). 음식명·재료명과 동일 정책 | ✅ PASS |

**Additional Constraints**: 스택/외부호출 없음/응답 DTO 노출 금지 — 충족. **GATE 통과.**

## Project Structure

### Documentation (this feature)

```text
specs/002-food-description/
├── plan.md              # 본 파일
├── research.md          # Phase 0 — 번역 저장 구조·NOT NULL 마이그레이션 결정 근거
├── data-model.md        # Phase 1 — 엔티티·컬럼·번역 테이블·V4 마이그레이션 절차
├── quickstart.md        # Phase 1 — 로컬 확인 시나리오
├── contracts/
│   └── food-detail-api.md   # Phase 1 — description 추가된 응답 계약
└── checklists/
    └── requirements.md  # spec 품질 체크리스트(완료)
```

### Source Code (repository root) — 변경 대상

```text
meogo-api/
├── food/                         # 도메인(ORM-free) — port·model 확장
│   ├── .../com/meogo/api/food/Food.kt                 # +briefDescription, +detailedDescription (non-null), create/reconstitute 갱신
│   ├── .../com/meogo/api/food/FoodDescriptionKind.kt  # (신규) enum BRIEF, DETAILED
│   ├── .../com/meogo/api/food/FoodRepository.kt       # +findFoodDescriptionTranslations(foodId, lang): Map<FoodDescriptionKind,String>
│   └── test/.../food/FoodTest.kt                       # 설명 non-null/notBlank 불변 테스트 보강
├── application/
│   ├── .../application/food/dto/GetFoodDetailResult.kt    # +briefDescription, +detailedDescription
│   ├── .../application/food/usecase/GetFoodDetailUseCase.kt # 설명 lang 해석 + 독립 ko 폴백
│   └── test/.../food/usecase/GetFoodDetailUseCaseTest.kt   # 폴백 시나리오 보강
├── persistence/
│   ├── .../persistence/food/FoodJpaEntity.kt                       # +brief_description, +detailed_description 컬럼·toDomain/from
│   ├── .../persistence/food/FoodDescriptionTranslationJpaEntity.kt # (신규) food_id, kind, lang_code, content
│   ├── .../persistence/food/FoodDescriptionTranslationJpaRepository.kt # (신규) findByFoodIdAndLangCode → 두 kind 행
│   ├── .../persistence/food/FoodRepositoryAdapter.kt               # findFoodDescriptionTranslations 구현
│   └── test/.../food/FoodRepositoryAdapterTest.kt                  # 번역 조회·폴백 영속 테스트
└── presentation/
    ├── .../presentation/food/FoodDetailResponse.kt   # +briefDescription, +detailedDescription (+Swagger)
    ├── .../presentation/food/FoodDetailApi.kt        # Swagger 예시 갱신
    ├── src/main/resources/db/migration/V4__add_food_description.sql  # (신규) 컬럼 2개 + 번역 테이블 + seed
    └── test/.../food/FoodDetail*Test.kt + FoodTestSeed.kt           # 응답 필드·seed 보강
```

**Structure Decision**: 기존 멀티모듈 경계를 그대로 사용하는 가산적 변경. 새 모듈 없음. 변경은 food 도메인 + 그 영속/유스케이스/웹 표현에 한정. 스키마 owner 는 `:meogo-api:presentation`(V4).

## Complexity Tracking

> Constitution Check 위반 없음 — 비움.
