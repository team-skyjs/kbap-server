# Tasks: 음식 candidate 스테이징 파이프라인 골격 + 승격 배치

**Feature**: KB-96 | **Branch**: `kb-96-food-candidate-pipeline`
**Input**: [plan.md](./plan.md) · [spec.md](./spec.md) · [research.md](./research.md) · [data-model.md](./data-model.md) · [contracts/](./contracts/internal-contracts.md) · [quickstart.md](./quickstart.md)

**TDD (헌법 원칙 I)**: 각 동작은 실패 테스트 먼저(Red) → 최소 구현(Green) → 리팩터. 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어), 영속·배치는 MySQL Testcontainers(`:infra:persistence` testFixtures `MySqlContainerConfig`).

**경로 규약**: 소스 `src/main/kotlin/...`, 테스트 `src/test/kotlin/...` 미러링. Kotlin 주석 금지.

---

## Phase 1: Setup (공유 인프라)

- [X] T001 `:infra:persistence` 가 candidate 포트를 구현하도록 `implementation(project(":core:research"))` 추가 in `infra/persistence/build.gradle.kts`
- [X] T002 [P] `food_candidate` 스테이징 테이블 Flyway 마이그레이션 작성(korean_name UNIQUE·description nullable·description_translations JSON·substance_mapping JSON·published_food_id·BaseEntity 공통컬럼·idx_promotable) in `app/api/src/main/resources/db/migration/V2026.07.07.16.19.13__create_food_candidate.sql`
- [X] T003 [P] 배치 datasource URL 에 `rewriteBatchedStatements=true` 반영 + `meogo.promotion.runner.enabled` 기본 off 안내 in `app/batch/src/main/resources/application.yml` (및 `application-local.yml` datasource URL)

---

## Phase 2: Foundational (모든 유저스토리의 선행 — 완료 전 US 진입 금지)

**목표**: candidate 도메인·포트·영속 어댑터(기본 CRUD)와 `FoodRepository.save` 를 세워, US1~US3 가 이 위에서 각자 검증되게 한다.

- [X] T004 [P] Red: `FoodCandidate.isComplete()` 경계 단위 테스트(성분0·ko null·번역 8/9/10개·비지원 언어키·이미 published) in `core/research/src/test/kotlin/com/meogo/core/research/candidate/FoodCandidateSpec.kt`
- [X] T005 `FoodCandidate`(불변) + `SubstanceSnapshot`(code·1..100) + `isComplete()`(성분≥1 && ko desc && 번역 키집합==9개 대상언어 && 미승격) 구현 in `core/research/src/main/kotlin/com/meogo/core/research/candidate/FoodCandidate.kt`, `.../candidate/SubstanceSnapshot.kt`
- [X] T006 `FoodCandidateRepository` 포트 선언(create·findPromotable·updateSubstanceMapping·updateDescriptionTranslations·markPublished — contracts 시그니처) in `core/research/src/main/kotlin/com/meogo/core/research/candidate/FoodCandidateRepository.kt`
- [X] T007 `FoodCandidateJpaEntity`(`BaseEntity` 상속·JSON 컬럼 `@JdbcTypeCode(SqlTypes.JSON)`·`toDomain()`/`from()`) in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/research/FoodCandidateJpaEntity.kt`
- [X] T008 Red: `FoodCandidateRepositoryAdapter` 통합 테스트(create 중복 무시·findPromotable 완성/미완성/승격 필터·markPublished 링크) with Testcontainers in `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/research/FoodCandidateRepositoryAdapterSpec.kt`
- [X] T009 `FoodCandidateJpaRepository`(findPromotable 술어 쿼리·markPublished `@Modifying`) + `FoodCandidateRepositoryAdapter`(create·findPromotable·markPublished) in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/research/FoodCandidateJpaRepository.kt`, `.../research/FoodCandidateRepositoryAdapter.kt`
- [X] T010 `FoodRepository.save(food): Food` 포트 추가 in `core/food/src/main/kotlin/com/meogo/core/food/FoodRepository.kt`
- [X] T011 Red: `FoodRepositoryAdapter.save` 업서트 통합 테스트(신규 insert / 동일 korean_name update / food_avoidance_substance 재적재) with Testcontainers in `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapterSaveSpec.kt`
- [X] T012 `FoodJpaEntity.from(domain)` 컴패니언 + `FoodJpaRepository.findEntityByKoreanName`(업서트 판정) + `FoodRepositoryAdapter.save`(업서트·성분 재적재) in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodJpaEntity.kt`, `.../food/FoodJpaRepository.kt`, `.../food/FoodRepositoryAdapter.kt`

**Checkpoint**: candidate 를 만들고 조회·승격 마킹할 수 있고, Food 를 업서트 저장할 수 있다.

---

## Phase 3: User Story 1 — 완성된 음식만 서빙 (P1) 🎯 MVP

**목표**: 완성 candidate 만 `food`(+`food_avoidance_substance`)로 적재되고 미완성은 잔류한다.
**Independent Test**: 완성/미완성 candidate 를 픽스처로 심고 승격 실행 → 완성분만 food 적재, 미완성 잔류.

- [X] T013 [P] [US1] Red: `FoodPromotionJob` e2e 테스트(완성 A→food 적재·미완성 B 잔류·조회 시 완전 음식만) with Testcontainers, 완성 candidate 는 픽스처로 직접 insert in `app/batch/src/test/kotlin/com/meogo/app/batch/promotion/FoodPromotionJobSpec.kt`
- [X] T014 [US1] candidate 스냅샷 → `Food` 조립 매퍼(경계 이관·스냅샷 값만·`Food.create`) in `app/batch/src/main/kotlin/com/meogo/app/batch/promotion/CandidatePromotionMapper.kt`
- [X] T015 [US1] `FoodPromotionJob.run()`(findPromotable page-0 재조회 루프 → isComplete 재검증 → 매핑 → `foods.save` → `markPublished`) in `app/batch/src/main/kotlin/com/meogo/app/batch/promotion/FoodPromotionJob.kt`
- [X] T016 [US1] `FoodPromotionJobRunner`(`ApplicationRunner`, `meogo.promotion.runner.enabled` 게이트) + `PromotionJobConfig`(빈 조립) in `app/batch/src/main/kotlin/com/meogo/app/batch/promotion/FoodPromotionJobRunner.kt`, `.../promotion/PromotionJobConfig.kt`

**Checkpoint**: US1 단독으로 "생성→(픽스처 완성)→승격→food" 완결.

---

## Phase 4: User Story 2 — enrichment 안전 부분 채움 (P2)

**목표**: 서로 다른 컬럼(성분·번역)을 독립·동시 갱신해도 서로 덮지 않는다.
**Independent Test**: 한 candidate 에 성분 업데이트 후 번역 업데이트 → 두 컬럼 모두 보존.

- [X] T017 [P] [US2] Red: 컬럼-스코프 보존 통합 테스트(updateSubstanceMapping 후 updateDescriptionTranslations → 성분 보존, 반대 순서도) with Testcontainers in `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/research/FoodCandidateColumnScopeSpec.kt`
- [X] T018 [US2] `FoodCandidateJpaRepository` 에 `@Modifying` 컬럼-스코프 `updateSubstanceMapping`/`updateDescriptionTranslations`(엔티티 로드 없이 해당 컬럼만 UPDATE, clearAutomatically) + Adapter 위임 in `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/research/FoodCandidateJpaRepository.kt`, `.../research/FoodCandidateRepositoryAdapter.kt`

**Checkpoint**: KB-54/KB-94 가 얹을 컬럼-스코프 seam 이 동시성 안전하게 준비됨.

---

## Phase 5: User Story 3 — 멱등 재실행·부분 실패 복원·대량 IO (P3)

**목표**: 재실행 중복 0, 한 건 실패가 나머지를 막지 않음, 대량 IO 억제.
**Independent Test**: 완성/미완성/이미승격 혼합에서 두 번 실행 → 중복 적재 0·완성분만.

- [X] T019 [P] [US3] Red→Green(characterization): 멱등 재실행 테스트(승격 두 번 → food row 중복 0·이미 published 제외) with Testcontainers in `app/batch/src/test/kotlin/com/meogo/app/batch/promotion/FoodPromotionIdempotencySpec.kt`
- [X] T020 [P] [US3] Red: 부분 실패 격리 테스트(한 음식 저장 실패 → 나머지 적재·실패분 잔류, @TestConfiguration 데코레이터 실패주입) in `app/batch/src/test/kotlin/com/meogo/app/batch/promotion/FoodPromotionPartialFailureSpec.kt`
- [X] T021 [US3] `FoodPromotionJob` 건별 격리(try/catch continue + skip-set strictly-shrinking 종료조건) in `app/batch/src/main/kotlin/com/meogo/app/batch/promotion/FoodPromotionJob.kt`
- [X] T022 [US3] 대량 IO 점검: `findPromotable` 페이지네이션·`markPublished` 컬럼-스코프 단일문·`rewriteBatchedStatements` 확인(코드변경 없음, 규칙 부합)

**Checkpoint**: 멱등·부분실패·IO 규칙 검증됨.

---

## Phase 6: Polish & Cross-Cutting

- [X] T023 [P] ArchUnit `ModuleBoundaryTest`(+`ErrorCodeStatusTest`) 확인: `FoodCandidateJpaEntity`(@Entity) 는 `:infra:persistence` 에만, `:core:research` ORM-free, 의존 방향 무손상 — 통과 in `app/api/src/test/kotlin/com/meogo/app/api/architecture/ModuleBoundaryTest.kt`
- [X] T024 [P] 승격 완료 집계 로그(total/promoted/failed) — `FoodPromotionJob.run(): PromotionResult` 반환 + runner 로깅 in `app/batch/src/main/kotlin/com/meogo/app/batch/promotion/FoodPromotionJob.kt`, `.../FoodPromotionJobRunner.kt`
- [X] T025 로컬 docker MySQL(8.4) 스크래치 DB 에 마이그레이션 적용·`SHOW CREATE TABLE` 검증(엔티티↔DDL 컬럼·길이·타입·UNIQUE·인덱스 일치 확인, 앱 부팅 없이 docker exec — 8080 무충돌)
- [X] T026 [P] KB-54/KB-94 seam 점검: `updateSubstanceMapping`(KB-54)/`updateDescriptionTranslations`(KB-94) 컬럼-스코프 시그니처가 후속 티켓 소비에 충분(SC-004 테스트 완료)·contracts 갱신(findPromotable keyset 반영) in `specs/kb-96-food-candidate-pipeline/contracts/internal-contracts.md`

---

## Dependencies & 실행 순서

```
Setup(T001-T003) → Foundational(T004-T012) → ┬ US1(T013-T016)  [MVP]
                                              ├ US2(T017-T018)  ∥ US1 가능
                                              └ US3(T019-T022)  (US1 이후)
                                                      → Polish(T023-T026)
```

- **Foundational 이 최대 병목** — T004→T005→T006→T007→T009, T008(Red)은 T009 전. T010→T011(Red)→T012.
- **US1 ∥ US2**: US1 은 `app/batch`, US2 는 `infra/persistence` 어댑터 업데이트 메서드 — 파일 대체로 분리(둘 다 `FoodCandidateJpaRepository`/`Adapter` 를 건드리면 소충돌, 순차 권장 or 메서드 미리 stub).
- **US3 는 US1 이후**(승격 잡 존재 전제).

## Parallel 예시

- Setup: `T002 ∥ T003`.
- Foundational: `T004`(도메인 테스트)는 T008/T011(영속 테스트)과 병렬 작성 가능.
- Story 간: **US1 ∥ US2** (다른 모듈). US3 Red 테스트 `T019 ∥ T020`.
- Polish: `T023 ∥ T024 ∥ T026`.

## Implementation Strategy

- **MVP = Setup + Foundational + US1**(완성분만 food 로 승격되는 골격 e2e). 여기까지면 KB-96 의 핵심 가치가 픽스처로 완결된다.
- **증분**: US2(컬럼-스코프 seam — KB-54/KB-94 착수 해금) → US3(멱등·부분실패·대량 IO 견고화) → Polish(ArchUnit·로그·마이그레이션 검증).
- **후속 해금**: US2 완료 시 KB-54(성분→candidate)·KB-94(번역→candidate)가 이 seam 위에서 병렬 착수 가능.
