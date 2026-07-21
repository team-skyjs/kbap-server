# Feature Specification: food_avoidance_substance 과거 테이블 제거

**Feature Branch**: `kb-213-drop-food-avoidance-substance`

**Created**: 2026-07-21

**Status**: Draft

**Input**: User description: "kb-213 워크트리 ㄱㄱ" (KB-213 — [BE] food_avoidance_substance 과거 테이블 제거)

## Context

kb-210 에서 음식 기피성분 매핑을 `food.avoidance_substances` JSON 컬럼으로 이관하고 백필 마이그레이션까지 완료했다. 이관 이후 기존 `food_avoidance_substance` 테이블과 매핑 엔티티/리포지토리는 조회 경로에서 더 이상 사용되지 않는 dead code 다. 이 작업은 그 잔재를 안전하게 제거한다.

**선행 의존**: kb-210 JSON 이관이 배포·검증 완료된 이후에 착수한다(백필 마이그레이션이 `food_avoidance_substance` 를 참조하므로).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 레거시 매핑 테이블·엔티티 제거 (Priority: P1)

개발자로서, 더 이상 사용되지 않는 `food_avoidance_substance` 테이블과 그 JPA 엔티티·리포지토리를 코드베이스와 스키마에서 제거해 혼란과 유지보수 부담을 없애고 싶다.

**Why this priority**: 이 스토리 하나가 티켓의 전부다 — dead code 와 미사용 테이블을 제거하는 것이 유일한 목표이며, 그 자체로 완결된 가치(스키마 단순화·오독 방지)를 전달한다.

**Independent Test**: 신규 DROP 마이그레이션을 적용한 뒤 스키마에 `food_avoidance_substance` 테이블이 없고, 엔티티/리포지토리 소스가 삭제됐으며, `./gradlew build` 가 전체 통과하는 것으로 독립 검증 가능하다.

**Acceptance Scenarios**:

1. **Given** kb-210 JSON 이관이 반영된 develop 기준 브랜치, **When** 애플리케이션을 마이그레이션과 함께 부팅, **Then** 신규 Flyway DROP 마이그레이션이 적용되어 `food_avoidance_substance` 테이블이 스키마에서 제거된다.
2. **Given** 엔티티·리포지토리 소스가 삭제된 상태, **When** 전체 빌드를 실행, **Then** 컴파일 오류 없이 `./gradlew build` 가 통과한다(어떤 코드도 삭제된 타입을 참조하지 않음).
3. **Given** 테스트 시드에서 `food_avoidance_substance` 참조가 제거된 상태, **When** food·home 통합 테스트를 실행, **Then** 존재하지 않는 테이블에 대한 시드 실패 없이 모든 테스트가 통과한다.

### Edge Cases

- **DROP 대상 부재**: 아직 이관 전인 환경에서 DROP 이 먼저 적용되면 안 된다 — 선행 의존(kb-210 배포·검증 완료)을 전제로 하며, 마이그레이션은 순서 비의존적으로(테이블이 있으면 제거) 독립 실행 가능해야 한다.
- **init_schema 보존**: `init_schema` 마이그레이션의 `CREATE TABLE food_avoidance_substance` 는 수정 금지 대상이다 — 이미 적용된 히스토리를 훼손하지 않도록 별도 DROP 마이그레이션으로만 제거한다.
- **백필 마이그레이션 보존**: kb-210 의 백필 마이그레이션(`V2026.07.21.14.04.54__add_food_avoidance_substances_json.sql`)은 `food_avoidance_substance` 를 참조하지만 히스토리 보존을 위해 수정하지 않는다.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: 시스템은 `food_avoidance_substance` 테이블을 DROP 하는 **신규 Flyway 마이그레이션**을 포함해야 한다 (timestamp 버전 규칙 준수, init_schema 수정 금지).
- **FR-002**: `FoodAvoidanceSubstance` 엔티티 소스를 제거해야 한다.
- **FR-003**: `FoodAvoidanceSubstanceJpaRepository` 소스를 제거해야 한다.
- **FR-004**: 테스트 시드/코드에서 `food_avoidance_substance` 에 대한 모든 참조(`DELETE FROM` / 손 CREATE TABLE 스텁 / INSERT 시드 등)를 제거해야 한다 — 대상: `HomeTestSeed`, `FoodTestSeed`, `FoodSearchControllerTest`, `FoodListControllerTest`.
- **FR-005**: kb-210 백필 마이그레이션 파일은 수정하지 않아야 한다(히스토리 보존).
- **FR-006**: `init_schema` 마이그레이션 파일은 수정하지 않아야 한다(적용된 히스토리 보존).
- **FR-007**: 변경 후 `./gradlew build` 전체(컴파일 + 전 모듈 테스트 + ArchUnit)가 통과해야 한다.

### Key Entities *(include if feature involves data)*

- **food_avoidance_substance (제거 대상)**: 음식↔기피성분 매핑을 담던 레거시 조인 테이블. kb-210 이후 `food.avoidance_substances` JSON 컬럼으로 대체됨. 조회 경로에서 미사용.
- **FoodAvoidanceSubstance (제거 대상)**: 위 테이블의 JPA 엔티티(`domain/food/.../model`).
- **FoodAvoidanceSubstanceJpaRepository (제거 대상)**: 위 엔티티의 `internal` 리포지토리.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 마이그레이션 적용 후 DB 스키마에 `food_avoidance_substance` 테이블이 0개 존재한다.
- **SC-002**: 코드베이스 전체에서 `food_avoidance_substance` / `FoodAvoidanceSubstance` 문자열 참조가 (보존 대상인 kb-210 백필·init_schema 마이그레이션을 제외하고) 0건이다.
- **SC-003**: `./gradlew build` 가 100% 통과한다(컴파일 오류·테스트 실패 0건).
- **SC-004**: 기존 food/home 관련 API 동작(기피성분 조회 결과)이 변경 전후 동일하다 — 이 작업은 순수 제거이며 기능 회귀가 없다.

## Assumptions

- kb-210(음식 기피성분 매핑 JSON 이관, PR #82)이 develop 에 머지·배포·검증 완료된 상태를 전제로 한다(본 브랜치는 develop 기준).
- 조회·쓰기 경로가 모두 `food.avoidance_substances` JSON 컬럼으로 이미 전환되어 있어, 테이블/엔티티 제거로 인한 런타임 참조가 없다(코드 확인 결과 non-test 프로덕션 참조 0건).
- DROP 마이그레이션은 다른 미적용 마이그레이션의 실행 순서에 의존하지 않고 독립 실행된다(out-of-order 정책 준수).
