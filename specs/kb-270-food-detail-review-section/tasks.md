# Tasks: 음식 상세 리뷰 섹션 응답 개편

**Input**: Design documents from `/specs/kb-270-food-detail-review-section/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-response.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). Every user story MUST include failing tests written BEFORE its implementation (Red → Green → Refactor).

**Organization**: Tasks are grouped by user story. 세 스토리가 같은 파일 3개(`FoodDetailResponse.kt`·`FoodController.kt`·테스트)를 순차로 다듬는 구조라 스토리 간 병렬 여지는 없고, 우선순위 순서(P1→P2→P3)로 진행한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

## Path Conventions

`:api` 모듈 기준 — 소스 `api/src/main/kotlin/com/kbap/api/`, 테스트 `api/src/test/kotlin/com/kbap/api/`. DB·`:common`·`:infra` 변경 없음(plan.md).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 개편 전 기준선 확인 — 신규 인프라 없음

- [x] T001 기준선 그린 확인 — `./gradlew :api:test --tests "com.kbap.api.food.*" -Dkotest.tags="!arch"` 통과 상태에서 시작 (기존 `FoodDetailRatingTest` 포함)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 없음 — DB 스키마·인증·라우팅 등 선행 인프라 변경이 없는 조회 응답 개편이다(research.md: COUNT 집계 유지 결정으로 마이그레이션 자체가 소거됨). 바로 Phase 3 진행.

**Checkpoint**: T001 그린이면 유저 스토리 착수 가능

---

## Phase 3: User Story 1 - 회원이 음식 상세에서 리뷰 요약을 하나의 섹션으로 본다 (Priority: P1) 🎯 MVP

**Goal**: 리뷰 평탄 필드 3개를 `review` 중첩 객체로 응집 — 상세 응답의 리뷰 관련 값은 묶음 안에서만 제공

**Independent Test**: 회원 토큰 MockMvc 조회로 `payload.review.{averageRating,reviewCount,sameCountryAverageRating}` 존재 + 최상위 동명 필드 부재 확인

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [x] T002 [US1] `api/src/test/kotlin/com/kbap/api/food/FoodDetailReviewSectionTest.kt` 신규 — BehaviorSpec(given/when/then 한국어): 리뷰 3건 시드 후 회원 조회 시 ① `payload.review` 에 전체 평균·개수 3·같은 국적 평균이 담긴다 ② 최상위 `averageRating`·`reviewCount`·`sameCountryAverageRating` 키가 없다. 작성 직후 실행해 **Red 확인**
- [x] T003 [US1] 기존 `api/src/test/kotlin/com/kbap/api/food/FoodDetailRatingTest.kt` 의 평탄 필드 단언을 `payload.review.*` 경로로 이행(검증 의미 보존) — 이 시점엔 구현 전이라 **Red 확인**

### Implementation for User Story 1

- [x] T004 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodDetailResponse.kt` — 평탄 3필드 제거, `review: ReviewSummaryResponse` 추가(중첩 data class — averageRating·reviewCount·sameCountryAverageRating, `@Schema` 설명 포함). 이 단계에선 기존 nullable 타입 유지(0.0 계약은 US3)
- [x] T005 [US1] `api/src/main/kotlin/com/kbap/api/food/FoodController.kt` — `FoodDetailResponse.from` 조립을 review 묶음으로 수정, T002·T003 **Green 확인** 후 리팩터
- [x] T006 [US1] `./gradlew :api:test -Dkotest.tags="!arch"` — 상세 응답을 참조하는 다른 테스트 회귀 확인·수정

**Checkpoint**: 회원 상세 조회가 review 묶음으로 내려감 — US1 단독 배포 가능(MVP)

---

## Phase 4: User Story 2 - 비회원에게는 리뷰 요약이 가려진다 (Priority: P2)

**Goal**: `blur` 불리언 추가 — 비회원 true(수치 서버측 차단·집계 생략), 회원 false

**Independent Test**: 무인증 MockMvc 조회로 `payload.review.blur == true` + 수치 기본값, 회원 조회로 `blur == false` + 실수치 확인

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [x] T007 [US2] `FoodDetailReviewSectionTest.kt` 에 추가 — 리뷰 있는 음식을 ① 비회원 조회: `blur=true` 이고 실수치(평균·개수)가 응답에 노출되지 않는다 ② 회원 조회: `blur=false` 이고 실수치가 내려간다. **Red 확인**

### Implementation for User Story 2

- [x] T008 [US2] `FoodDetailResponse.kt` — `ReviewSummaryResponse` 에 `blur: Boolean` 추가(`@Schema` 설명: 비회원 가림, "리뷰 없음"과 구분용)
- [x] T009 [US2] `FoodController.kt` — 비회원(`memberId == null`) 분기: `getFoodRatingSummary` 호출 없이 blur 고정 요약 반환(집계 0회 — research.md 부수 결정), 회원은 `blur=false`. T007 **Green 확인** 후 리팩터

**Checkpoint**: 비회원/회원 응답이 계약(contracts 케이스 4종 중 3종) 충족 — US1+US2 독립 검증 가능

---

## Phase 5: User Story 3 - 평점이 없어도 응답 값 형태가 일정하다 (Priority: P3)

**Goal**: 평균 별점 null 폐기 — 리뷰 없음·같은 국적 없음·국적 미보유 전부 `0.0` (수치 필드 non-null 계약)

**Independent Test**: 리뷰 0건 음식을 회원 조회해 `averageRating=0.0`·`reviewCount=0`·`sameCountryAverageRating=0.0` 확인

### Tests for User Story 3 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [x] T010 [US3] `FoodDetailReviewSectionTest.kt` 에 추가 — ① 리뷰 0건 회원 조회: 세 수치가 0.0·0·0.0(JSON null 아님) ② 전체 리뷰는 있고 같은 국적만 없음: `sameCountryAverageRating=0.0`, 나머지 실수치. **Red 확인**

### Implementation for User Story 3

- [x] T011 [US3] `FoodDetailResponse.kt` — `ReviewSummaryResponse` 수치 필드를 non-null(`Double`·`Long`)로 확정하고 조립 지점에서 `?: 0.0` 변환(`RatingSummary` 는 nullable 유지 — 변환은 응답 경계 소유, research.md). T010 **Green 확인** 후 리팩터

**Checkpoint**: contracts/food-detail-response.md 케이스 4종 전부 충족

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: 계약 문서화 마감·회귀 전체 확인

- [x] T012 [P] Swagger `@Schema` 설명 최종 점검 — `review` 각 필드에 기본값(0.0)·blur 의미가 담겼는지 `FoodDetailResponse.kt` 확인·보강 (FR-007)
- [x] T013 `./gradlew :api:test` 전체 그린(ArchUnit 포함) — `ModuleBoundaryTest` 위반 없음 확인
- [x] T014 quickstart.md 수동 검증 — contracts 케이스 4종은 MockMvc 통합 테스트(FoodDetailReviewSectionTest)가 실 HTTP 계층·Testcontainers MySQL 로 검증 완료(로컬 bootRun curl 은 시크릿 필요 시 선택 수행)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 즉시 시작
- **Foundational (Phase 2)**: 없음 — 통과
- **User Stories (Phase 3~5)**: 세 스토리가 동일 파일(`FoodDetailResponse.kt`·`FoodController.kt`·`FoodDetailReviewSectionTest.kt`)을 수정하므로 **순차 진행(P1→P2→P3)** — 파일 충돌로 스토리 간 병렬 불가
- **Polish (Phase 6)**: US1~US3 완료 후

### Within Each User Story

- 테스트 작성 → **Red 확인** → 구현 → **Green 확인** → 리팩터 (Constitution I)
- T002·T003 은 같은 사이클의 Red 를 구성(신규 계약 + 기존 테스트 이행) — 둘 다 Red 확인 후 T004~T005 로 Green

### Parallel Opportunities

- 이 기능은 단일 개발자·순차 흐름이 최적 — [P] 는 T012(문서 점검, 타 태스크와 파일 겹침 없음) 하나뿐
- 스토리 간 병렬은 같은 파일 3개를 공유해 의도적으로 배제

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 기준선 → T002~T006 (US1)
2. **STOP and VALIDATE**: review 묶음 구조 단독 검증 — FE(KB-73)에 계약 공유 가능 시점
3. US2(blur)·US3(0.0)를 같은 브랜치에서 순차 증분

### Incremental Delivery

- US1 구조 개편 → US2 blur → US3 0.0 순으로 각 체크포인트마다 `:api` 테스트 그린 유지 — 어느 지점에서 멈춰도 계약 일관성 유지(US1 단독이면 nullable 유지 상태의 중첩 구조)
- 각 태스크/논리 단위마다 커밋 (Development Workflow)

---

## Notes

- 총 14 태스크 / US1 5개(T002~T006) · US2 3개(T007~T009) · US3 2개(T010~T011) · Setup 1 · Polish 3
- 테스트 스타일: Kotest BehaviorSpec, given/when/then 한국어, MockMvc + `@AutoConfigureMockMvc`, MySQL Testcontainers (CLAUDE.md 고정 컨벤션)
- DB·Flyway·`:common` 무변경 — research.md 의 COUNT 집계 유지 결정이 전제. 이 결정이 뒤집히면 tasks 재생성 필요
