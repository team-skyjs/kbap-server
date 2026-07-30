# Tasks: 관리자 음식 수정 안정성 — 편집 모드 토글·상태 자동 전이

**Input**: Design documents from `specs/kb-260-admin-food-edit-safety/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-food-edit-pages.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 스토리는 실패하는 테스트(Red 확인)를 구현보다 먼저 작성한다.

**Organization**: 유저 스토리별 독립 구현·검증. 두 스토리는 서로 다른 파일을 만져 완전히 독립적이다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 태스크 의존 없음)
- **[Story]**: US1(편집 토글) / US2(상태 자동 보정)

## Path Conventions

모듈러 모놀리스 — 변경은 전부 `:api` 모듈. 테스트는 기존 파일 확장(신규 파일 없음). 스키마·`:common` 무변경.

---

## Phase 1: Setup

**Purpose**: 기준선 확인 — 신규 인프라 없음(기존 admin SSR·테스트 픽스처 재사용)

- [X] T001 기존 admin 테스트 기준선 green 확인: `./gradlew :api:test --tests "com.kbap.api.admin.*"` (Red 판정의 대조군)

---

## Phase 2: Foundational (Blocking Prerequisites)

**없음** — 기존 인증 인터셉터·화면·서비스·도메인 전이 메서드를 그대로 재사용한다. 유저 스토리 바로 시작 가능.

---

## Phase 3: User Story 1 - 상세 모달 읽기 전용 기본 + 편집 토글 (Priority: P1) 🎯 MVP

**Goal**: 상세 모달이 읽기 전용으로 열리고, `edit` 쿼리 파라미터로만 입력·저장이 활성화되며, 취소 시 DB 값으로 재렌더된다.

**Independent Test**: MockMvc 로 `GET /admin/foods/list?page=1&detail={id}` 렌더 HTML 에 `disabled`·저장 버튼 부재·'편집' 링크를, `&edit=true` 렌더에 입력 활성·저장 버튼·'취소' 링크를 검증 — US2 없이 단독 배포 가능.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T002 [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt` 에 BehaviorSpec 시나리오 추가 후 **Red 확인**: (1) `detail` 만 주면 전 입력 필드 `disabled`·저장 버튼 부재·'편집' 링크(`edit=true` 포함 URL) 존재 (2) `detail`+`edit=true` 면 입력 활성(disabled 부재)·저장 버튼 존재·'취소' 링크(edit 없는 URL) 존재 (3) 저장 검증 실패 리다이렉트 URL 이 `detail`+`edit=true` 를 유지 — `./gradlew :api:test --tests "*.AdminFoodListControllerTest"` 실패 출력 확보

### Implementation for User Story 1

- [X] T003 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` 수정: `foodList` 에 `@RequestParam(required = false) edit: Boolean?` 추가 → `model.addAttribute("editMode", detail != null && edit == true)`, `updateFood` 의 invalid-name·invalid-json·duplicate-name 리다이렉트에 `&edit=true` 부착
- [X] T004 [US1] `api/src/main/resources/templates/admin/food-list.html` 수정: 모달 전 입력에 `th:disabled="${!editMode}"`, 저장 버튼 `th:if="${editMode}"`, 편집 모드에 '취소' 링크(edit 제거 URL)·읽기 전용에 '편집' 링크(`edit=true` URL) 분기
- [X] T005 [US1] Green 확인 + Refactor: `./gradlew :api:test --tests "*.AdminFoodListControllerTest"` 통과, 기존 시나리오 회귀 없음 확인

**Checkpoint**: 편집 토글 단독으로 완전 동작 — 실수 덮어쓰기 경로 차단(MVP).

---

## Phase 4: User Story 2 - 저장 시 콘텐츠 완성도 기반 상태 자동 보정 (Priority: P2)

**Goal**: 저장 성공 시 검수 이전 상태(INCOMPLETE·PENDING_IMAGE)는 완성도로 재계산되고, 검수 단계(PENDING_REVIEW·READY) 수동 지정은 보존된다.

**Independent Test**: `AdminFoodServiceTest` 에서 완성도 조합별 `updateFood` 호출 후 저장된 `contentStatus` 검증 — US1(화면) 없이 서비스 레벨에서 단독 검증 가능.

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T006 [P] [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodServiceTest.kt` 에 BehaviorSpec 시나리오 추가 후 **Red 확인**: (1) 검수 이전 상태 선택 + 텍스트 완비·이미지 없음 → `PENDING_IMAGE` (2) 검수 이전 상태 선택 + 텍스트 완비·이미지 있음 → `PENDING_REVIEW` (3) 검수 이전 상태 선택 + 텍스트 미완 → `INCOMPLETE` (4) `READY`·`PENDING_REVIEW` 수동 지정 → 그대로 유지 (5) 검증 실패(중복 이름 등) → 상태 무변경 — `./gradlew :api:test --tests "*.AdminFoodServiceTest"` 실패 출력 확보

### Implementation for User Story 2

- [X] T007 [US2] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` 수정: `updateFood` 필드 대입 마지막(`avoidanceSubstances` 대입 뒤, `return UPDATED` 앞)에 `food.transitionByContentState()` 호출 1줄 추가
- [X] T008 [US2] Green 확인 + Refactor: `./gradlew :api:test --tests "*.AdminFoodServiceTest"` 통과 확인

**Checkpoint**: 두 스토리 모두 독립 동작 — 상태 불일치 휴먼 에러 해소.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T009 전체 게이트: `./gradlew build` (ArchUnit `arch` 태그 포함) 통과 확인 — admin 외 회귀(특히 `AdminFoodPageControllerTest`·시드/이미지 화면 테스트) 점검
- [ ] T010 quickstart.md 수동 검증: `SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun` 후 상세보기→읽기 전용 확인→편집→취소(원값 복원)→INCOMPLETE 인 완비 음식 저장→목록 배지 보정 확인

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (T001)**: 즉시 시작 — Red 판정 대조군이므로 스토리 착수 전 1회.
- **Phase 2**: 없음(스킵).
- **Phase 3 (US1)·Phase 4 (US2)**: T001 후 **서로 독립** — 순서 무관, 병렬 가능(만지는 파일이 전혀 겹치지 않음: US1=Controller·템플릿·ListControllerTest / US2=Service·ServiceTest).
- **Phase 5**: 두 스토리 완료 후.

### Within Each User Story

- Red(테스트 작성+실패 확인) → Green(구현) → Refactor 순서 엄수 (헌법 원칙 I).
- US1: T002 → T003·T004(서로 다른 파일이지만 같은 테스트를 Green 으로 만드는 한 묶음) → T005.
- US2: T006 → T007 → T008.

### Parallel Opportunities

- T006(US2 Red)은 T002~T005(US1 전체)와 병렬 가능 — 유일한 [P].
- US1 과 US2 를 서로 다른 작업자/세션이 동시에 진행 가능.

---

## Implementation Strategy

**MVP = US1**: T001 → T002~T005 로 실수 덮어쓰기 차단부터 배포 가능. US2(T006~T008)는 저장 품질 보강으로 뒤따른다. 태스크/논리 단위마다 커밋한다.
