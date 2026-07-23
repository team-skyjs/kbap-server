# Implementation Plan: 배치 완성 콘텐츠를 검수 대기(PENDING_REVIEW)로 저장

**Branch**: `kb-223-batch-review-status` | **Date**: 2026-07-23 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-223-batch-review-status/spec.md`

## Summary

배치가 음식 콘텐츠 4작업을 모두 채웠을 때의 전이 목적지를 READY 에서 **PENDING_REVIEW** 로 바꾼다. `FoodContentStatus` 에 값 추가 + MySQL ENUM 컬럼 확장(Flyway) + `Food` 의 전이 도메인 메서드 변경이 전부이며, 사용자 노출 필터(`content_status = 'READY'`)는 이미 전 조회 쿼리에 있어 **변경 없이 그대로** PENDING_REVIEW 를 차단한다. 관리자 승인/반려는 범위 밖(후속 브랜치).

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 (Gradle toolchain)

**Primary Dependencies**: Spring Boot 4.1 (data-jpa), Spring Batch(`:app:batch`), Flyway

**Storage**: MySQL — `food.content_status` 는 **MySQL ENUM 컬럼**(`enum('INCOMPLETE','READY')`, `V2026.07.16.21.38.41__init_schema.sql:27`) → 값 추가에 ALTER 마이그레이션 필요

**Testing**: Kotest BehaviorSpec(한국어 given/when/then) + JUnit5 플랫폼, 통합은 MySQL Testcontainers

**Target Platform**: 서버(모듈러 모놀리스, bootJar 2개 — api·batch)

**Project Type**: 백엔드 멀티모듈 — 이번 변경 모듈: `:domain:food`, `:app:batch`, `:app:api`(Flyway owner, SQL 만)

**Performance Goals**: 해당 없음(상태 값 1개 추가·전이 목적지 변경 — 쿼리 플랜 불변)

**Constraints**: 기존 READY 데이터 불변(FR-004), 배치는 flyway off — 스키마 변경은 api Flyway 로만

**Scale/Scope**: 파일 4개 수정 + 마이그레이션 1개 + 테스트 갱신 — 소형 변경

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | 기존 `FoodReadyTransitionTest` 를 새 전이 기대값(Red)으로 먼저 고치고, 노출 차단 테스트를 추가한 뒤 구현한다 |
| II. Bounded Contexts | ✅ | 변경은 food 컨텍스트 내부(상태 enum·엔티티 전이). 타 도메인 의존 추가 없음 |
| III. Dependency Direction | ✅ | 의존 그래프 불변 — batch 는 이미 `:domain:food` 를 의존, 도메인 메서드 호출부 이름만 변경 |
| IV. Persistence Ownership | ✅ | 상태 전이는 엔티티 도메인 메서드가 소유(현행 유지). 스키마 변경은 Flyway owner(:app:api) 마이그레이션 |
| V. Language Policy | ✅ | 콘텐츠 언어 정책 무관. "안전 직결 데이터는 검수 상태를 구분한다" 조항을 오히려 실현하는 변경 |

**Post-design re-check**: 위반 없음 — Complexity Tracking 불필요.

## Project Structure

### Documentation (this feature)

```text
specs/kb-223-batch-review-status/
├── spec.md
├── plan.md              # 이 파일
├── research.md          # Phase 0
├── data-model.md        # Phase 1
├── quickstart.md        # Phase 1
└── tasks.md             # /speckit-tasks 산출(이 커맨드는 만들지 않음)
```

contracts/ 는 생성하지 않는다 — 이 범위엔 신규/변경 외부 인터페이스(API 엔드포인트)가 없다(배치 내부 상태 전이만).

### Source Code (repository root)

```text
domain/food/src/main/kotlin/com/kbap/domain/food/
├── model/FoodContentStatus.kt        # PENDING_REVIEW 값 추가
└── model/Food.kt                     # columnDefinition 3값 확장 + transitionToReadyIfComplete → transitionToPendingReviewIfComplete

app/batch/src/main/kotlin/com/kbap/app/batch/content/
└── FoodContentBatchConfig.kt         # writer 의 호출부 리네임 (line 47)

app/api/src/main/resources/db/migration/
└── V2026.07.23.<HH.mm.ss>__food_content_status_pending_review.sql   # ENUM 3값 ALTER

domain/food/src/test/kotlin/com/kbap/domain/food/
├── model/FoodReadyTransitionTest.kt  # 기대값 PENDING_REVIEW 로 갱신(리네임 포함)
└── FoodJpaRepositoryTest.kt          # 전이 호출부 갱신 + PENDING_REVIEW 비노출 케이스 추가
```

**Structure Decision**: 기존 모듈 구조 그대로 — 신규 모듈·패키지 없음. 변경 없는 부분: `FoodJpaRepository`·`ScanHistoryJpaRepository` 의 `content_status = 'READY'` 필터(FR-003 을 이미 충족 — PENDING_REVIEW 는 자동으로 비노출), `isReady()`/`overallRisk()`(READY 의미 불변), 시드/기존 데이터(READY 유지 — FR-004).

## Complexity Tracking

해당 없음 — Constitution Check 위반 없음.
