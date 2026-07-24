# Implementation Plan: 모든 JPA 엔티티·리포지토리 internal 제거 — 영속 캡슐화 완화

**Branch**: `kb-220-remove-internal` | **Date**: 2026-07-22 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-220-remove-internal/spec.md`

## Summary

도메인 모듈의 Spring Data 리포지토리 9개 선언에서 `internal` 을 제거하고(엔티티는 이미 public — 전수 확인만), 그 부산물인 도메인 서비스 8개의 `internal constructor` 도 함께 제거한다. `internal` 우회 전용 창구 서비스 2개(`FoodContentBatchService`·`AvoidanceCatalogService`)를 삭제하고 소비 계층(`:app:batch` 콘텐츠 파이프라인, `:application` 홈 유스케이스)이 리포지토리를 직접 사용하도록 배선한다. 핵심 제약은 배치 `saveProgress` 의 REQUIRES_NEW(청크 실패에도 진행 커밋 유지) 의미를 배치 계층이 `TransactionTemplate` 로 이어받는 것. 마지막으로 헌법 원칙 IV(+III 의 "유일 창구" 조항)를 개정(5.0.0 MAJOR)하고 ADR-0014·컨벤션 문서·CLAUDE.md 를 새 정책으로 갱신한다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM(Java 21 toolchain)

**Primary Dependencies**: Spring Boot 4.1(data-jpa·batch), Spring Batch(청크 스텝), Kotest + MySQL Testcontainers

**Storage**: MySQL — 스키마·데이터 변경 없음(코드 가시성·배선만 변경)

**Testing**: Kotest BehaviorSpec(전 테스트), `@SpringBootTest` + MySQL Testcontainers, ArchUnit(`arch` 태그)

**Target Platform**: 기존 bootJar 2종(`:app:api`·`:app:batch`) — 신규 모듈 없음

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스(기존 구조 유지)

**Performance Goals**: 해당 없음 — 동작 동일성 유지가 목표(위임 계층 1단 제거로 미세 단순화)

**Constraints**: 배치 진행 저장의 독립 커밋(REQUIRES_NEW) 의미 보존 · 기존 API 응답 불변 · 전체 테스트 그린

**Scale/Scope**: `internal` 선언 9곳 + `internal constructor` 8곳 제거, 창구 서비스 2개 삭제·소비처 재배선(배치 4파일·application 1파일), 테스트 이전 1파일, 문서 4종 + 헌법 + ADR 1건

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First (NON-NEGOTIABLE) | PASS | 새 동작(배치 REQUIRES_NEW 진행 저장·홈 직접 배선)은 실패 테스트 선행. 가시성 완화 자체는 동작 불변 — 기존 테스트 스위트가 회귀 방어, 창구 테스트 시나리오는 손실 없이 이전한다. |
| II. Bounded Contexts | PASS | 도메인 간 결합 변화 없음. 소비 계층(배치·application)이 리포지토리를 직접 쓰는 것은 계층 방향(상위→하위)을 그대로 따른다. |
| III. Layered Dependency Direction | **개정 동반** | "도메인 서비스를 유일한 공개 창구로 둔다" 조항이 이 기능으로 완화된다. 의존 방향 자체(부트앱→application→도메인→core)는 불변. |
| IV. Persistence Encapsulation | **개정 동반** | 이 기능의 목적 그 자체 — "internal 로 감춘다·외부 참조는 컴파일 실패" 를 폐기하고 완화된 정책으로 재정의한다. 유지되는 것: JPA 연관관계 금지(id 참조)·Flyway 스키마 owner·도메인 로직의 도메인 서비스 소유. |
| V. Language Policy | PASS | 무관(홈 응답의 lang 처리 불변). |

**게이트 판정**: PASS — 원칙 III·IV 의 이탈은 위반이 아니라 **이 기능이 곧 헌법 개정**(KB-220)이다. 개정은 헌법 문서의 절차(Sync Impact Report, 4.0.0 → 5.0.0 MAJOR)를 따르며 코드 변경과 같은 브랜치에서 원자적으로 반영한다. Complexity Tracking 에 기록할 별도 위반 없음.

**참고(개정 시 함께 정합화)**: 현행 헌법 IV 의 "도메인 모델과 JPA 엔티티는 분리한다 — ORM 애너테이션을 갖지 않으며 toDomain/from 변환" 서술은 2026-07-14 대개편(엔티티=도메인 모델, CLAUDE.md·ArchUnit 반영 완료) 이후의 실제 코드와 이미 어긋나 있다. 원칙 IV 를 다시 쓰는 김에 현실과 일치시킨다.

## Project Structure

### Documentation (this feature)

```text
specs/kb-220-remove-internal/
├── spec.md              # /speckit-specify 출력
├── plan.md              # 이 파일
├── research.md          # Phase 0 출력 — 설계 결정 6건
├── data-model.md        # Phase 1 출력 — 스키마 변경 없음 확인
├── quickstart.md        # Phase 1 출력 — 검증 절차
└── tasks.md             # /speckit-tasks 출력 (이 커맨드가 만들지 않음)
```

`contracts/` 는 만들지 않는다 — 외부 인터페이스(API 응답·경로) 변경이 없다(SC-005: 기존 동작 동일).

### Source Code (repository root)

```text
domain/
├── avoidance/src/main/kotlin/com/kbap/domain/avoidance/
│   ├── AvoidanceSubstanceJpaRepository.kt   # internal 제거
│   ├── AvoidanceCatalogService.kt           # 삭제(위임 전용 창구)
│   └── AvoidanceService.kt 등               # internal constructor 제거
├── bookmark/…/BookmarkJpaRepository.kt      # internal 제거 (+BookmarkService ctor)
├── member/…/MemberJpaRepository.kt          # internal 제거 (+MemberService ctor)
├── scan/…/ScanHistoryJpaRepository.kt       # internal 제거 (+ScanService ctor)
├── image/…/UploadedImageJpaRepository.kt    # internal 제거 (+ImageUploadService ctor)
├── metering/…/LlmCallCostJpaRepository.kt   # internal 제거 (+LlmCallCostService ctor)
└── food/src/main/kotlin/com/kbap/domain/food/
    ├── FoodJpaRepository.kt                 # internal 제거
    ├── FoodJpaRepositoryCustom.kt           # internal 제거
    ├── FoodJpaRepositoryCustomImpl.kt       # internal 제거
    ├── FoodContentBatchService.kt           # 삭제(위임 전용 창구)
    └── FoodService.kt                       # internal constructor 제거
domain/food/src/test/kotlin/com/kbap/domain/food/
    └── FoodContentBatchServiceTest.kt       # → FoodJpaRepositoryTest 로 시나리오 이전

app/batch/src/main/kotlin/com/kbap/app/batch/content/
├── FoodContentBatchConfig.kt                # @Import 제거, FoodJpaRepository 직접 주입
├── IncompleteFoodItemReader.kt              # FoodJpaRepository 직접 사용
└── FoodContentItemProcessor.kt              # 리포지토리 + TransactionTemplate(REQUIRES_NEW)

application/src/main/kotlin/com/kbap/application/home/
└── HomeApplicationService.kt                # AvoidanceSubstanceJpaRepository 직접 사용

app/api/src/test/kotlin/com/kbap/app/api/architecture/
└── ModuleBoundaryTest.kt                    # 규칙 점검(결론: 구조 변경 불필요 — research D4)

.specify/memory/constitution.md              # 원칙 III·IV 개정, 5.0.0
docs/adr/0014-*.md                           # 신설 — 영속 캡슐화 완화 결정
docs/architecture/meogo-conventions.md       # "도메인 서비스 창구"·internal 서술 갱신
docs/architecture/meogo-api-module-structure.md  # 79행 등 캡슐화 서술 갱신
CLAUDE.md                                    # 리포지토리 internal·internal constructor 서술 갱신
```

**Structure Decision**: 기존 멀티모듈 구조를 그대로 쓴다. 신규 모듈·패키지 없음. 유일한 코드 이동은 창구 서비스 2개의 삭제와 그 트랜잭션 경계의 소비 계층 이관이다.

## Complexity Tracking

위반 없음 — 이 기능은 조각 수를 늘리는 쪽이 아니라 줄이는 쪽(창구 서비스 2개·internal 선언 17곳 제거)이다.
