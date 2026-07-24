# Implementation Plan: 기피 성분 데이터 구조 정리 — 미사용 분류 제거 + 다국어 저장 단순화

**Branch**: `009-avoidance-schema-refactor` | **Date**: 2026-07-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-avoidance-schema-refactor/spec.md`

## Summary

기피 성분(avoidance) 카탈로그의 영속 구조를 두 축으로 정리한다.

1. **분류 카테고리 제거**: 조회·활용 소비자가 없는 3분류(`AvoidanceCategory`)를 도메인·영속·스키마·아키텍처 테스트에서 완전히 걷어낸다. `avoidance_substance_category` 테이블과 조인 로직, `byCategory` port·구현, 도메인 `categories`/`belongsTo`, ArchUnit "분류 저장 형식" 규칙을 제거한다.
2. **번역 저장 단순화**: `avoidance_substance` 의 언어별 개별 컬럼 9종(`name_en`…`name_es`)을 단일 `translations` **JSON 컬럼**으로 통합한다. `korean_name` 컬럼은 전용 컬럼으로 유지하고, JSON 은 비-`ko` 번역만 담는다(도메인 `koreanName` + `translations: Map<LanguageCode,String>` 구조 불변).

관찰 가능한 사용자 동작·API 계약은 변하지 않는다(분류는 원래 미노출, `displayName(lang)` 결과 동일). 저장소는 이미 배포 운영 중이므로 새 **V6 forward 마이그레이션**으로 데이터를 보존·이전한다(V5 는 불변).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 toolchain

**Primary Dependencies**: Spring Boot 4.1(Hibernate 7, data-jpa), Flyway(+flyway-mysql), jackson-module-kotlin(영속 JSON 직렬화)

**Storage**: MySQL(prod, 네이티브 `JSON` 컬럼) + H2(test, `ddl-auto=create-drop`, Flyway off). MongoDB 무관.

**Testing**: Kotest `BehaviorSpec`(given/when/then 한국어). 도메인 단위 테스트 + `:infra:persistence` H2 영속 테스트 + `:app:api` ArchUnit.

**Target Platform**: Linux server (web bootJar `:app:api`, batch `:app:batch`)

**Project Type**: 멀티모듈 모듈러 모놀리스(백엔드) — 이번 변경은 `:core:avoidance` + `:infra:persistence` + `:app:api`(ArchUnit) + Flyway 에 국한.

**Performance Goals**: 별도 성능 목표 없음(카탈로그 81행, 코드/ID 조회). JSON 통합으로 성분 1행 = 이름 1행(카테고리 조인 제거로 쿼리 단순화).

**Constraints**: 기존 81종 데이터 무손실 이전. 컬럼 정의 MySQL 기준. 테스트(H2)에서도 JSON 매핑이 동작해야 함(엔티티 매핑이 H2 create-drop 스키마를 생성). Kotlin 주석 금지·도메인 불변·`BaseResponse`/`/api/v` 규약 준수.

**Scale/Scope**: 성분 81종, 지원 언어 10종(ko + 9). 변경 파일 대략: 도메인 4~5, 영속 5~6, ArchUnit 1, Flyway 1(신규 V6), 관련 테스트.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- **I. Test-First (NON-NEGOTIABLE)** — 준수. 각 변경(도메인 카테고리 제거, JSON 매핑 복원, 카테고리 잔여 제거)은 실패 테스트 우선 작성 후 구현. 특히 영속 JSON 왕복은 H2 어댑터 테스트로 Red→Green.
- **II. Bounded Contexts** — 준수. 변경은 `avoidance` 컨텍스트와 그 영속 어댑터에 국한. food 는 `IngredientAvoidanceSubstanceRepository` port 로만 성분을 받으므로(코드/스냅샷 참조) 영향 없음.
- **III. Layered Dependency Direction** — 준수. port(`AvoidanceSubstanceRepository`)에서 `byCategory` 제거 → 도메인 계약 축소. 상위(application/app) 는 여전히 port 만 참조.
- **IV. Persistence Encapsulation** — 준수. JSON 매핑·엔티티·어댑터는 `:infra:persistence` 안에서만. 도메인은 ORM-free 유지(`translations: Map<LanguageCode,String>` 는 순수 코틀린 타입). ArchUnit 경계 유지(단, 폐기되는 "분류 저장 형식" 규칙만 제거).
- **V. Domain Content Language Policy** — 준수. ko 원문 + 9개 번역 + ko 폴백 + 콘텐츠↔UI 분리 **모두 불변**. 분류(카탈로그 데이터의 한 축)를 DB 에서 제거하는 것은 원칙 V 의 요구(ko+9·폴백·분리)를 훼손하지 않음. 번역은 저장 **형태**만 컬럼→JSON 으로 바뀌고 콘텐츠 DB 단일 출처·식별자 enum 원칙은 유지. (`AvoidanceSubstanceCode` label=시드 korean_name 정합 규칙 불변.)

**게이트 통과** — 위반 없음. Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/009-avoidance-schema-refactor/
├── plan.md              # 본 파일
├── research.md          # Phase 0 — JSON 매핑/키/마이그레이션/파급 결정
├── data-model.md        # Phase 1 — 엔티티·도메인 before/after
├── quickstart.md        # Phase 1 — 검증 절차
├── checklists/
│   └── requirements.md  # spec 품질 체크리스트
└── tasks.md             # /speckit-tasks 산출(본 명령 아님)
```

(contracts/ 는 생성하지 않음 — 아래 Structure Decision 참고.)

### Source Code (repository root)

```text
core/avoidance/src/main/kotlin/com/meogo/core/avoidance/
├── AvoidanceCategory.kt              # ✗ 삭제
├── AvoidanceSubstance.kt             # 수정: categories/belongsTo/require(categories) 제거
├── AvoidanceSubstanceRepository.kt   # 수정: byCategory 제거
├── AvoidanceSubstanceCode.kt         # 불변
└── IngredientAvoidanceSubstanceRepository.kt # 불변

infra/persistence/src/main/kotlin/com/meogo/infra/persistence/avoidance/
├── AvoidanceSubstanceJpaEntity.kt            # 수정: name_* 9컬럼 → translations JSON 매핑, toDomain 단순화
├── AvoidanceSubstanceCategoryJpaEntity.kt    # ✗ 삭제
├── AvoidanceSubstanceCategoryJpaRepository.kt # ✗ 삭제
├── AvoidanceSubstanceReconstitutor.kt        # 수정: 카테고리 조인 제거, "카테고리 없으면 drop" 필터 제거
├── AvoidanceSubstanceRepositoryAdapter.kt    # 수정: byCategory 구현·category 리포지토리 의존 제거
├── AvoidanceSubstanceJpaRepository.kt        # 불변
└── IngredientAvoidanceSubstanceRepositoryAdapter.kt # 불변(Reconstitutor 재사용)

app/api/src/main/resources/db/migration/
└── V6__drop_avoidance_category_and_jsonify_translations.sql  # ✚ 신규

app/api/src/test/kotlin/com/meogo/app/api/architecture/
└── ModuleBoundaryTest.kt             # 수정: "분류 저장 형식 회귀" given 블록 제거

(테스트: core/avoidance/.../AvoidanceSubstanceTest.kt, infra/.../AvoidanceSubstanceRepositoryAdapterTest.kt,
 application/.../FoodAvoidanceSubstanceResolverTest.kt — 카테고리 참조 제거·JSON 왕복 검증으로 수정)
```

**Structure Decision**: 백엔드 단일 멀티모듈 모놀리스. 이번 기능은 새 모듈/디렉터리를 만들지 않고 기존 `avoidance` 컨텍스트와 그 영속 어댑터, ArchUnit, Flyway 를 수정한다. **contracts/ 디렉터리는 생성하지 않는다** — 이 변경은 순수 내부(영속·도메인) 리팩터로 외부 API 요청/응답 계약이 달라지지 않기 때문이다(SC-004). 외부 인터페이스 산출물이 없어 계약 문서화 대상이 없다.

## Complexity Tracking

> Constitution Check 위반 없음 — 작성 불필요.
