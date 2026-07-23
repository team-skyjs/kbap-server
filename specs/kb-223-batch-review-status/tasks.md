# Tasks: 배치 완성 콘텐츠를 검수 대기(PENDING_REVIEW)로 저장

**Input**: Design documents from `specs/kb-223-batch-review-status/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, quickstart.md

**Tests**: Test-First **NON-NEGOTIABLE**(헌법 원칙 I) — Red 확인 후 구현.

**Organization**: 유저 스토리 1개(US1)뿐이라 Setup/Foundational 단계는 없다(기존 프로젝트, 신규 모듈·의존성 없음).

## Format: `[ID] [P?] [Story] Description`

## Phase 1: User Story 1 - 배치 완성 콘텐츠는 검수 대기로 저장된다 (Priority: P1) 🎯 MVP

**Goal**: 배치의 완비 전이 목적지를 READY → PENDING_REVIEW 로 변경. 사용자 노출은 READY 화이트리스트 필터가 자동 차단(기존 쿼리 무변경 — 테스트로 고정).

**Independent Test**: 4작업 완비 음식이 배치 처리 후 PENDING_REVIEW 로 저장되고 목록·검색·랜덤 조회에 나타나지 않으면 성공.

### Tests for User Story 1 (Red — 구현 전 작성·실패 확인) ⚠️

- [X] T001 [US1] `domain/food/src/test/kotlin/com/kbap/domain/food/model/FoodReadyTransitionTest.kt` 를 `FoodPendingReviewTransitionTest.kt` 로 리네임하고 새 계약으로 재작성: 4작업 완비 시 `transitionToPendingReviewIfComplete()` true + `contentStatus == PENDING_REVIEW`(READY 아님) / 미완비 시 false + INCOMPLETE 유지 / PENDING_REVIEW·READY 상태에서 호출 시 즉시 true·상태 불변(멱등) / 기피성분 0건(빈 목록)은 완비 인정. 컴파일 실패(미구현 심벌) 또는 assertion 실패로 Red 확인
- [X] T002 [P] [US1] `domain/food/src/test/kotlin/com/kbap/domain/food/FoodJpaRepositoryTest.kt` 전이 호출부를 새 메서드명으로 갱신하고 비노출 케이스 추가: PENDING_REVIEW 음식이 `findFoodPageIds`·`searchFoodPageIds`·`findRandomReadyIds` 결과에서 제외됨(READY 음식만 반환). Red 확인

### Implementation for User Story 1 (Green)

- [X] T003 [US1] `domain/food/src/main/kotlin/com/kbap/domain/food/model/FoodContentStatus.kt` 에 `PENDING_REVIEW` 추가(순서: INCOMPLETE, PENDING_REVIEW, READY)
- [X] T004 [US1] `domain/food/src/main/kotlin/com/kbap/domain/food/model/Food.kt` — `columnDefinition = "ENUM('INCOMPLETE','PENDING_REVIEW','READY')"` 로 확장, `transitionToReadyIfComplete` → `transitionToPendingReviewIfComplete` 리네임, 가드 `contentStatus != INCOMPLETE → return true`, 완비 시 `PENDING_REVIEW` 전이 (research.md D1·D2)
- [X] T005 [US1] `app/batch/src/main/kotlin/com/kbap/app/batch/content/FoodContentBatchConfig.kt` writer 의 호출부를 `transitionToPendingReviewIfComplete()` 로 갱신
- [X] T006 [P] [US1] Flyway 마이그레이션 생성 `app/api/src/main/resources/db/migration/V2026.07.23.<생성시각 HH.mm.ss>__food_content_status_pending_review.sql`: `ALTER TABLE food MODIFY content_status enum('INCOMPLETE','PENDING_REVIEW','READY') NOT NULL DEFAULT 'READY';` (기존 행 무변경 — research.md D3·D4)
- [X] T007 [US1] Green 확인: `./gradlew :domain:food:test :app:batch:test`

**Checkpoint**: US1 완결 — 배치 완성 음식이 PENDING_REVIEW 로 저장되고 비노출.

---

## Phase 2: Polish & Cross-Cutting

- [X] T008 전체 회귀: `./gradlew build` (ArchUnit·api 통합 테스트 — Hibernate schema-generation 이 엔티티 columnDefinition 에서 3값 ENUM 생성, `FoodServiceTest` 의 content_status 미정의값 케이스 회귀 확인 — research.md 리스크 노트)

---

## Dependencies & Execution Order

- T001·T002: 선행 없음, 서로 [P] — 먼저 작성·Red 확인
- T003 → T004 → T005 순차(심벌 의존). T006 은 언제든 [P]
- T007 은 T003–T006 완료 후, T008 은 T007 후

## Parallel Example

```text
동시 착수 가능: T001 + T002 (Red), 이후 T006 + (T003→T004→T005)
```

## Implementation Strategy

스토리 1개 = MVP 그 자체. T001→T008 한 사이클로 완결하고 커밋한다. 반려/승인(후속 브랜치)과의 통합 지점은 `FoodContentStatus.PENDING_REVIEW` 값 하나뿐이라 추가 준비 작업은 없다.
