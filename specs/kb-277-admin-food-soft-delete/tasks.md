# Tasks: 관리자 음식 삭제(소프트) 기능

**Input**: Design documents from `specs/kb-277-admin-food-soft-delete/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-food-delete.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). Every user story MUST include failing tests written BEFORE its implementation (Red → Green → Refactor).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)

## Path Conventions

전부 기존 파일 확장 — 신규 파일·모듈·마이그레이션 없음 (plan.md Structure Decision).

- 서비스/컨트롤러: `api/src/main/kotlin/com/kbap/api/admin/`
- 템플릿: `api/src/main/resources/templates/admin/food-list.html`
- 테스트: `api/src/test/kotlin/com/kbap/api/admin/`

---

## Phase 1: Setup (Shared Infrastructure)

**없음** — 기존 `com.kbap.api.admin` 기능 패키지·기존 테스트 인프라(Testcontainers·MockMvc)를 그대로 사용한다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**없음** — 스키마 변경 없음(BaseEntity `status`·`@SQLRestriction` 재사용), 인증도 기존 `AdminPageAuthInterceptor` 가 `/admin/**` 를 이미 보호한다.

**Checkpoint**: 바로 User Story 구현 시작 가능.

---

## Phase 3: User Story 1 - 관리자가 잘못 시드된 음식을 삭제한다 (Priority: P1) 🎯 MVP

**Goal**: 상세 패널 삭제 버튼 → confirm → `POST /admin/foods/{id}/delete` → 소프트 삭제 → 현재 페이지 유지 redirect + 완료 배너. 미존재·기삭제는 `error=not-found` redirect.

**Independent Test**: `AdminFoodServiceTest`·`AdminFoodPageControllerTest` 만으로 검증 — 삭제 성공(상태 전이·redirect)과 미존재 실패가 독립 확인된다.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T001 [P] [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodServiceTest.kt`에 `deleteFood` BehaviorSpec 시나리오 추가: (1) 존재하는 음식 삭제 → `DELETED` 반환 + `getFoodDetailOrNull` null, (2) 미존재 id → `NOT_FOUND`, (3) 같은 id 재삭제 → `NOT_FOUND`. 컴파일 실패(메서드 부재)로 Red 확인.
- [X] T002 [P] [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt`에 `POST /admin/foods/{id}/delete` 시나리오 추가(기존 관리자 쿠키 인증 패턴 재사용): (1) 성공 → 302 `/admin/foods/list?page={page}&deleted={id}`, (2) 미존재 id → 302 `...&error=not-found`, (3) 삭제 후 `GET /admin/foods/list?deleted={id}` 응답 본문에 삭제 완료 배너 문구 포함. Red 확인.

### Implementation for User Story 1

- [X] T003 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt`에 `deleteFood(id: Long): AdminFoodDeleteResult`(`@Transactional`, `findById` null → NOT_FOUND, 존재 시 `food.delete()`) + `enum AdminFoodDeleteResult { DELETED, NOT_FOUND }` 구현 — T001 Green.
- [X] T004 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt`에 `@PostMapping("/admin/foods/{id}/delete")` 핸들러 추가(`page` 폼 파라미터 보정은 기존 `updateFood` 와 동일, 결과별 redirect — contracts/admin-food-delete.md) — T002 (1)(2) Green.
- [X] T005 [US1] `api/src/main/resources/templates/admin/food-list.html` 수정: 상세 패널 modal-foot(비편집 모드)에 삭제 폼(POST, hidden page, `onsubmit="return confirm(...)"` — `food-images.html` 선례) 추가, 목록 상단에 `param.deleted` 완료 배너 추가(기존 `updated` 배너 패턴) — T002 (3) Green.
- [X] T006 [US1] `./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodServiceTest" --tests "com.kbap.api.admin.AdminFoodPageControllerTest"` 전체 Green 확인 후 리팩터(중복 정리).

**Checkpoint**: 관리자 삭제 흐름 완결 — MVP 배포 가능.

---

## Phase 4: User Story 2 - 삭제된 음식은 어디에도 노출되지 않는다 (Priority: P2)

**Goal**: 삭제 후 관리자 목록·앱 사용자 조회(검색·상세·북마크·리뷰 진입)에서 비노출임을 테스트로 고정한다. research.md R2 — 기존 `@SQLRestriction`·`mapNotNull`·`getReadyFood` 메커니즘으로 자동 달성이므로 **구현 태스크 없음**, 회귀 방지 테스트만 추가한다(즉시 Green 이어도 무방 — 신규 동작이 아니라 기존 보장의 고정).

**Independent Test**: 음식 시드 → 삭제 → 각 조회 경로 assertion 만으로 독립 검증.

### Tests for User Story 2 (회귀 고정 — 신규 구현 없음)

- [X] T007 [P] [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodServiceTest.kt`에 시나리오 추가: 삭제된 음식이 `getFoodPage` items 에 미포함(totalCount 에서도 제외).
- [X] T008 [P] [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt`에 시나리오 추가: READY 음식 삭제 후 사용자 API 대표 경로에서 비노출 — `GET /api/v1/foods` 검색/목록 응답에 미포함(기존 사용자 API 테스트 시드·호출 패턴 재사용, 시드 음식명은 스펙 간 유일 접두어 — quickstart.md 주의).

**Checkpoint**: 비노출 보장이 회귀 테스트로 고정됨.

---

## Phase 5: User Story 3 - 동명 재시드 제약을 삭제 화면에서 안내한다 (Priority: P3)

**Goal**: 삭제 confirm 문구·패널 상시 안내에 "삭제된 이름은 계속 점유되어 같은 이름 재시드가 조용히 누락된다" 를 명시한다.

**Independent Test**: 상세 패널 렌더 응답 본문의 안내 문구 존재만으로 독립 검증.

### Tests for User Story 3 (REQUIRED — Test-First) ⚠️

- [X] T009 [P] [US3] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt`에 시나리오 추가: `GET /admin/foods/list?detail={id}` 응답 본문에 재시드 누락 안내 문구 포함. Red 확인.

### Implementation for User Story 3

- [X] T010 [US3] `api/src/main/resources/templates/admin/food-list.html` 삭제 폼 영역에 상시 안내 문구를 추가하고 confirm 문구에도 동일 경고 포함 — T009 Green.

**Checkpoint**: 전 스토리 완결.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T011 `./gradlew :api:test` 로 admin·food·bookmark·review 관련 스펙 회귀 확인(전체 api 테스트), quickstart.md 수동 확인 절차 점검.

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1·2: 태스크 없음 — 즉시 Phase 3 시작.
- **US1 (Phase 3)**: 선행 없음. T001·T002 (Red, 병렬) → T003 → T004 → T005 → T006.
- **US2 (Phase 4)**: T003(deleteFood 존재) 이후 가능. T007·T008 병렬.
- **US3 (Phase 5)**: T005(삭제 폼 존재) 이후 가능. T009 → T010.
- **Polish (Phase 6)**: 전 스토리 완료 후.

### 파일 충돌 주의

`AdminFoodServiceTest.kt`(T001·T007)·`AdminFoodPageControllerTest.kt`(T002·T008·T009)·`food-list.html`(T005·T010)은 여러 태스크가 같은 파일을 만진다 — 스토리 순차 진행 시 문제 없음, 병렬 진행 시 같은 파일 태스크는 직렬화.

### Parallel Opportunities

- T001 ∥ T002 (US1 Red, 서로 다른 파일)
- T007 ∥ T008 (US2, 서로 다른 파일)
- US2 와 US3 는 US1 완료 후 서로 병렬 가능(단 T008·T009 는 같은 파일 — 직렬)

---

## Parallel Example: User Story 1

```bash
# Red 테스트 동시 작성 (작성 후 반드시 실패 확인):
Task: "AdminFoodServiceTest 에 deleteFood 시나리오 추가 (T001)"
Task: "AdminFoodPageControllerTest 에 POST delete 시나리오 추가 (T002)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 3 (T001~T006) 완료 → 삭제 기능 자체는 배포 가능 (US2 는 기존 메커니즘이 이미 보장, US3 는 안내 문구).
2. STOP and VALIDATE: 두 테스트 클래스 Green + quickstart 수동 확인.

### Incremental Delivery

1. US1 → 삭제 동작 (MVP)
2. US2 → 비노출 회귀 고정
3. US3 → 안내 문구
4. Polish → 전체 회귀

---

## Notes

- 스키마 변경 금지 — Flyway 마이그레이션을 만들지 않는다 (data-model.md).
- 테스트 시드 정리 시 `food` 물리 DELETE 전 `food_review` 선삭제, 시드 음식명 유일 접두어 (quickstart.md 함정).
- 모든 테스트는 Kotest BehaviorSpec, given/when/then 한국어 (CLAUDE.md 고정).
- 태스크/논리 단위마다 커밋.
