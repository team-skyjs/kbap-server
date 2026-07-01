# Implementation Plan: 회피·주의 성분 카탈로그 DB 영속화 + 재료 매핑 (이슈 #15)

**Branch**: `006-ingredient-avoidance-mapping` | **Date**: 2026-07-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/006-ingredient-avoidance-mapping/spec.md`

## Summary

회피·주의 성분 카탈로그(81종)를 **MySQL DB(JPA 엔티티)로 영속화**하고 재료(`ingredient`)와 **외래키 매핑**한다. 테이블 3개: `avoidance_substance`(code·ko·9 번역 컬럼 비정규화) · `avoidance_substance_category`(성분↔분류 1~3, 다대다) · `ingredient_avoidance_substance`(재료↔성분 FK 매핑). **004 의 enum 은 제거하지 않고 유지**(시드 원천 + 타입 통화) — port 반환은 기존 enum `AvoidanceSubstance` 재사용해 충돌·중복 모델을 피하고, **조회 데이터는 DB 에서** 읽는다(D-READMODEL). enum↔DB 정합은 테스트로 강제. 도메인 모델/port 는 `:core:avoidance`(순수), JPA 엔티티/어댑터는 `:infra:persistence`, 음식→성분 합집합은 `:application:client`. **미지원 언어 코드 에러 처리는 본 기능에서 분리**(공유 `LanguageCode` 변경 → GitHub #18) — 본 기능은 ko 폴백만. 사용자 프로필·판정 등급·음식 상세조회 연결은 후속(#16·#17).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: `:core:avoidance` 신규 port 는 순수 인터페이스(Spring/JPA 없음, `api(:core:kernel)` 그대로 — `LanguageCode` 사용). `:infra:persistence`(spring-data-jpa, kotlin-jpa) 가 `:core:avoidance`·`:core:food` 를 `implementation` 의존(food 기존; **avoidance 의존 신규 추가**). Flyway 시드는 `:app:api`.

**Storage**: **MySQL**(prod) / H2(test) — 3 테이블. 번역은 컬럼 비정규화(D-TRANS). 매핑·분류는 FK/멤버십. Mongo 미사용.

**Testing**: Kotest `BehaviorSpec`(한국어). `:infra:persistence:test`(H2 어댑터) + `:application:client:test`(조합) + `:app:api:test`(enum↔DB 시드 정합·ArchUnit).

**Target Platform**: 모듈러 모놀리스 — `:app:api`(web, 조립·Flyway owner). batch 변경 없음.

**Project Type**: 기존 모듈에 port/엔티티/어댑터/조합/마이그레이션 추가 + 기존 enum 유지. 신규 Gradle 모듈 없음.

**Performance Goals**: 매핑·카탈로그 조회는 인덱스/IN 쿼리(O(결과행)). 연관은 id 스칼라 + 어댑터 분리 조회 → N+1 없음.

**Constraints**: 읽기 전용 reference(런타임 CRUD 없음). 코드·(성분,분류)·(재료,성분) 유일을 DB 제약으로, enum↔DB 정합을 테스트로 강제. 도메인 간 직접 의존 0(avoidance 는 ingredient 를 id 로).

**Scale/Scope**: 성분 81 × (1~3 분류) × (ko + 9 번역) + 재료 매핑 N. 시드는 enum 의 현재 mock 데이터, 확정 시 교체.

## Constitution Check

*GATE: Phase 0 전 통과 필수, Phase 1 후 재점검.*

> 헌법은 ADR-0008 현행 모듈 구조로 매핑해 평가.

| 원칙 | 평가 | 비고 |
|------|------|------|
| **I. Test-First (NON-NEGOTIABLE)** | ✅ 통과 예정 | 어댑터·조합·시드정합을 실패 테스트 우선. tasks 가 강제. |
| **II. Bounded Contexts** | ✅ 통과 | 카탈로그·매핑 소유=avoidance. ingredient 를 **id(Long)로만** 참조(food 타입 미import). 음식→성분 **조합은 application**. |
| **III. Layered Dependency Direction** | ✅ 통과 | port=`:core:avoidance`(kernel 만), 구현=`:infra:persistence`, 조립=부트앱 runtimeOnly. |
| **IV. Persistence Encapsulation** | ✅ 통과 | 신규 `@Entity`·Spring Data·Adapter 전부 `:infra:persistence`(패키지 `...avoidance`). `BaseEntity` 상속·소프트삭제. ArchUnit 회귀. |
| **V. Domain Content Language Policy** | ⚠️ **정합(조건부)** | 본 기능은 카탈로그를 **DB 에 저장**(원칙 V 본문 "DB 저장" 과 합치) + ko 원문 + 9 번역 + ko 폴백 충족. **단** 원칙 V 의 **예외 단서**("고정 taxonomy 는 enum 저장 허용")가 enum 을 명문화 — 본 기능은 enum 을 *유지*하면서 DB 도 둠(공존). 충돌 아님(둘 다 허용 범위). enum 최종 제거 시 단서 조정은 §Complexity·후속. |

**게이트 결론**: 진행 가능. enum·DB 공존은 원칙 V 양쪽(본문 DB + 예외 enum)에 모두 들어맞아 위반 없음. 정리(enum 제거·단서 조정)는 후속 governance.

## Project Structure

### Documentation (this feature)

```text
specs/006-ingredient-avoidance-mapping/
├── plan.md · research.md · data-model.md · quickstart.md
├── contracts/ingredient-avoidance-mapping.md
├── checklists/requirements.md
└── tasks.md   (/speckit-tasks 산출)
```

### Source Code (repository root)

```text
# 도메인 port — avoidance (순수, 기존 enum 유지)
core/avoidance/src/main/kotlin/com/meogo/core/avoidance/
├── AvoidanceSubstanceRepository.kt              # byCategory·translatedName·findByCodes
└── IngredientAvoidanceSubstanceRepository.kt    # findByIngredientIds
   (AvoidanceSubstance·AvoidanceCategory·AvoidanceSubstanceTranslations·AvoidanceCatalog = 유지)

# 영속 — :infra:persistence (패키지 ...avoidance)
infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/
├── AvoidanceSubstanceJpaEntity.kt               # code·korean_name·9 번역 컬럼
├── AvoidanceSubstanceCategoryJpaEntity.kt       # substance_id·category
├── IngredientAvoidanceSubstanceJpaEntity.kt     # ingredient_id·substance_id
├── AvoidanceSubstanceJpaRepository.kt / AvoidanceSubstanceCategoryJpaRepository.kt
├── IngredientAvoidanceSubstanceJpaRepository.kt
├── AvoidanceSubstanceRepositoryAdapter.kt       # port 구현, code↔enum, 번역 컬럼·ko 폴백
└── IngredientAvoidanceSubstanceRepositoryAdapter.kt
infra/persistence/src/test/kotlin/com/meogo/infra/persistence/avoidance/
├── AvoidanceSubstanceRepositoryAdapterTest.kt
└── IngredientAvoidanceSubstanceRepositoryAdapterTest.kt

# 조합 — application (US3)
application/client/src/main/kotlin/com/meogo/application/client/food/usecase/FoodAvoidanceSubstanceResolver.kt
application/client/src/test/kotlin/com/meogo/application/client/food/usecase/FoodAvoidanceSubstanceResolverTest.kt

# 마이그레이션·시드·정합 — app:api
app/api/src/main/resources/db/migration/V5__create_avoidance_catalog_and_mapping.sql
app/api/src/test/kotlin/com/meogo/app/api/avoidance/AvoidanceCatalogSeedSyncTest.kt   # enum↔DB 시드 정합

# 빌드
infra/persistence/build.gradle.kts   # implementation(project(":core:avoidance"))
```

**Structure Decision**: 신규 Gradle 모듈 없음. (1) `:core:avoidance` 에 순수 port 2개(enum 유지, Spring-free), (2) `:infra:persistence` 에 엔티티 3 + 리포지토리 3 + 어댑터 2 + `:core:avoidance` 의존 추가, (3) `:application:client` 에 음식→성분 합집합, (4) `:app:api` 에 Flyway V5(3 테이블 + 시드) + enum↔DB 정합 테스트. avoidance↔food 는 application 조합으로만 만남(도메인 간 직접 의존 0).

## Complexity Tracking

| 항목 | 사유 | 단순 대안 기각 이유 |
|------|------|------|
| enum·DB **공존**(이중 표현) | 사용자 결정 — enum 을 도메인 로직(#16 타입 참조)에서 쓸 수 있어 유지하며 DB 영속화. 과도기. | enum 즉시 제거(DB 단일 출처): 도메인 참조 가능성 + 헌법 단서 조정이 묶여 위험 → 후속 분리. 정합은 테스트로 드리프트 차단. |
| 번역 **컬럼 비정규화**(기존 정규화 패턴과 다름) | 정적 81×9 고정 언어 — 행 정규화 이점 없음, 조회 조인 0. 사용자 제안. | 행-per-언어(food 패턴): 정적 소량엔 과함. 일관성 < 단순성(정적 한정). data-model 에 의도적 이탈 기록. |

> 원칙 V 예외 단서 조정(enum 제거 시)·헌법 모듈명 동기화는 별도 `/speckit-constitution` 후속(governance, 본 plan 밖).

## 후속 (본 plan 범위 밖)

- **확정 콘텐츠** 수령 시 V5 시드 + enum 교체(정합 테스트가 일치 강제).
- **#18** 미지원 언어 코드 strict 에러(공유 `LanguageCode` — 음식/스캔 포함).
- **#16** 사용자 프로필 매칭 4단계 판정 — 본 port(성분·분류) 소비.
- **#17** 음식 상세조회 응답 연결 — `FoodAvoidanceSubstanceResolver` 소비.
- **enum 최종 정리**(제거 vs 존치) + 헌법 원칙 V 단서 조정.
