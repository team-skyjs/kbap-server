# Implementation Plan: 음식 기피성분 매핑을 food 테이블 JSON 컬럼으로 이관

**Branch**: `kb-210-food-avoidance-json` | **Date**: 2026-07-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-210-food-avoidance-json/spec.md`

## Summary

음식별 기피성분(코드 + 포함 확률)을 별도 매핑 테이블 `food_avoidance_substance` 의 읽기 전용 `@OneToMany` 연관으로 들던 구조를, `food` 테이블의 JSON 컬럼(`avoidance_substances`)으로 이관한다. 조회(상세·목록·스캔 위험도)는 JSON 컬럼 기반으로 전환하되 정렬·유효성은 애플리케이션이 담당하고, DB 는 저장만 한다(저장소 수준 제약 금지). 기존 데이터는 Flyway 마이그레이션으로 1회 백필하며 **구 테이블·엔티티·리포지토리·배치는 건드리지 않는다**(후속 작업).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Spring Boot 4.1)

**Primary Dependencies**: Spring Data JPA (Hibernate — `@JdbcTypeCode(SqlTypes.JSON)` 기존 사용 중), Flyway(+flyway-mysql)

**Storage**: MySQL (`food` 테이블에 JSON 컬럼 추가; `food_avoidance_substance` 는 보존)

**Testing**: Kotest BehaviorSpec + JUnit 5 플랫폼, 통합 테스트는 MySQL Testcontainers. **api 테스트 프로필은 Flyway enabled + `ddl-auto=validate`** — 엔티티 컬럼 추가는 대응 Flyway 마이그레이션과 한 묶음이어야 `validate` 통과(US1↔US2 스키마 결합)

**Target Platform**: `:app:api` (web bootJar, Flyway 스키마 owner) — 배치(`:app:batch`)는 이번 범위 밖

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스 — 변경은 `:domain:food` + `:app:api`(마이그레이션·테스트 시드)에 국한

**Performance Goals**: 기존과 동등 — JSON 컬럼은 food row 와 함께 단일 조회로 로드되므로 `@OneToMany(EAGER)+@BatchSize` 의 추가 쿼리가 사라진다(회귀 없음)

**Constraints**: 정렬·유효성은 애플리케이션 레이어 전담(DB CHECK·UNIQUE 등 추가 금지), 구 테이블 삭제 금지, 배치 코드 무수정

**Scale/Scope**: 음식 1건당 기피성분 소수(카탈로그 81종 이하) — JSON 크기 문제 없음

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS (계획) | 각 task 를 Red→Green→Refactor 로 진행 — Food 도메인 단위 테스트·FoodService 테스트·컨트롤러 통합 테스트를 먼저 갱신/작성해 실패 확인 후 구현 |
| II. Bounded Contexts | PASS | 변경은 food 컨텍스트 내부. 성분 참조는 기존처럼 **코드 문자열**(JSON 의 `code`)로 유지 — avoidance enum 직접 보관 없음 |
| III. Layered Dependency | PASS | 모듈 의존 변화 없음. `:app:api` 는 마이그레이션·테스트만 수정 |
| IV. Persistence Encapsulation | PASS (개선) | 현행 유일 예외였던 `Food` 의 읽기 전용 `@OneToMany` 연관이 **제거**되고 JSON 값 컬럼으로 대체된다 — "엔티티 간 JPA 연관관계 금지" 원칙 위반 예외가 0건이 된다. 리포지토리 `internal` 유지 |
| V. Language Policy | 해당 없음 | JSON 은 성분 **코드·확률만** 저장 — 표시명·번역은 기존대로 avoidance 카탈로그(DB)가 단일 출처 |

**Post-Phase-1 재확인**: data-model.md 설계 결과 위반 없음 — 신규 값 객체(`FoodAvoidanceItem`)는 food 모듈 `model/` 에 위치, DB 제약 추가 없음(사용자 지시 + 스키마는 저장만).

## Project Structure

### Documentation (this feature)

```text
specs/kb-210-food-avoidance-json/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

(contracts/ 없음 — 외부 API 계약 변경이 0건인 내부 저장 구조 이관. FoodDetailResponse·FoodSummaryResponse·ScanResponse 의 필드·정렬 계약은 그대로 유지된다.)

### Source Code (repository root)

```text
domain/food/src/main/kotlin/com/kbap/domain/food/
├── model/Food.kt                          # [수정] @OneToMany 제거 → JSON 컬럼 매핑, 도메인 메서드 유지
├── model/FoodAvoidanceItem.kt             # [신규] 값 객체 (code + inclusionPercent + riskLevel())
├── model/FoodAvoidanceSubstance.kt        # [보존] 구 엔티티 — 미수정
├── FoodAvoidanceSubstanceJpaRepository.kt # [보존] 미수정 (main 소스에서 이미 미사용)
├── FoodService.kt                         # [수정] getDetail 소비 타입 교체 + upsertIncomplete 에 새 컬럼
└── FoodScoringSource.kt                   # [무수정] 배치 읽기 창구

domain/scan/src/main/kotlin/com/kbap/domain/scan/ScanService.kt  # [무수정] overallRisk 시그니처 유지

app/api/src/main/resources/db/migration/
└── V2026.07.21.HH.mm.ss__add_food_avoidance_substances_json.sql # [신규] 컬럼 추가 + 백필

domain/food/src/test/kotlin/…   # [수정] FoodTest·FoodOverallRiskTest·FoodServiceTest 시드 방식 교체
app/api/src/test/kotlin/…       # [수정] FoodTestSeed·HomeTestSeed·ScenarioFoodSeed 등 food INSERT 에 JSON 컬럼 반영
```

**Structure Decision**: 기존 모듈 구조 그대로 — `:domain:food` 가 값 객체·매핑·서비스 변경을 소유하고, `:app:api` 는 Flyway 마이그레이션(스키마 owner)과 통합 테스트 시드만 수정한다.

## Complexity Tracking

> 위반 없음 — 이 작업은 유일한 JPA 연관관계 예외를 제거해 구조를 단순화한다.
