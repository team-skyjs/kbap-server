# Tasks: 관리자 음식 상세 모달 UX 개선 — 목록 스크롤 유지·이미지 렌더링

**Input**: Design documents from `/specs/kb-259-admin-food-modal-ux/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-food-pages.md

**Tests**: Test-First **NON-NEGOTIABLE** (헌법 원칙 I) — 각 스토리는 실패하는 테스트를 먼저 작성하고 Red 확인 후 구현한다. 전부 Kotest BehaviorSpec(given/when/then 한국어) + MockMvc.

**Organization**: 스토리별 독립 구현·검증. US1(스크롤 유지)과 US2(이미지 렌더링)는 서로 독립 — 파일이 일부 겹치므로(`food-list.html`) 순차 진행을 기본으로 한다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup / Phase 2: Foundational

**없음** — 신규 모듈·의존성·스키마·인증 변경이 없다. 기존 `:api` admin 패키지와 테스트 인프라(Testcontainers·MockMvc)를 그대로 사용하므로 바로 스토리 구현에 들어간다.

---

## Phase 3: User Story 1 - 상세 모달을 여닫아도 목록 위치가 유지된다 (Priority: P1) 🎯 MVP

**Goal**: 상세 열기·닫기·저장(성공/실패) 전 과정에서 `#food-<id>` anchor 로 목록 행 위치를 유지한다.

**Independent Test**: `AdminFoodPageControllerTest`(redirect fragment)·`AdminFoodListControllerTest`(행 anchor·링크 fragment)만으로 검증 가능. 수동으로는 quickstart.md 1·2·4·5 단계.

### Tests for User Story 1 (Red — 작성 후 반드시 실패 확인) ⚠️

- [x] T001 [P] [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt`에 (구현 시 정정: 수정 redirect 테스트가 이 파일 소속) 저장 redirect 5분기(UPDATED·NOT_FOUND·INVALID_NAME·INVALID_JSON·DUPLICATE_NAME)의 Location 이 `#food-<id>` fragment 로 끝나는지 검증하는 then 추가 — 실행해 Red 확인
- [x] T002 [P] [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt`에 목록 렌더링 검증 추가: 각 행에 `id="food-<id>"` anchor 존재, 상세보기 링크 href 가 `detail=<id>` + `#food-<id>` 포함, 모달 닫기 링크 href 가 `#food-<id>` 포함 — 실행해 Red 확인

### Implementation for User Story 1 (Green → Refactor)

- [x] T003 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt`의 `updateFood` redirect 문자열 5분기 전부에 `#food-$id` fragment 추가 (T001 Green)
- [x] T004 [US1] `api/src/main/resources/templates/admin/food-list.html` 수정: 행 `.food-row`에 `th:id="|food-${f.id}|"`, 상세보기 링크에 fragment `#food-<id>`, 모달 닫기 링크에 fragment `#food-<id>` (T002 Green — Thymeleaf URL 표현식의 fragment 문법 주의)
- [x] T005 [US1] `./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodPageControllerTest" --tests "com.kbap.api.admin.AdminFoodListControllerTest"` 전체 그린 확인 후 필요 시 리팩터(중복 fragment 조립 정리 등)

**Checkpoint**: 스크롤 유지 단독 배포 가능 — MVP.

---

## Phase 4: User Story 2 - 상세 모달에서 실제 이미지를 확인한다 (Priority: P2)

**Goal**: 상세 모달에서 `imageRef`를 공개 URL 로 해석해 실제 사진을 렌더링하고, 키 부재·로드 실패 시 플레이스홀더를 표시한다.

**Independent Test**: `AdminFoodServiceTest`(imageUrl 해석)·`AdminFoodListControllerTest`(모달 img/플레이스홀더 렌더링)만으로 검증 가능. 수동으로는 quickstart.md 3단계.

### Tests for User Story 2 (Red — 작성 후 반드시 실패 확인) ⚠️

- [x] T006 [P] [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodServiceTest.kt`에 `getFoodDetailOrNull` imageUrl 해석 검증 추가: imageRef 존재 → base URL 결합값, imageRef null → null, imageRef 가 절대 URL → 원문 유지 (`ImageUrls.resolve` 계약, 선례: `AdminMemberQueryService`) — 실행해 Red 확인
- [x] T007 [P] [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt`에 상세 모달 렌더링 검증 추가: imageRef 있는 음식의 `detail` 조회 응답에 해석된 URL 의 `<img>` 존재, imageRef 없는 음식은 `<img>` 대신 플레이스홀더 요소 존재 — 실행해 Red 확인

### Implementation for User Story 2 (Green → Refactor)

- [x] T008 [US2] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt`에 `@Value("\${kbap.storage.public-base-url:}") imagePublicBaseUrl` 주입, `AdminFoodDetailView`에 `imageUrl: String?` 필드 추가 및 `ImageUrls.resolve` 로 채움 (T006 Green — data-model.md 참조)
- [x] T009 [US2] `api/src/main/resources/templates/admin/food-list.html` 모달에 이미지 블록 추가: `imageUrl != null`이면 `<img th:src="${foodDetail.imageUrl}">` + `onerror` 시 플레이스홀더 대체 표시, null 이면 플레이스홀더만 렌더 — 기존 `imageRef` 입력 필드는 유지 (T007 Green)
- [x] T010 [US2] `./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodServiceTest" --tests "com.kbap.api.admin.AdminFoodListControllerTest"` 전체 그린 확인 후 필요 시 리팩터

**Checkpoint**: 두 스토리 모두 독립 동작.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [x] T011 `./gradlew :api:test` 전체 실행 — 기존 admin·food 테스트 전부 그린 확인 (FR-006 회귀 게이트)
- [ ] T012 quickstart.md 수동 시나리오 1~5 로 local 확인 (선택 — 배포 전 스모크)

---

## Dependencies & Execution Order

- **Setup/Foundational 없음** → 바로 US1 부터 시작.
- **US1 (T001→T005)**: 의존 없음. T001·T002 는 [P](서로 다른 테스트 파일). T003·T004 는 각자의 Red 테스트 이후, T005 는 T003·T004 이후.
- **US2 (T006→T010)**: US1 과 논리 독립이나 `food-list.html`·`AdminFoodListControllerTest.kt`를 공유하므로 **US1 완료 후 순차 진행 권장**. T006·T007 은 [P].
- **Polish (T011~T012)**: 모든 스토리 완료 후.

## Parallel Example: User Story 1

```bash
# Red 테스트 동시 작성 (서로 다른 파일):
Task: "AdminFoodPageControllerTest 에 redirect fragment 검증 추가"
Task: "AdminFoodListControllerTest 에 행 anchor·링크 fragment 검증 추가"
```

## Implementation Strategy

- **MVP = US1**: T001~T005 완료 시점에 스크롤 유지만으로 배포 가치 있음.
- **Incremental**: US1 검증 → US2 추가 → T011 회귀 게이트 → 커밋·PR.
- 각 태스크(또는 Red+Green 논리 단위)마다 커밋한다.
