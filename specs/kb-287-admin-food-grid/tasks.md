# Tasks: 관리자 음식 목록 화면 개편 — 카드 그리드·상태 필터·상세 모달

**Input**: Design documents from `/specs/kb-287-admin-food-grid/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-food-list-pages.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 실패하는 테스트(Red)를 먼저 작성·확인한 뒤 구현(Green)한다. 테스트는 Kotest BehaviorSpec + MockMvc 렌더 검증(Testcontainers MySQL — Docker 필요).

**Organization**: 유저 스토리별 독립 구현·검증 가능하도록 그룹화. 같은 파일(`food-list.html`·`admin.css`·테스트 2종)을 여러 스토리가 순차 수정하므로 스토리 간 병렬 실행은 권하지 않는다 — 우선순위 순서대로 진행.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 기존 프로젝트·기존 화면 개편이라 초기화 작업이 없다. 신규 의존성·마이그레이션·설정 변경 없음.

---

## Phase 2: Foundational

없음 — 모든 변경이 스토리 소속이다. 공유 선행 작업 없음.

---

## Phase 3: User Story 1 - 카드 그리드 + 고정 높이 내부 스크롤 (Priority: P1) 🎯 MVP

**Goal**: 행 목록 → 정사각 카드 그리드(썸네일·음식명·상태 배지), 목록 영역 고정 높이 + 내부 스크롤, 검색·페이지네이션 고정.

**Independent Test**: `/admin/foods/list` 렌더 결과에 `.food-grid-viewport > .food-grid` 와 카드별 썸네일(`imageUrl`)/플레이스홀더·음식명·상태 배지가 존재하고, 기존 행 마크업(`.food-row`)이 사라졌는지 MockMvc 로 검증. 브라우저에서 내부 스크롤 확인(quickstart §1).

### Tests (Red 먼저 — 실패 확인 후 구현) ⚠️

- [X] T001 [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt` 개편 — 카드 그리드 렌더 Red 테스트: (1) `.food-grid-viewport`·`.food-grid` 마크업 존재, (2) `imageRef` 있는 음식 카드에 resolve 된 `imageUrl` `<img loading="lazy">`, 없는 음식 카드에 플레이스홀더, (3) 카드에 음식명·상태 배지(6종 배지 클래스 매핑 — READY→ok·INCOMPLETE→neutral·PENDING_*→progress·REVIEW_REJECTED→warn·REVIEWED→info), (4) 0건 시 empty-state + 뷰포트 유지, (5) 구 `.food-row` 마크업 부재. 실행해 **Red 확인**

### Implementation (Green → Refactor)

- [X] T002 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` — `AdminFoodSummaryView` 에 `imageUrl: String?` 추가(`ImageUrls.resolve(imagePublicBaseUrl, food.imageRef)`), `hasImage` 제거, `from(food, imagePublicBaseUrl)` 시그니처 변경 및 `getFoodPage` 호출부 반영
- [X] T003 [US1] `api/src/main/resources/templates/admin/food-list.html` — 목록 블록을 `.food-grid-viewport`(고정 높이 스크롤 컨테이너) > `.food-grid` 카드 그리드로 개편: 카드 = 썸네일 `<img loading="lazy" onerror=플레이스홀더 폴백>` 또는 `.image-placeholder`, 음식명(한 줄 말줄임), 상태 배지, 상세보기 링크. 검색 폼·배너·페이지네이션은 뷰포트 밖 유지. empty-state 를 뷰포트 안으로
- [X] T004 [P] [US1] `api/src/main/resources/static/assets/admin.css` — `.food-grid-viewport`(height calc + overflow-y auto), `.food-grid`(`repeat(auto-fill, minmax(180px, 1fr))`), `.food-card`(정사각 썸네일 `aspect-ratio:1; object-fit:cover`, 이름 말줄임, 배지 배치), 카드용 플레이스홀더. 구 `.food-card-list`·`.food-row*` 규칙 삭제
- [X] T005 [US1] T001 테스트 **Green 확인** + `./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodListControllerTest"` 통과

**Checkpoint**: 카드 그리드 MVP 완성 — 필터·모달 없이도 배포 가능한 상태

---

## Phase 4: User Story 2 - content_status 필터 (Priority: P2)

**Goal**: `status` 쿼리 파라미터로 상태 필터(전체 + 6종 select), 검색어와 AND 결합, 페이지 이동·건수에 반영, 알 수 없는 값은 무시.

**Independent Test**: 상태 섞인 시드 후 `?status=PENDING_REVIEW` 조회 → 해당 상태만 + totalCount 일치, `q` 동시 적용, `?status=없는값` → 전체 목록 200 OK.

### Tests (Red 먼저) ⚠️

- [X] T006 [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt` — 상태 필터 Red 테스트: (1) `status` 단독 필터(해당 상태만 + totalCount 반영), (2) `q`+`status` AND, (3) 알 수 없는 `status` → 200 + 전체 목록(FR-011), (4) 필터 select 마크업(전체 + 6종, 선택 상태 유지), (5) 페이지네이션·상세보기 링크에 `status` 스레딩, (6) 해당 상태 0건 → empty-state. 실행해 **Red 확인**

### Implementation

- [X] T007 [P] [US2] `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt` — 파생 쿼리 2개 추가: `findByContentStatus(contentStatus, pageable): Page<Food>`, `findByKoreanNameContainingAndContentStatus(koreanName, contentStatus, pageable): Page<Food>`
- [X] T008 [US2] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` — `getFoodPage(page, query, status: FoodContentStatus? = null)` (keyword × status) 4분기, `AdminFoodListPageView` 에 `status` 필드 추가
- [X] T009 [US2] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` — `foodList` 에 `status: String?` 수신, `FoodContentStatus.entries.find { it.name == status }` 관대 파싱 후 서비스 전달
- [X] T010 [US2] `api/src/main/resources/templates/admin/food-list.html` — 검색 폼에 `status` `<select>`(전체 + `T(...FoodContentStatus).values()` 열거, 선택 유지) 추가, 페이지네이션·상세보기·empty-state 링크에 `status` 스레딩
- [X] T011 [US2] T006 테스트 **Green 확인**

**Checkpoint**: 그리드 + 필터 동작 — US1 회귀 없음(`AdminFoodListControllerTest` 전체 통과)

---

## Phase 5: User Story 3 - 상세 모달 전환 (Priority: P2)

**Goal**: 우측 패널 → 네이티브 `<dialog>` 모달. 기존 read-only 오픈·편집 토글·삭제 확인·유효성 오류 재오픈 계약 유지. 닫기/저장/삭제 후 목록 위치(페이지·q·status·스크롤) 유지 — 앵커 폐기, sessionStorage 복원.

**Independent Test**: `?detail={id}` 렌더에 `.food-modal` `<dialog>` + showModal 스크립트, 닫기 링크가 `detail` 제거 URL. 저장/삭제 리다이렉트가 `status` 포함 + `#food-` fragment 없음. 유효성 오류 시 `detail+edit+error` 재오픈.

### Tests (Red 먼저) ⚠️

- [X] T012 [US3] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt` — 모달 렌더 Red 테스트: (1) `detail` 지정 시 `<dialog class="food-modal">` 렌더 + `showModal()` 인라인 스크립트, (2) 구 `.food-panel` 부재, (3) 닫기 링크 = `detail`/`edit` 제거 + `page`·`q`·`status` 유지, (4) 읽기 모드 disabled·저장 버튼 부재 / `edit=true` 편집 모드(KB-260 계약 회귀 방지), (5) 모달 폼 hidden 필드에 `page`·`q`·`status`. 실행해 **Red 확인**
- [X] T013 [US3] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodPageControllerTest.kt` — 리다이렉트 Red 테스트: (1) 저장 성공/유효성 오류/삭제 리다이렉트가 `status` 파라미터 보존, (2) 리다이렉트 URL 에 `#food-` fragment 부재, (3) 유효성 오류 시 `detail+edit+error` 유지(기존 계약). 실행해 **Red 확인**

### Implementation

- [X] T014 [US3] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` — `updateFood`·`deleteFood` 에 `status: String?` 수신·스레딩, `listRedirect` 에서 `#food-$anchorId` fragment 제거 + `status` 쿼리 포함
- [X] T015 [US3] `api/src/main/resources/templates/admin/food-list.html` — `<aside class="food-panel">` → `<dialog class="food-modal">` 전환: 로드 시 `showModal()` 인라인 스크립트, `cancel` 이벤트(ESC) → 닫기 링크 이동, 닫기 링크·폼 hidden 필드(`page`·`q`·`status`) 정비, 상세 필드·삭제 confirm·오류 배너는 모달 안으로 이동
- [X] T016 [P] [US3] `api/src/main/resources/static/assets/admin.css` — `.food-modal`(`::backdrop` 포함, 최대 높이 + 내부 스크롤) 추가, 구 `.food-panel*` 규칙 삭제
- [X] T017 [US3] `api/src/main/resources/templates/admin/food-list.html` — 그리드 뷰포트 scrollTop sessionStorage 저장(scroll 이벤트)/복원(로드 시) 인라인 JS 추가
- [X] T018 [US3] T012·T013 테스트 **Green 확인** + quickstart §3 수동 확인(모달 오픈·ESC·스크롤 유지)

**Checkpoint**: 그리드 + 필터 + 모달 — 기존 편집·삭제 플로우 전부 모달 안에서 동작

---

## Phase 6: User Story 4 - 버튼 공통 규격 (Priority: P3)

**Goal**: 편집·저장·취소·삭제 버튼을 `.btn` 공통 규격 + 역할별 색(삭제=경고색)으로 통일. JSON 표시는 손대지 않는다(기존 textarea 유지).

**Independent Test**: 상세 모달 렌더에서 버튼 4종이 `.btn` + 역할 변형 클래스를 갖는지 확인.

### Tests (Red 먼저) ⚠️

- [X] T019 [US4] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt` — Red 테스트: 읽기 모드에 `.btn .btn-neutral`(편집)·`.btn .btn-danger`(삭제), 편집 모드에 `.btn .btn-primary`(저장)·`.btn .btn-neutral`(취소) 클래스. 실행해 **Red 확인**

### Implementation

- [X] T020 [US4] `api/src/main/resources/templates/admin/food-list.html` — 모달 푸터·삭제 폼의 버튼 4종에 `.btn` + 역할 변형 클래스 적용
- [X] T021 [P] [US4] `api/src/main/resources/static/assets/admin.css` — `.btn` 베이스(패딩·radius·min-width 고정) + `.btn-primary`/`.btn-neutral`/`.btn-danger` 변형(기존 토큰 재사용)
- [X] T022 [US4] T019 테스트 **Green 확인**

**Checkpoint**: 전 스토리 완료

---

## 구현 중 확인된 사실

- **JSON syntax highlighting 은 범위에서 제외**(2026-08-05 결정) — 이를 받치던 "깨진 JSON 방어"가 성립하지 않는다. `food` 의 JSON 컬럼(MySQL `JSON` 타입)이 유효하지 않은 값을 저장 단계에서 거부하므로(`Invalid JSON text`) 깨진 값이 화면에 도달할 수 없다. 남는 이득이 색상뿐이라 하이라이터·읽기 모드 마크업 분기·토큰 CSS 를 전부 걷어내고 기존 textarea 표시로 되돌렸다.
- **Thymeleaf 는 null URL 파라미터를 `q=`·`status=` 로 비워서 렌더한다**(기존 `q=` 와 동일한 선행 동작). 링크 단언은 이 형태를 그대로 반영한다.
- **수동 브라우저 확인은 미수행** — 마크업·리다이렉트·모델은 MockMvc 로 검증했으나 CSS 레이아웃(그리드 정렬·모달 백드롭·내부 스크롤·색상)은 quickstart 절차로 별도 확인이 필요하다.

---

## Phase 7: Polish & Cross-Cutting

- [X] T023 전체 검증 — `./gradlew :api:test` + `./gradlew build` 그린 확인(ArchUnit 포함), quickstart 수동 시나리오 전체 통과
- [X] T024 [P] 죽은 코드 정리 — `hasImage` 참조 잔재·미사용 CSS(`.food-card-list`·`.food-row*`·`.food-panel*`) 제거 확인, 템플릿 인라인 JS 중복 없는지 점검

---

## Dependencies

```text
Phase 3 (US1 그리드) ── T001(Red) → T002·T003·T004 → T005(Green)
        ↓ (같은 템플릿·테스트 파일 순차 수정)
Phase 4 (US2 필터) ── T006(Red) → T007[P]·T008 → T009 → T010 → T011(Green)
        ↓
Phase 5 (US3 모달) ── T012·T013(Red) → T014·T015·T016[P] → T017 → T018(Green)
        ↓
Phase 6 (US4 버튼·JSON) ── T019(Red) → T020·T021[P] → T022(Green)
        ↓
Phase 7 (Polish) ── T023 → T024
```

- 스토리는 개념상 독립이지만 **동일 파일(food-list.html·admin.css·테스트 2종)을 공유**하므로 우선순위 순 순차 진행이 실질적으로 안전하다.
- US2 의 T007(리포지토리)만 타 스토리와 파일이 겹치지 않는 진짜 병렬 후보.

## Parallel Example

각 스토리 안에서 CSS task 는 템플릿 task 와 파일이 달라 병렬 가능:

```text
US1: T002(서비스) 완료 후 → T003(템플릿) ∥ T004(CSS)
US3: T014(컨트롤러) 완료 후 → T015(템플릿) ∥ T016(CSS)
US4: T020(템플릿) ∥ T021(CSS)
US2: T007(리포지토리) ∥ T008(서비스) — 단 T008 이 T007 의 메서드를 호출하므로 T007 먼저가 단순
```

## Implementation Strategy

- **MVP = Phase 3 (US1)**: 카드 그리드만으로도 배포 가치가 있다. 여기서 멈춰도 된다.
- 이후 US2(필터) → US3(모달) → US4(가독성) 순 증분 딜리버리 — 각 체크포인트마다 `AdminFoodListControllerTest` 전체 통과로 회귀 확인.
- 스토리마다 Red 확인 없이 구현 금지(헌법 I). 각 스토리 완료 시 커밋.
