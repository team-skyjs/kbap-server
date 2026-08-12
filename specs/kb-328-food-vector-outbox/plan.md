# Implementation Plan: READY 전이 벡터 아웃박스 기반 음식 벡터 동기화

**Branch**: `kb-328-food-vector-outbox` | **Date**: 2026-08-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-328-food-vector-outbox/spec.md`

## Summary

관리자 승인(PENDING_REVIEW → READY)·READY 이후 변경·삭제 시 같은 트랜잭션에서 `food_vector_outbox`(UPSERT|DELETE) 를 생성하고, `:batch` 의 `foodVectorSyncJob` 이 PENDING 을 읽어 처리 시점 최신 MySQL 데이터(koreanName + longDescription)를 임베딩해 DocumentDB `kbap.foods` 에 foodId 기준 upsert/delete 한다. `embeddingHash` 로 멱등을 보장하고(hash 동일 → 임베딩 생략), 실패는 attempts/last_error 로 기록해 5회 초과 시 FAILED 로 격리, 관리자 화면에서 조회·재처리한다. 기존 READY 음식은 테이블 생성 Flyway 마이그레이션의 INSERT…SELECT 백필로 1회 적재한다. 구조는 기존 `food_content_outbox` 파이프라인(엔티티·커서 페이징 리포지토리·Tasklet 배치·짧은 트랜잭션 분리·관리자 대시보드)의 검증된 패턴을 복제·확장한다 — 상세 결정과 근거는 [research.md](research.md) R1–R10.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring Batch(기존 `:batch`), Spring AI 2.0 Bedrock(`:infra:llm` — `TextEmbeddingClient` seam), mongodb-driver-sync(신규 — `:batch` 에 추가, api 읽기 경로와 동일 드라이버)

**Storage**: MySQL(`food_vector_outbox` 신규 테이블, Flyway owner = api) + AWS DocumentDB(`kbap.foods` 컬렉션, cosine 벡터 인덱스 — KB-318 기구축)

**Testing**: Kotest BehaviorSpec + MySQL Testcontainers(아웃박스 생성·상태 전이·리포지토리), DocumentDB 는 Testcontainers 재현 불가 → `FoodVectorStore` seam 뒤 fake 로 배치 로직 검증(KB-319 선례), 어댑터는 dev 클러스터 수동 검증

**Target Platform**: Linux server — `:api`(web bootJar)·`:batch`(run-to-completion ECS 태스크)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스(기존 7모듈, 신규 모듈 없음)

**Performance Goals**: 실시간성 불요 — "다음 배치 실행 안에 반영"(SC-001/004). 임베딩 호출 수 ≤ 실제 내용 변경 건수(SC-002, hash 스킵)

**Constraints**: 외부 호출(임베딩·DocumentDB)은 DB 트랜잭션 밖(헌법 Additional Constraints), 아웃박스 생성은 상태 전이와 같은 트랜잭션(FR-001), at-least-once 처리에서 결과 멱등(FR-006)

**Scale/Scope**: READY 음식 수백~수천 건 규모 백필 + 이후 승인·수정 건당 증분(배치 1회 실행당 수십 건 내외) — 페이징 커서(기존 패턴)로 충분

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | ✅ | 전 task Red→Green→Refactor. 아웃박스 생성/전이·판정 로직은 Testcontainers·fake seam 으로 실패 테스트 선행 가능 |
| II. Bounded Contexts | ✅ | 신규 코드는 전부 food 컨텍스트(`common.domain.food`)·api admin 조합·batch 조립에 위치. 도메인 간 의존 추가 없음(ModuleBoundaryTest 허용 맵 무변경) |
| III. Layered Dependency Direction | ⚠️→✅ | 모듈 의존은 batch→common 그대로. DocumentDB 쓰기 클라이언트를 common.port seam 없이 `:batch` 내부에 두는 것은 원칙 III 문언("외부 시스템은 seam 인터페이스로만")의 예외 — KB-319 가 읽기 경로에서 동일 예외를 확립했고 Complexity Tracking 에 정당화 기록 |
| IV. Persistence Ownership | ✅ | `FoodVectorOutbox` 엔티티·리포지토리는 `common.domain.food` 에 public, BaseEntity 상속, JPA 연관 없음(food_id 는 Long), FK 는 Flyway 스키마가 강제, 트랜잭션 경계는 소비자(admin 서비스 `@Transactional`·배치 `TransactionTemplate`)가 명시 소유 |
| V. Language Policy | ✅ | 사용자 노출 콘텐츠 없음(벡터 메타데이터·관리자 화면) — 해당 없음 |

**Post-Phase-1 재평가**: 설계 산출물(data-model·contracts)이 위 판정을 바꾸지 않음. 유일한 예외(III — batch 내부 seam)는 아래 Complexity Tracking 에 기록. GATE 통과.

## Project Structure

### Documentation (this feature)

```text
specs/kb-328-food-vector-outbox/
├── plan.md              # 이 파일
├── research.md          # Phase 0 — 결정 R1~R10
├── data-model.md        # Phase 1 — 아웃박스 테이블·벡터 문서·판정 표
├── quickstart.md        # Phase 1 — 로컬/dev 검증 절차
├── contracts/
│   ├── vector-food-document-v2.md   # 벡터 문서 스키마(KB-319 계약 대체)
│   └── admin-vector-outbox.md       # 관리자 대시보드·재처리 화면 계약
└── tasks.md             # Phase 2 — /speckit-tasks 산출(이 커맨드가 만들지 않음)
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/food/
├── model/FoodVectorOutbox.kt            # 신규 — 엔티티 + operation/status enum
└── FoodVectorOutboxJpaRepository.kt     # 신규 — 커서 페이징·상태 전이·대시보드 쿼리

api/src/main/kotlin/com/kbap/api/admin/
├── AdminFoodContentReviewService.kt     # 수정 — 승인 전이 시 UPSERT 아웃박스 생성
├── AdminFoodService.kt                  # 수정 — updateFood(READY→UPSERT/해제→DELETE)·deleteFood(DELETE)
├── AdminFoodPageController.kt           # 수정 — 벡터 아웃박스 재처리 POST
└── AdminFoodDashboardService.kt         # 수정 — 벡터 아웃박스 카운트·FAILED 목록
api/src/main/resources/db/migration/
└── V<timestamp>__food_vector_outbox_table.sql   # 신규 — 테이블 + READY 백필 INSERT…SELECT

common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt
                                          # 수정 — approve() 가 전이 여부 Boolean 반환

batch/src/main/kotlin/com/kbap/batch/vector/
├── FoodVectorSyncBatchConfig.kt         # 신규 — foodVectorSyncJob/step (Tasklet, 기존 패턴)
├── FoodVectorSyncProcessor.kt           # 신규 — 페이지 조회→임베딩/스토어(트랜잭션 밖)→결과 반영
├── FoodVectorStore.kt                   # 신규 — batch 내부 seam (upsert/delete/findHash)
├── DocumentDbFoodVectorStore.kt         # 신규 — mongodb-driver-sync thin adapter + 조건부 조립
└── FoodVectorSyncSummary.kt             # 신규 — 실행 결과 요약(로그)
batch/build.gradle.kts                    # 수정 — mongodb-driver-sync 의존 추가
batch/src/main/resources/application.yml  # 수정 — kbap.llm.embedding.*(dimension 256)·kbap.vector.* 신설

api/src/test/kotlin/com/kbap/api/admin/   # 승인·수정·삭제 훅 통합 테스트
common/src/test/kotlin/com/kbap/common/domain/food/  # 아웃박스 리포지토리·엔티티 테스트
batch/src/test/kotlin/com/kbap/batch/vector/         # 판정 로직 테스트(fake seam)
```

**Structure Decision**: 신규 모듈 없음. 영속(엔티티·리포지토리)은 food 컨텍스트 소유로 `:common`, 생성 훅은 전이 트랜잭션을 소유한 api admin 서비스, 소비·외부 시스템 접근은 `:batch` 내부 `vector` 패키지에 응집한다(기존 `outbox` 패키지의 콘텐츠 발행과 나란한 구조).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 III 예외 — DocumentDB 쓰기 seam(`FoodVectorStore`)을 `common.port` 가 아닌 `:batch` 내부에 배치 | 소비자가 배치 하나뿐이고 DocumentDB 는 Testcontainers 재현 불가라 어댑터를 얇게 유지해야 함. KB-319 가 읽기 경로(`SimilarFoodSearcher` — `:api` 내부)에서 동일 예외를 확립 | `common.port.vector` + `:infra:documentdb` 신설 — 소비자 1개를 위해 모듈·계약 층을 늘리는 과잉 설계로 KB-319 에서 이미 기각. api 빈 재사용은 api↔batch 상호 미의존 원칙 위반 |
