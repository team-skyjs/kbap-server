# Tasks: 관리자 음식 목록 음식명 검색

**Input**: Design documents from `/specs/kb-276-admin-food-search/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/admin-food-list-page.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 실패 테스트(Red) 작성·확인 후 구현한다.

**Organization**: 스토리별 독립 구현·검증. 기존 기능 확장이라 Setup/Foundational 단계는 없다(신규 인프라·스키마 없음).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 선행 미완 의존 없음)
- **[Story]**: US1(검색 필터링)·US2(검색 상태 유지)·US3(빈 결과 안내·초기화)

## Path Conventions

기존 멀티모듈 경로 그대로 — `common/src/main/kotlin/...`, `api/src/{main,test}/kotlin/...`, `api/src/main/resources/templates/admin/`.

---

## Phase 1: User Story 1 - 음식명으로 목록 검색 (Priority: P1) 🎯 MVP

**Goal**: `GET /admin/foods/list?q=김치` 가 음식명 부분 일치 목록을 반환하고, `q` 없음/blank 는 기존 전체 목록 그대로.

**Independent Test**: 검색어 유무별 `getFoodPage` 서비스 검증 + MockMvc 로 `q` 파라미터 목록 필터링 확인.

### Tests for User Story 1 (Red 먼저 — 실패 확인 필수) ⚠️

- [X] T001 [P] [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodServiceTest.kt` 에 검색 조회 given 추가 — ① 검색어 부분 일치 음식만 반환(무관 음식 제외) ② 검색어 null/blank/공백뿐 → 전체 목록과 동일 ③ 검색어 앞뒤 공백 무시 ④ 검색 결과 기준 totalCount·페이지네이션 ⑤ 반환 View 의 `query` 에 확정 검색어 탑재. 실행해 컴파일 실패/Red 확인
- [X] T002 [P] [US1] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt` 에 `q` 파라미터 given 추가 — `/admin/foods/list?q=김치` 응답 HTML 에 일치 음식만 렌더·불일치 음식 부재, `q` 생략 시 전체 렌더. 실행해 Red 확인

### Implementation for User Story 1

- [X] T003 [US1] `common/src/main/kotlin/com/kbap/common/domain/food/FoodJpaRepository.kt` 에 파생 쿼리 `fun findByKoreanNameContaining(koreanName: String, pageable: Pageable): Page<Food>` 추가
- [X] T004 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodService.kt` — `getFoodPage(page: Int, query: String? = null)` 로 확장: trim 후 blank 면 기존 `findAll`, 값 있으면 `findByKoreanNameContaining`. `AdminFoodListPageView` 에 `query: String?` 필드 추가(확정 검색어, 없으면 null)
- [X] T005 [US1] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` `foodList` — `@RequestParam(required = false) q: String?` 수신해 서비스에 전달
- [X] T006 [US1] `api/src/main/resources/templates/admin/food-list.html` — 목록 상단에 GET 검색 폼(input name="q", 현재 검색어 초기값) 추가. T001·T002 Green 확인 후 리팩터

**Checkpoint**: 검색 입력 → 필터된 목록. 기존 전체 목록 동작 불변(회귀 테스트 통과).

---

## Phase 2: User Story 2 - 검색 상태 유지 (Priority: P2)

**Goal**: 검색 상태에서 페이지 이동·상세·닫기·편집·취소 링크와 수정 후 redirect 가 `q`를 유지한다.

**Independent Test**: MockMvc 로 검색 상태 화면의 링크 href 에 `q` 포함 확인 + `POST /admin/foods/{id}` redirect Location 에 인코딩된 `q` 유지 확인.

### Tests for User Story 2 (Red 먼저 — 실패 확인 필수) ⚠️

- [X] T007 [US2] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt` 에 given 추가 — ① `?q=김치` 화면의 페이지 이동·상세·편집 링크 href 가 `q=김치` 유지 ② `POST /admin/foods/{id}` 에 `q` 폼 필드 제출 시 성공/실패 redirect Location 이 URL 인코딩된 `q` 유지(앵커 `#food-{id}` 보존) ③ `q` blank 제출 시 redirect 에 `q` 파라미터 없음. 실행해 Red 확인

### Implementation for User Story 2

- [X] T008 [US2] `api/src/main/kotlin/com/kbap/api/admin/AdminFoodPageController.kt` `updateFood` — `@RequestParam(required = false) q: String?` 수신, 기존 문자열 보간 redirect 5종을 `UriComponentsBuilder` 조립으로 교체(한글 인코딩, blank 면 `q` 생략, 기존 page·detail·edit·error·앵커 유지)
- [X] T009 [US2] `api/src/main/resources/templates/admin/food-list.html` — 페이지 이동(이전/다음)·상세보기·닫기·편집·취소 링크에 `q=${foodPage.query}` 추가(빈 값이면 생략), 수정 폼에 hidden input `q` 추가. T007 Green 확인 후 리팩터

**Checkpoint**: 검색 → 편집 → 저장 → 같은 검색 결과 복귀. US1 단독 동작 불변.

---

## Phase 3: User Story 3 - 빈 결과 안내와 검색 초기화 (Priority: P3)

**Goal**: 결과 0건이면 빈 목록 안내 + 전체 목록 복귀(검색 초기화) 링크 표시.

**Independent Test**: 없는 음식명 검색 시 안내 문구·초기화 링크 렌더 확인, 링크가 `q` 없는 목록 경로인지 확인.

### Tests for User Story 3 (Red 먼저 — 실패 확인 필수) ⚠️

- [X] T010 [US3] `api/src/test/kotlin/com/kbap/api/admin/AdminFoodListControllerTest.kt` 에 given 추가 — 일치 없는 검색어로 요청 시 빈 목록 안내 문구와 `/admin/foods/list`(q 없음) 초기화 링크가 렌더. 실행해 Red 확인

### Implementation for User Story 3

- [X] T011 [US3] `api/src/main/resources/templates/admin/food-list.html` — 검색 상태 + items 비어 있음 조건의 빈 결과 안내 블록(안내 문구 + 전체 목록 링크) 추가. T010 Green 확인 후 리팩터

**Checkpoint**: 세 스토리 모두 독립 검증 완료.

---

## Phase 4: Polish & Cross-Cutting Concerns

- [X] T012 quickstart.md 시나리오 수동 점검 가능 상태 확인 + `./gradlew :api:test` 전체 회귀(ArchUnit 포함) 통과 확인
- [X] T013 [P] `./gradlew :common:test` 회귀 확인(리포지토리 파생 쿼리 추가 영향)

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup/Foundational 없음 — US1 부터 즉시 시작 가능
- **US1 (Phase 1)**: 선행 없음. `q` 전달 경로와 View `query` 필드를 만들므로 US2·US3 의 사실상 선행
- **US2 (Phase 2)**: US1 완료 후(View `query` 필드·서비스 시그니처 의존)
- **US3 (Phase 3)**: US1 완료 후(검색 상태 렌더 의존). US2 와는 독립 — 병렬 가능하나 같은 파일(`food-list.html`·`AdminFoodListControllerTest.kt`)을 만지므로 순차 권장
- **Polish (Phase 4)**: 전 스토리 완료 후

### Within Each User Story

- Red(테스트 작성·실패 확인) → Green(최소 구현) → Refactor 순서 엄수
- US1 내부: T003(리포지토리) → T004(서비스) → T005(컨트롤러) → T006(템플릿) 순차 — 시그니처 의존 체인

### Parallel Opportunities

- T001 ‖ T002 (서로 다른 테스트 파일)
- T012 ‖ T013 (서로 다른 모듈 테스트)
- US2·US3 는 파일이 겹쳐 병렬 비권장(위 참조)

---

## Parallel Example: User Story 1

```bash
# Red 테스트 두 개를 병렬 작성 (작성 후 반드시 실패 확인):
Task: "AdminFoodServiceTest 검색 조회 given 추가"        # T001
Task: "AdminFoodListControllerTest q 파라미터 given 추가"  # T002

# 실패 확인:
./gradlew :api:test --tests "com.kbap.api.admin.AdminFoodServiceTest" \
                    --tests "com.kbap.api.admin.AdminFoodListControllerTest"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1(US1) 완료 → 검색 필터링 단독 배포 가능(MVP)
2. **STOP and VALIDATE**: 검색어 유무별 목록·기존 회귀 통과 확인
3. US2(상태 유지) → US3(빈 결과) 순 증분 — 각 체크포인트에서 독립 검증

### Incremental Delivery

- 각 스토리 완료 시점마다 커밋(작업/논리 단위 커밋 규약). US1 만으로도 가치 전달, US2 가 "찾아서 수정" 동선 완성, US3 이 완성도 마감.

---

## Notes

- 스키마·마이그레이션·신규 파일 없음 — 기존 4개 소스 + 테스트 2개 수정으로 완결
- `food-list.html`·`AdminFoodPageController.kt` 는 US2·US3 에서 재수정되므로 스토리 간 순차 진행이 충돌 최소화
- 병행 브랜치 `kb-277-admin-food-soft-delete` 가 같은 화면을 수정 중 — 머지 순서 뒤인 쪽이 rebase (research.md R4)
