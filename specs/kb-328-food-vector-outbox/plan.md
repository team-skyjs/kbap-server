# Implementation Plan: READY 전이 벡터 아웃박스 기반 음식 벡터 동기화

**Branch**: `kb-328-food-vector-outbox` | **Date**: 2026-08-12 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-328-food-vector-outbox/spec.md`

## Summary

관리자 승인(PENDING_REVIEW → READY)·READY 이후 변경·삭제 시 같은 트랜잭션에서 `food_vector_outbox`(UPSERT|DELETE) 를 생성하고, `:batch` 의 `foodVectorSyncJob` 이 PENDING 을 읽어 처리 시점 최신 MySQL 데이터(koreanName + longDescription)를 임베딩해 DocumentDB `kbap.foods` 에 foodId 기준 upsert/delete 한다. `embeddingHash` 로 멱등을 보장하고(hash 동일 → 임베딩 생략), 실패는 attempts/last_error 로 기록해 5회 초과 시 FAILED 로 격리, 관리자 화면에서 조회·재처리한다. 기존 READY 음식은 자동 백필 없이 랭체인 재수집 후 관리자 화면의 수동 적재 액션(미적재분 500건 단위)으로 적재한다(2026-08-13 재개정 — R9). 구조는 기존 `food_content_outbox` 파이프라인(엔티티·커서 페이징 리포지토리·Tasklet 배치·짧은 트랜잭션 분리·관리자 대시보드)의 검증된 패턴을 복제·확장하며, 벡터 저장소 접근(seam·어댑터·연결 설정·문서 필드명)은 api(읽기)·batch(쓰기) 공용이 되므로 **food 컨텍스트의 제2 영속으로서 `:common` 에 단일 소유**시킨다(기존 api 내부 검색 어댑터 이사 포함) — 상세 결정과 근거는 [research.md](research.md) R1–R10.

## Technical Context

**Language/Version**: Kotlin 2.3 / JVM 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring Batch(기존 `:batch`), Spring AI 2.0 Bedrock(`:infra:llm` — `TextEmbeddingClient` seam), mongodb-driver-sync(`:api` 개별 의존 → **`:common` 으로 승격** — 읽기·쓰기 공용)

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
| III. Layered Dependency Direction | ⚠️→✅ | 모듈 의존은 api·batch→common 그대로(신규 방향 없음). 벡터 저장소 어댑터를 `common.port`+`:infra:*` 가 아니라 `:common` 의 food 도메인에 두는 것은 원칙 III 문언("외부 시스템 구현은 :infra")의 예외 — 벡터 문서를 외부 시스템이 아닌 **food 의 제2 영속**(헌법 IV 소유 규칙)으로 취급, Complexity Tracking 에 정당화 기록. 조립은 여전히 부트앱 config 소유 |
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
├── FoodVectorOutboxJpaRepository.kt     # 신규 — 커서 페이징·상태 전이·대시보드 쿼리
└── vector/                              # 신규 — food 의 제2 영속(벡터 저장소) 단일 소유
    ├── FoodVectorSearcher.kt            #   seam (기존 api SimilarFoodSearcher 이사)
    ├── FoodVectorStore.kt               #   seam — upsert/delete/findHash
    ├── FoodVectorDocuments.kt           #   문서 필드명·hash 규약 단일 출처
    ├── DocumentDbFoodVectorSearcher.kt  #   어댑터 (기존 api 구현 이사, plain class)
    ├── DocumentDbFoodVectorStore.kt     #   어댑터 — mongodb-driver-sync (plain class)
    └── FoodVectorProperties.kt          #   kbap.vector.* 설정 홀더
common/build.gradle.kts                   # 수정 — mongodb-driver-sync 의존 추가(:api 에서 승격)

common/src/main/kotlin/com/kbap/common/domain/food/model/Food.kt
                                          # 수정 — approve() 가 전이 여부 Boolean 반환

api/src/main/kotlin/com/kbap/api/admin/
├── AdminFoodContentReviewService.kt     # 수정 — 승인 전이 시 UPSERT 아웃박스 생성
├── AdminFoodService.kt                  # 수정 — updateFood(READY→UPSERT/해제→DELETE)·deleteFood(DELETE)
├── AdminFoodPageController.kt           # 수정 — 벡터 아웃박스 재처리 POST
└── AdminFoodDashboardService.kt         # 수정 — 벡터 아웃박스 카운트·FAILED 목록
api/src/main/kotlin/com/kbap/api/scan/    # 수정 — SimilarFood* 를 common vector seam 참조로 전환(이사 리팩터링)
api/src/main/kotlin/com/kbap/api/core/config/
                                          # 수정 — searcher 빈 조립(@ConditionalOnProperty kbap.vector.enabled)
api/src/main/resources/db/migration/
└── V<timestamp>__food_vector_outbox_table.sql   # 신규 — 테이블 생성 (백필 없음 — 수동 적재, R9 재개정)

batch/src/main/kotlin/com/kbap/batch/vector/
├── FoodVectorSyncBatchConfig.kt         # 신규 — foodVectorSyncJob/step (Tasklet) + store 빈 조립
├── FoodVectorSyncProcessor.kt           # 신규 — 페이지 조회→임베딩/스토어(트랜잭션 밖)→결과 반영
└── FoodVectorSyncSummary.kt             # 신규 — 실행 결과 요약(로그)
batch/src/main/resources/application.yml  # 수정 — kbap.llm.embedding.*(dimension 256)·kbap.vector.* 신설

api/src/test/kotlin/com/kbap/api/admin/   # 승인·수정·삭제 훅 통합 테스트
common/src/test/kotlin/com/kbap/common/domain/food/  # 아웃박스 리포지토리·엔티티 테스트
batch/src/test/kotlin/com/kbap/batch/vector/         # 판정 로직 테스트(fake seam)
```

**Structure Decision**: 신규 모듈 없음. 영속(JPA 아웃박스 + 벡터 저장소 seam·어댑터·문서 필드명)은 food 컨텍스트 소유로 `:common` 에 단일화하고(mongodb-driver-sync 는 `:api` → `:common` 승격), 생성 훅은 전이 트랜잭션을 소유한 api admin 서비스, 배치 소비는 `:batch` 의 `vector` 패키지(기존 `outbox` 패키지와 나란한 구조)에 둔다. 어댑터는 스테레오타입 없는 plain class 로 두고 **빈 조립은 부트앱 config** 가 한다 — api 는 searcher 만, batch 는 store 만(배치의 좁힌 컴포넌트 스캔과도 정합).

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 원칙 III 문언 예외 — 벡터 저장소 어댑터(mongodb-driver-sync)를 `common.port`+`:infra:*` 가 아닌 `:common` 의 `common.domain.food.vector` 에 배치 | KB-328 로 소비자가 api(읽기)·batch(쓰기) 둘이 되어 연결 설정·문서 필드명이 공유 계약이 됨 — 단일 출처 필요. 벡터 문서는 교체 가능한 외부 시스템이 아니라 food 데이터의 제2 영속이므로 "영속은 :common 소유"(원칙 IV)로 정합. 조립은 부트앱 config 소유 유지 | (a) 모듈별 자체 어댑터 — 필드명·연결 설정 드리프트가 조용한 검색 파손으로 이어짐. (b) `common.port.vector`+`:infra:vector` 신설 — 문언에는 충실하나 소비자 둘을 위해 모듈·계약 층을 늘리는 비용 대비 이득 없음(KB-319 에서 동종 기각), 영속 성격과도 불일치 |
