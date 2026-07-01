# Implementation Plan: 회피·주의 성분 — 식별자 enum + 도메인 어그리게이트 분리

**Branch**: `007-avoidance-substance-aggregate` | **Date**: 2026-07-02 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/007-avoidance-substance-aggregate/spec.md`

## Summary

data 를 이고 있던 `AvoidanceSubstance` enum(81종 · koName · categories)을 **① 데이터 없는 식별자 enum `AvoidanceSubstanceCode`** 와 **② DB 에서 복원되는 도메인 어그리게이트 `AvoidanceSubstance`(id·code·koreanName·translations·categories + `displayName(lang)`/`belongsTo(category)`)** 로 분리한다. port 는 어그리게이트를 반환하고, `translatedName` 은 어댑터에서 제거되어 `substance.displayName(lang)` 로 이동한다(Finding ① — KO 도 DB 조회). JPA 는 분류를 도메인 enum 직접 사용에서 **String 저장 + 경계 변환**으로 정렬하고, `toDomain()` 이 번역 컬럼·분류 멤버십을 모아 어그리게이트를 복원한다(배치 조회로 N+1 회피). 전이 유물 `AvoidanceCatalog`·`AvoidanceSubstanceTranslations` 를 제거하고, 시드 정합 불변식을 **코드 집합 일치**로 축소한다. 헌법 원칙 V 예외 단서는 후속 `/speckit-constitution` 으로 조정한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM (Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1(data-jpa), Kotest(BehaviorSpec), ArchUnit. 신규 라이브러리 없음.

**Storage**: MySQL(prod) / H2(test). 기존 테이블 `avoidance_substance`(code + 9개 번역 컬럼 + korean_name)·`avoidance_substance_category`(substance_id, category) 재사용. **스키마 변경 없음**(category 컬럼은 이미 VARCHAR STRING — 엔티티 필드 타입만 변경).

**Testing**: JUnit5 platform + Kotest `BehaviorSpec`(given/when/then 한국어). 도메인 단위(순수) · 영속 H2 슬라이스 · ArchUnit.

**Target Platform**: Linux server (web bootJar `:app:api`).

**Project Type**: 모듈러 모놀리스(단일 저장소, 멀티모듈). 대상 모듈: `:core:avoidance`(도메인) · `:infra:persistence`(영속) · `:app:api`(시드정합·ArchUnit 테스트).

**Performance Goals**: 조회 쿼리 수가 성분 개수와 무관한 **상수 단계**(N+1 없음) — 배치 in-절 조회.

**Constraints**: 도메인 완전 Spring-free·ORM-free 유지. 도메인 불변(val, private copy). 관측 가능한 동작 보존(성분 목록·분류 소속·표시명·재료↔성분 매핑).

**Scale/Scope**: 고정 카탈로그 81종 · 3분류 · 9개 대상 언어. 순수 리팩터(신규 유스케이스 없음).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| **I. Test-First** | ✅ 강화 | 리팩터는 기존 테스트를 회귀 가드로 두고, 어그리게이트·toDomain·시드정합 테스트를 먼저 실패 작성 후 통과시킨다. |
| **II. Bounded Contexts** | ✅ 유지 | 성분 코드/어그리게이트/분류는 `avoidance` 컨텍스트 소유. 타 컨텍스트는 코드로만 참조(FoodAvoidanceSubstanceResolver 는 그대로 avoidance 타입 소비). 도메인 간 직접 의존 없음. |
| **III. Layered Dependency Direction** | ✅ 유지 | 어그리게이트·코드 enum·port 는 `:core:avoidance`(→ `:core:kernel` 만). `:infra:persistence` 가 도메인 `implementation` 의존해 port 구현. 방향 불변. |
| **IV. Persistence Encapsulation** | ✅ 강화 | JPA 엔티티·`toDomain()`·adapter 는 `:infra:persistence` 에 집약. 도메인은 ORM-free. category 를 도메인 enum 직접 매핑 → String 저장 + 경계 변환으로 **관례 정렬**(RiskLevel/FoodDescriptionKind 패턴). |
| **V. Domain Content Language Policy** | ⚠️ 후속 조정 | enum 이 더 이상 데이터를 이지 않으므로 "고정 reference taxonomy = 공유 컴파일 enum 저장" 예외의 **전제**가 바뀐다. ko 원문 + 9개 번역·ko 폴백·콘텐츠↔UI 분리는 그대로 충족(이제 DB 단일 출처). 문구 조정은 `/speckit-constitution`(MINOR) 로 별도 처리 — 위반 아님, governance 동기화. |

**Gate 결과: PASS** (원칙 V 는 위반이 아니라 문구 동기화 후속. Complexity Tracking 불필요.)

## Project Structure

### Documentation (this feature)

```text
specs/007-avoidance-substance-aggregate/
├── plan.md              # This file
├── research.md          # Phase 0 — 설계 결정(어그리게이트 복원 전략·시드 원천·enum 축소)
├── data-model.md        # Phase 1 — 코드 enum·어그리게이트·JPA 엔티티·변환
├── contracts/
│   └── ports.md         # Phase 1 — 도메인 port 시그니처(before/after)
├── quickstart.md        # Phase 1 — 검증 절차(테스트·회귀)
├── checklists/
│   └── requirements.md  # spec 품질 체크리스트(완료)
└── tasks.md             # Phase 2 (/speckit-tasks 에서 생성 — 이 명령 아님)
```

### Source Code (repository root)

```text
core/avoidance/src/main/kotlin/com/meogo/core/avoidance/
├── AvoidanceSubstanceCode.kt          # (신규) 데이터 없는 식별자 enum 81종
├── AvoidanceSubstance.kt              # (변경) enum → @AggregateRoot 어그리게이트
├── AvoidanceCategory.kt               # (유지) 값 enum 3종
├── AvoidanceSubstanceRepository.kt    # (변경) 어그리게이트 반환, translatedName 제거
├── IngredientAvoidanceSubstanceRepository.kt  # (변경) Map<Long, Set<AvoidanceSubstance 어그리게이트>>
├── AvoidanceCatalog.kt                # (삭제) 전이 유물 — DB 어그리게이트로 대체
└── AvoidanceSubstanceTranslations.kt  # (삭제) 전이 유물 — 번역 원천은 DB/JSON

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/
├── AvoidanceSubstanceJpaEntity.kt          # (변경) toDomain() 어그리게이트 복원
├── AvoidanceSubstanceCategoryJpaEntity.kt  # (변경) category: String 저장
├── AvoidanceSubstanceRepositoryAdapter.kt  # (변경) translatedName 제거·어그리게이트 조립
├── IngredientAvoidanceSubstanceRepositoryAdapter.kt  # (변경) 어그리게이트 조립
└── *JpaRepository.kt                        # (유지/보강) 배치 in-절 조회

app/api/src/test/kotlin/com/meogo/app/api/
├── avoidance/AvoidanceCatalogSeedSyncTest.kt   # (변경) 코드 집합 일치로 축소
└── architecture/ModuleBoundaryTest.kt          # (보강) 코드 enum 무데이터·경계 회귀
```

**Structure Decision**: 기존 모듈러 모놀리스 구조를 그대로 쓴다. 신규 모듈·패키지 없음. 변경은 `:core:avoidance`(도메인 모델·port) · `:infra:persistence`(엔티티·어댑터·변환) · `:app:api`(시드정합·ArchUnit 테스트) 3개 모듈에 국한된다.

## Complexity Tracking

> Constitution Check 통과 — 정당화할 위반 없음. (원칙 V 는 위반이 아니라 후속 문구 동기화.)
