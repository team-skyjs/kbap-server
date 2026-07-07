---
description: "Task list for 메뉴 목록 조회 (무한 스크롤, no-offset)"
---

# Tasks: 메뉴 목록 조회 (무한 스크롤, no-offset)

**Input**: Design documents from `specs/kb-63-menu-list-cursor/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/menu-list-api.md, quickstart.md

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 유저스토리의 테스트를 구현 **전에** 먼저 작성하고 Red 를 확인한다(Kotest BehaviorSpec, 한국어 given/when/then).

**Organization**: 유저스토리별로 그룹핑. 본 기능은 단일 엔드포인트(`GET /api/v1/foods`)라 US1 이 수직 슬라이스를 깔고 US2/US3 가 의미·경계를 얹는다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1/US2/US3 매핑(추적성)
- 모든 경로는 저장소 루트 기준

## Path Conventions

- 도메인 포트: `core/food/src/main/kotlin/com/meogo/core/food/`
- 영속: `infra/persistence/src/{main,test}/kotlin/com/meogo/infra/persistence/food/`
- 유스케이스: `application/client/src/{main,test}/kotlin/com/meogo/application/client/food/usecase/`
- web: `app/api/src/{main,test}/kotlin/com/meogo/app/api/food/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 재사용 범위 확정 — 신규 모듈·마이그레이션 없음

- [X] T001 기존 food 조회 계층(`core/food/FoodRepository.kt`, `infra/persistence/food/FoodRepositoryAdapter.kt`·`FoodJpaRepository.kt`, `application/client/food/usecase/GetFoodDetailUseCase.kt`)의 위치·시그니처와 재사용 협력자(`LanguageResolver`·`AvoidedSubstanceProvider`·`AvoidanceSubstanceRepository`) 를 확인하고, 신규 모듈·Flyway 마이그레이션이 불필요함을 확정한다(정렬·커서 키 = PK `id`).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 공유하는 순수 데이터 계약(로직 없음 → 전용 테스트 불요). 이 파일들은 포트/어댑터 없이도 독립 컴파일된다.

**⚠️ CRITICAL**: 유저스토리 착수 전 완료

- [X] T002 [P] `application/client/.../usecase/BrowseMenusInput.kt`·`BrowseMenusResult.kt` 생성 — `BrowseMenusInput(cursor: Long?, lang: String?)`, `BrowseMenusResult(items, nextCursor: Long?, hasNext)` + 중첩 `MenuSummaryView(foodId, name, imageRef, spiciness, overallRiskStatus: RiskLevel)` (data-model.md §3).
- [X] T003 [P] `app/api/common/Page.kt` + `app/api/food/MenuSummaryResponse.kt` 생성 — 공유 봉투 `Page<T>(items: List<T>, hasNext: Boolean, nextCursor: Long? = null)` (`com.meogo.app.api.common`, 향후 검색 재사용) + `MenuSummaryResponse(foodId, name, imageRef, spiciness: Int, overallRiskStatus: String)` 및 `from(view)` 매퍼(RiskLevel.name). 본 API 응답 타입은 `Page<MenuSummaryResponse>`. (data-model.md §4, contracts)

**Checkpoint**: 공유 DTO 컴파일 통과 — 스토리 구현 착수 가능

---

## Phase 3: User Story 1 - 메뉴를 무한 스크롤로 둘러본다 (Priority: P1) 🎯 MVP

**Goal**: 커서 없이 첫 20개(최신순)를 받고, `nextCursor` 로 중복 없이 다음 20개를 이어 받는 keyset 목록 수직 슬라이스.

**Independent Test**: 시드 다건에서 첫 페이지(20개·`hasNext`·`nextCursor`) → 그 커서로 재조회 시 직전 최소 foodId 미만 항목만 중복 없이 온다.

### Tests for User Story 1 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T004 [P] [US1] `application/client/.../usecase/BrowseMenusUseCaseTest.kt` 작성 — 페이크 `FoodRepository` 로 21개 반환 시 `hasNext=true`·`nextCursor=items.last().foodId`·`items.size==20`, 커서 지정 시 `findMenuPage(cursor, 21)` 호출·최신순 유지. Red 확인.
- [X] T005 [P] [US1] `infra/persistence/food/FoodRepositoryAdapterTest.kt` 에 keyset 슬라이스 추가(MySQL Testcontainers) — `findMenuPage(null, 20)` 최신순 상위 20, `findMenuPage(cursorId, 20)` 는 `id < cursorId` 만, 소프트삭제(DELETED) food 제외. Red 확인.
- [X] T006 [P] [US1] `app/api/food/MenuListControllerTest.kt` 작성(MockMvc `@SpringBootTest`) — 첫 페이지 200·`payload.items` 20개·`hasNext`·`nextCursor` 존재·`success=true`, 다음 커서 연속 조회 시 foodId 교집합 공집합. Red 확인.

### Implementation for User Story 1

- [X] T007 [US1] `core/food/FoodRepository.kt` 에 `fun findMenuPage(cursor: Long?, size: Int): List<Food>` 포트 메서드 추가(계약: cursor 있으면 `id < cursor`, 없으면 전체에서 `id` 내림차순 상위 size).
- [X] T008 [US1] `infra/persistence/food/FoodJpaRepository.kt` 에 `findMenuPageIds(cursor, pageable)`(keyset, 컬렉션 미조인, `order by f.id desc`) + `findByIdInWithAvoidanceSubstancesDesc(ids)`(`join fetch`, `order by f.id desc`) 쿼리 추가 (data-model.md §2).
- [X] T009 [US1] `infra/persistence/food/FoodRepositoryAdapter.kt` 에 `findMenuPage` 2단계 구현 — id keyset 조회 → 비면 emptyList, 아니면 id-in fetch join → `toDomain()` 매핑 (research R2). T007·T008 의존.
- [X] T010 [US1] `application/client/.../usecase/BrowseMenusUseCase.kt` 구현 — `@Transactional(readOnly=true)`, `PAGE_SIZE=20`, `findMenuPage(cursor, PAGE_SIZE+1)` → `hasNext = rows.size > PAGE_SIZE`·`items = rows.take(PAGE_SIZE)`·`nextCursor = if(hasNext) items.last().id else null`, `MenuSummaryView` 매핑(foodId·name=`displayName(lang)`·imageRef·spiciness.value). 위험도 상세정합은 US2 에서 확정. T002·T007 의존.
- [X] T011 [US1] `app/api/food/MenuListController.kt` + `MenuListApi.kt`(Swagger `@Tag` "음식 목록") 구현 — `@RequestMapping(ApiPaths.V1 + "/foods")`, `@GetMapping` `browse(@RequestParam(required=false) cursor, @RequestParam(required=false) lang)` → `ResponseEntity<BaseResponse<Page<MenuSummaryResponse>>>` = `BaseResponse.ok(Page(items = result.items.map(MenuSummaryResponse::from), hasNext = result.hasNext, nextCursor = result.nextCursor))`. T003·T010 의존.

**Checkpoint**: 무한 스크롤 목록(최신순·keyset·연속 페이지) 독립 동작·검증

---

## Phase 4: User Story 2 - 각 항목에서 상세로 이어갈 정보를 본다 (Priority: P2)

**Goal**: 각 항목이 foodId + 리치 카드(name 지역화·imageRef·spiciness·상세와 동일 의미의 overallRiskStatus)를 담는다.

**Independent Test**: 회피 성분 포함 food 의 `overallRiskStatus` 가 상세 종합 위험도와 동일하고, `lang=en` 표시명이 영어(번역부재 시 ko), 항목에 숫자 foodId 포함.

### Tests for User Story 2 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T012 [P] [US2] `BrowseMenusUseCaseTest.kt` 보강 — 사용자 회피 ∩ 성분 위험도가 food 별 정확(소프트삭제 카탈로그 성분 미반영), `lang=en` 표시명 지역화·미지정 시 ko, `foodId` 매핑 검증. Red 확인.
- [X] T013 [P] [US2] `MenuListControllerTest.kt` 보강 — `lang=en` 지역화 응답, 항목 필드 계약(`foodId`·`imageRef`·`spiciness`(0~10)·`overallRiskStatus`∈{SAFE,CAUTION,DANGER,UNKNOWN}). Red 확인.

### Implementation for User Story 2

- [X] T014 [US2] `BrowseMenusUseCase.kt` 위험도 정합 — `avoidedSubstanceProvider.avoidedCodes()` 1회 + `avoidanceSubstanceRepository.findByCodes(페이지 전체 성분코드 합집합)` 1회 → food 별 `overallRisk(avoidedCodes ∩ 카탈로그존재코드)` 인메모리 계산해 `MenuSummaryView.overallRiskStatus` 채움(상세 `GetFoodDetailUseCase` 와 동일 의미, N+1 회피). T010 확장.
- [X] T015 [US2] `MenuSummaryResponse.from` 매핑 완전성 확인 — `overallRiskStatus = view.overallRiskStatus.name`, `spiciness = view.spiciness`, nullable `imageRef` 전달. `Page.nextCursor` 는 `result.nextCursor`(Long?) 그대로 전달(문자열 변환 없음). (T003 데이터클래스 검증)

**Checkpoint**: US1 + US2 리치 카드·상세 식별자·언어 동작

---

## Phase 5: User Story 3 - 경계·빈 결과·오류를 명확히 처리한다 (Priority: P3)

**Goal**: 빈 결과 200, 마지막 페이지, 잘못된 커서 400, 미지원 언어 400 을 구분 처리.

**Independent Test**: 0건 → `items:[]`·`hasNext:false`, 잘못된 커서 → 400, 미지원 lang → 400(지원목록), 20 배수 경계 다음 요청 → 빈 페이지.

### Tests for User Story 3 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T016 [P] [US3] `BrowseMenusUseCaseTest.kt` 보강 — 결과 0개 시 `items:[]`·`hasNext:false`·`nextCursor:null`, 남은 ≤20 시 `hasNext:false`. Red 확인.
- [X] T017 [P] [US3] `MenuListControllerTest.kt` 보강 — 빈 결과 200, 잘못된 커서(음수·비숫자) 400·`success:false`, 미지원 lang 코드 400(지원 언어 안내). Red 확인.

### Implementation for User Story 3

- [X] T018 [US3] 커서 검증 추가 — 파싱 불가·음수 커서를 400 으로(`IllegalArgumentException` 또는 신규 `FoodErrorCode.INVALID_CURSOR(400)`) 던져 `GlobalExceptionHandler` 가 `BaseResponse.fail(message)` 반환하게 한다(research R6). 위치: `MenuListController` 파싱 지점.
- [X] T019 [US3] 미지원 lang 400 경로 확인 — `LanguageResolver.resolve(lang)` 재사용으로 미지원 코드 400+지원목록(원칙 V), 빈 결과는 usecase 정상 흐름(200)임을 확인. 필요 시 컨트롤러/usecase 배선만 조정.

**Checkpoint**: 모든 스토리 독립 동작 — 경계/오류 구분 완결

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T020 [P] `./gradlew :app:api:test --tests "com.meogo.app.api.architecture.ModuleBoundaryTest"` — 의존 방향·경계 무손상 확인.
- [X] T021 [P] `./gradlew build` 전체 통과 + 로컬 docker MySQL 로 수동 스모크(`curl "/api/v1/foods"` → nextCursor 로 연속) (quickstart.md).
- [X] T022 Swagger UI "음식 목록" 태그·응답 스키마 노출 확인, `contracts/menu-list-api.md` 불변식 5종과 대조.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 의존 없음 — 즉시 시작.
- **Foundational (Phase 2)**: Setup 후. 순수 DTO — 모든 스토리 선행.
- **User Stories (Phase 3~5)**: Foundational 후. US1(수직 슬라이스)이 포트/어댑터/usecase/controller 를 깔면 US2/US3 는 같은 usecase·controller·테스트를 확장한다.
- **Polish (Phase 6)**: 원하는 스토리 완료 후.

### User Story Dependencies

- **US1 (P1)**: Foundational 후 시작 — 다른 스토리 무의존. MVP.
- **US2 (P2)**: US1 의 `BrowseMenusUseCase`·`MenuListController` 위에 위험도·언어 의미를 얹음(T014 는 T010 확장).
- **US3 (P3)**: US1 의 컨트롤러·usecase 위에 커서 검증·오류 매핑을 얹음(T018 은 T011 확장).

### Within Each User Story

- 테스트 먼저 작성·FAIL 확인(원칙 I) → 포트 → JPA 쿼리 → 어댑터 → usecase → controller.
- 스토리 완료 후 다음 우선순위로.

### Parallel Opportunities

- T002·T003(Foundational DTO) 병렬.
- 각 스토리 테스트(T004/T005/T006, T012/T013, T016/T017)는 서로 다른 파일이라 병렬 작성.
- 구현 태스크(T007~T011)는 포트→쿼리→어댑터→usecase→controller 로 의존 사슬이라 대체로 순차.

---

## Parallel Example: User Story 1

```bash
# US1 테스트 3종 먼저 작성(모두 FAIL 확인):
Task: "BrowseMenusUseCaseTest 페이지네이션 해피패스 in application/client/.../BrowseMenusUseCaseTest.kt"
Task: "FoodRepositoryAdapterTest keyset 슬라이스 in infra/persistence/food/FoodRepositoryAdapterTest.kt"
Task: "MenuListControllerTest 첫 페이지+연속 in app/api/food/MenuListControllerTest.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → Phase 2 Foundational(DTO) → Phase 3 US1.
2. **STOP & VALIDATE**: 첫 페이지·연속 커서 독립 검증.
3. 데모 가능(스크롤 목록).

### Incremental Delivery

1. Setup + Foundational → 공유 DTO 준비.
2. US1 → 무한 스크롤 목록(MVP) → 검증.
3. US2 → 리치 카드(위험도·언어) → 검증.
4. US3 → 경계·오류 → 검증.

### Notes

- Kotlin 주석 금지(고정) · BehaviorSpec 한국어 given/when/then(고정).
- 컬렉션 fetch-join + limit 단일 쿼리 금지 → 반드시 2단계.
- 상세 foodId 정합(FR-013)은 KB-98 별도 태스크 — 본 목록은 상세 엔드포인트 미변경.
- 신규 Flyway 마이그레이션 추가 금지(스키마 변경 없음).
