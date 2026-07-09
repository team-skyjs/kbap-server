---
description: "Task list for 검색어 메뉴 조회 (다국어 부분 일치, no-offset)"
---

# Tasks: 검색어 메뉴 조회 (다국어 부분 일치, no-offset)

**Input**: Design documents from `specs/kb-62-menu-search/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/menu-search-api.md, quickstart.md

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 유저스토리의 테스트를 구현 **전에** 먼저 작성하고 Red 를 확인한다(Kotest BehaviorSpec, 한국어 given/when/then).

**Organization**: 유저스토리별 그룹핑. 본 기능은 KB-63 목록 조회의 near-clone(단일 엔드포인트 `GET /api/v1/foods/search`)이라 US1 이 **검색 매칭 수직 슬라이스**를 깔고, US2 가 커서 연속성, US3 이 카드 의미·언어를 얹는다. 응답 봉투·항목 DTO·언어/커서/회피 협력자는 KB-63 것을 **재사용**한다(신규 없음).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1/US2/US3 매핑(추적성)
- 모든 경로는 저장소 루트 기준

## Path Conventions

- 도메인 포트/에러: `core/food/src/main/kotlin/com/meogo/core/food/`
- 영속: `infra/persistence/src/{main,test}/kotlin/com/meogo/infra/persistence/food/`
- 유스케이스: `application/client/src/{main,test}/kotlin/com/meogo/application/client/food/{dto,usecase}/`
- web: `app/api/src/{main,test}/kotlin/com/meogo/app/api/food/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 재사용 범위 확정 — 신규 모듈·마이그레이션 없음

- [X] T001 KB-63 목록 조회 계층(`core/food/FoodRepository.kt`, `infra/persistence/food/{FoodRepositoryAdapter,FoodJpaRepository}.kt`, `application/client/food/usecase/BrowseMenusUseCase.kt`, `app/api/{common/Page.kt,food/MenuSummaryResponse.kt}`, `application/client/food/dto/BrowseMenusResult.kt`)와 재사용 협력자(`LanguageResolver`·`resolveCursor`·`AvoidedSubstanceProvider`·`AvoidanceSubstanceRepository`·기존 `findByIdInWithAvoidanceSubstancesDesc`)의 위치·시그니처를 확인하고, 신규 모듈·Flyway 마이그레이션·인덱스가 불필요함을 확정한다(번역명은 기존 `name_translations` JSON 컬럼, leading-wildcard LIKE 풀스캔 수용 — research R3).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리가 공유하는 순수 데이터 계약·에러코드(로직 없음 → 전용 테스트 불요). 결과 DTO 는 `BrowseMenusResult`(+`MenuSummaryView`) 재사용이라 신규 생성하지 않는다.

**⚠️ CRITICAL**: 유저스토리 착수 전 완료

- [X] T002 [P] `application/client/.../food/dto/SearchMenusInput.kt` 생성 — `SearchMenusInput(keyword: String?, cursor: Long?, lang: String?)` (data-model.md §3). 결과는 기존 `BrowseMenusResult` 재사용.
- [X] T003 [P] `core/food/FoodErrorCode.kt` 에 `BLANK_SEARCH_KEYWORD(400, "검색어를 입력해 주세요")` 추가(FR-011, 정중한 종결형).

**Checkpoint**: 입력 DTO·에러코드 컴파일 통과 — 스토리 구현 착수 가능

---

## Phase 3: User Story 1 - 검색어로 먹고 싶은 메뉴를 찾는다 (Priority: P1) 🎯 MVP

**Goal**: 검색어가 **한국어명 또는 요청 언어 번역명**에 부분 일치(대소문자 비구분)하는 메뉴를 첫 20개(최신순) 내려주는 검색 수직 슬라이스. 결과 없음 200·빈 검색어 400 포함.

**Independent Test**: 한국어명/번역명 조각으로 검색해 그 조각을 포함하는 메뉴만 결과에 담기고, 미포함 검색어는 빈 목록 200, 공백 검색어는 400.

### Tests for User Story 1 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T004 [P] [US1] `application/client/.../food/usecase/SearchMenusUseCaseTest.kt` 작성 — 페이크 `FoodRepository`: 빈/공백 `keyword` → `BLANK_SEARCH_KEYWORD` 예외·`searchMenuPage` 미호출, `" 김치 "` → trim 되어 `searchMenuPage("김치", …)` 위임, 매칭 21개 반환 시 `hasNext=true`·`nextCursor=items.last().foodId`·`items.size==20`, 미지정 lang → `LanguageCode.KO` 전달. Red 확인.
- [X] T005 [P] [US1] `infra/persistence/food/FoodRepositoryAdapterTest.kt` 에 검색 슬라이스 추가(MySQL Testcontainers, 한국어명·번역명 JSON seed 다건) — `searchMenuPage("김치", KO, null, 20)` 한국어명 매칭, 영어 번역명 "Bibimbap" seed 를 `searchMenuPage("bibim", EN, null, 20)` 매칭(대소문자 비구분), 일본어 번역명만 가진 seed 를 `lang=EN` 검색 시 미포함(언어 분리), `lang=KO` 는 번역명 무시(한국어명만), 미포함 키워드 → `[]`, **소프트삭제(DELETED) food 는 매칭돼도 제외**(네이티브 `status='ACTIVE'`). Red 확인.
- [X] T006 [P] [US1] `app/api/food/MenuSearchControllerTest.kt` 작성(MockMvc `@SpringBootTest`) — `?keyword=…` 부분 일치 200·`payload.items` 매칭·`success=true`, 미포함 검색어 200 빈 목록(`hasNext:false`·`nextCursor:null`), **빈/공백 `keyword` → 400 `success:false`**(FR-011). Red 확인.

### Implementation for User Story 1

- [X] T007 [US1] `application/client/.../food/usecase/SearchKeywordResolver.kt` 생성 — `fun resolveKeyword(keyword: String?): String` = trim 후 blank 면 `FoodException(FoodErrorCode.BLANK_SEARCH_KEYWORD)`, 아니면 trim 값 반환(`resolveCursor` 형제). T003 의존.
- [X] T008 [US1] `core/food/FoodRepository.kt` 에 `fun searchMenuPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food>` 포트 추가(계약: cursor 있으면 `id < cursor`, 매칭=korean_name + (lang≠KO 시)해당 언어 번역명 부분 일치, id 내림차순 상위 size). kernel 타입만 받아 ORM-free 유지(data-model.md §1).
- [X] T009 [US1] `infra/persistence/food/FoodJpaRepository.kt` 에 `searchMenuPageIds(kw, jsonPath, cursor, size)` **네이티브 쿼리** 추가 — `where f.status='ACTIVE' and (:cursor is null or f.id < :cursor) and (f.korean_name like concat('%',:kw,'%') or (:jsonPath is not null and json_unquote(json_extract(f.name_translations,:jsonPath)) like concat('%',:kw,'%'))) order by f.id desc limit :size` (data-model.md §2, research R1·R4). ⚠️ 네이티브라 `@SQLRestriction` 안 붙음 → `status='ACTIVE'` 명시 필수.
- [X] T010 [US1] `infra/persistence/food/FoodRepositoryAdapter.kt` 에 `searchMenuPage` 2단계 구현 — `jsonPath = if (lang==KO) null else "$.\"${lang.code}\""` 조립 → `searchMenuPageIds` 호출 → 비면 emptyList, 아니면 **기존 `findByIdInWithAvoidanceSubstancesDesc(ids)` 재사용** → `toDomain()` 매핑(research R5). T008·T009 의존.
- [X] T011 [US1] `application/client/.../food/usecase/MenuSummaryAssembler.kt` 추출 — 회피 조달 1회 + 카탈로그 일괄 1회 + food별 `overallRisk(avoided ∩ catalog)` + `MenuSummaryView`(foodId·`displayName(lang)`·koreanName·imageRef·spiciness·risk) 매핑을 `@Component` 로. **`BrowseMenusUseCase` 를 이 assembler 를 쓰도록 리팩터**하고 기존 browse 테스트 green 유지(research R7, 안전 위험도 단일 출처).
- [X] T012 [US1] `application/client/.../food/usecase/SearchMenusUseCase.kt` 구현 — `@Transactional(readOnly=true)`, `PAGE_SIZE=20`: `resolveKeyword(input.keyword)` → `languageResolver.resolve(input.lang)` → `foodRepository.searchMenuPage(keyword, lang, input.cursor, PAGE_SIZE+1)` → `hasNext=rows.size>PAGE_SIZE`·`items=rows.take(PAGE_SIZE)`·`nextCursor=if(hasNext) items.last().id else null` → `menuSummaryAssembler.assemble(items, lang)` → `BrowseMenusResult`. T002·T007·T008·T011 의존.
- [X] T013 [US1] `app/api/food/MenuSearchController.kt` + `MenuSearchApi.kt`(Swagger `@Tag` "음식 검색") 구현 — `@RequestMapping(ApiPaths.V1 + "/foods")`, `@GetMapping("/search")` `search(@RequestParam keyword, @RequestParam(required=false) cursor, @RequestParam(required=false) lang)` → `ResponseEntity<BaseResponse<Page<MenuSummaryResponse>>>` = `BaseResponse.ok(Page(items = result.items.map(MenuSummaryResponse::from), hasNext, nextCursor))`. `cursor` 는 `resolveCursor` 재사용. `Page`·`MenuSummaryResponse` 재사용(신규 금지). T012 의존.

**Checkpoint**: 검색 매칭(한국어명·번역명 부분 일치)·첫 페이지·빈 결과 200·빈 검색어 400 독립 동작·검증 — MVP

---

## Phase 4: User Story 2 - 검색 결과를 무한 스크롤로 끝까지 넘겨본다 (Priority: P2)

**Goal**: 검색 결과가 20개를 넘으면 같은 keyword 로 `nextCursor` 를 넘겨 중복 없이 다음 20개를 이어 받는다. 마지막 페이지·잘못된 커서 처리.

**Independent Test**: 매칭 21+개 검색어로 첫 페이지·`nextCursor` 를 받고, 그 커서를 같은 keyword 로 재요청 시 직전 최소 foodId 미만만 중복 없이 오며, 마지막 페이지는 `hasNext:false`, 잘못된 커서는 400.

### Tests for User Story 2 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T014 [P] [US2] `SearchMenusUseCaseTest.kt` 보강 — 커서 지정 시 `searchMenuPage(keyword, lang, cursor, 21)` 위임·최신순 유지, 남은 ≤20 시 `hasNext:false`·`nextCursor:null`. Red 확인.
- [X] T015 [P] [US2] `MenuSearchControllerTest.kt` 보강 + `FoodRepositoryAdapterTest.kt` keyset 경계 — 같은 keyword 로 다음 커서 연속 조회 시 foodId 교집합 공집합·단조 감소(불변식 4), 마지막 페이지 `hasNext:false`, 잘못된 커서(음수·비숫자) 400 `success:false`; 어댑터 `searchMenuPage(kw, lang, cursorId, 20)` 는 `id < cursorId` 매칭만. Red 확인.

### Implementation for User Story 2

- [X] T016 [US2] 커서 연속성 배선 확인 — `MenuSearchController` 가 `resolveCursor(cursor)` 로 파싱불가·음수를 `INVALID_CURSOR(400)` 처리하고(`GlobalExceptionHandler` → `BaseResponse.fail`), `SearchMenusInput.cursor` 로 전달함을 확인·보강(research R6, KB-63 재사용). T013 확장.
- [X] T017 [US2] `searchMenuPageIds` 의 `(:cursor is null or f.id < :cursor)` + `order by f.id desc limit` 로 keyset 연속성(중복·누락 0, SC-002)이 성립함을 T015 어댑터 경계 테스트로 확정. 필요 시 쿼리만 보정(신규 로직 없음). T009 확장.

**Checkpoint**: US1 + 무한 스크롤 연속 페이지·마지막 페이지·잘못된 커서 처리

---

## Phase 5: User Story 3 - 항목에서 바로 상세로 이어지고 목록과 같은 카드를 본다 (Priority: P3)

**Goal**: 각 항목이 상세로 이어질 foodId + 목록과 동일한 'food summary'(요청 언어 표시명·koreanName·imageRef·spiciness·상세와 동일 의미 overallRiskStatus)를 담는다. 미지원 언어 400.

**Independent Test**: 회피 성분 포함 food 의 `overallRiskStatus` 가 상세/목록 종합 위험도와 동일하고, `lang=en` 표시명이 영어(번역부재 시 ko), 항목에 숫자 foodId 포함, 미지원 lang 코드는 400.

### Tests for User Story 3 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [ ] T018 [P] [US3] `SearchMenusUseCaseTest.kt` 보강 — 사용자 회피 ∩ 성분 위험도가 food 별 정확(소프트삭제 카탈로그 성분 미반영), `lang=en` 표시명 지역화·미지정 시 ko, `foodId`·`koreanName`(표시명과 다를 때만) 매핑 검증. Red 확인.
- [ ] T019 [P] [US3] `MenuSearchControllerTest.kt` 보강 — 항목 계약(`foodId`·`koreanName`·`imageRef`·`spiciness`(0~10)·`overallRiskStatus`∈{SAFE,CAUTION,DANGER,UNKNOWN}), `lang=en` 지역화 응답, 미지원 lang 코드 400(지원 언어 안내, 원칙 V). Red 확인.

### Implementation for User Story 3

- [ ] T020 [US3] 위험도·언어 정합 확인 — `MenuSummaryAssembler`(T011)가 상세 `GetFoodDetailUseCase`·목록 `BrowseMenusUseCase` 와 동일 의미(`avoided ∩ 카탈로그존재코드`, 소프트삭제 성분 제외)로 계산하고 `displayName(lang)` 지역화함을 검증·보강. 검색 고유 로직 없음(assembler 재사용). T011·T012 확장.
- [ ] T021 [US3] 미지원 lang 400 경로·매핑 완전성 확인 — `LanguageResolver.resolve(lang)` 재사용으로 미지원 코드 400+지원목록(원칙 V), `MenuSummaryResponse.from(view)` 가 `overallRiskStatus=view.overallRiskStatus.name`·`koreanName`·nullable `imageRef` 를 온전히 전달함을 확인. 필요 시 컨트롤러 배선만 조정. T013 확장.

**Checkpoint**: 모든 스토리 독립 동작 — 검색 카드·상세 식별자·언어·오류 완결

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T022 [P] `./gradlew :app:api:test --tests "com.meogo.app.api.architecture.ModuleBoundaryTest"` — 의존 방향·경계 무손상 확인(네이티브 쿼리·assembler 추가 후에도 도메인 ORM-free·app→persistence 금지 유지).
- [ ] T023 [P] `./gradlew build` 전체 통과 + 로컬 docker MySQL 수동 스모크 — `curl "/api/v1/foods/search?keyword=김치"`, `?keyword=bibim&lang=en`(번역명·대소문자), `?keyword=`(빈 → 400), nextCursor 로 연속(quickstart.md).
- [ ] T024 Swagger UI "음식 검색" 태그·응답 스키마 노출 확인, `contracts/menu-search-api.md` 불변식 7종과 대조.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 의존 없음 — 즉시 시작.
- **Foundational (Phase 2)**: Setup 후. 순수 입력 DTO·에러코드 — 모든 스토리 선행.
- **User Stories (Phase 3~5)**: Foundational 후. US1(수직 슬라이스)이 resolver/포트/네이티브쿼리/어댑터/assembler/usecase/controller 를 깔면 US2/US3 는 같은 usecase·controller·어댑터 테스트를 확장한다.
- **Polish (Phase 6)**: 원하는 스토리 완료 후.

### User Story Dependencies

- **US1 (P1)**: Foundational 후 시작 — 다른 스토리 무의존. MVP(검색 매칭 슬라이스).
- **US2 (P2)**: US1 의 `SearchMenusUseCase`·`MenuSearchController`·네이티브 쿼리 위에 커서 연속성·오류를 얹음(대부분 KB-63 커서 인프라 재사용).
- **US3 (P3)**: US1 의 `MenuSummaryAssembler`·컨트롤러 위에 카드 의미·언어 정합을 확정(assembler 재사용이라 신규 로직 최소).

### Within Each User Story

- 테스트 먼저 작성·FAIL 확인(원칙 I) → resolver → 포트 → 네이티브 쿼리 → 어댑터 → assembler/usecase → controller.
- 스토리 완료 후 다음 우선순위로.

### Parallel Opportunities

- T002·T003(Foundational) 병렬.
- 각 스토리 테스트(T004/T005/T006, T014/T015, T018/T019)는 서로 다른 파일이라 병렬 작성.
- 구현 태스크(T007~T013)는 resolver→포트→쿼리→어댑터→assembler→usecase→controller 로 의존 사슬이라 대체로 순차(T007 은 T008~T010 과 병렬 가능).

---

## Parallel Example: User Story 1

```bash
# US1 테스트 3종 먼저 작성(모두 FAIL 확인):
Task: "SearchMenusUseCaseTest 키워드검증+매칭 해피패스 in application/client/.../SearchMenusUseCaseTest.kt"
Task: "FoodRepositoryAdapterTest 검색 슬라이스(번역명·언어분리·소프트삭제) in infra/persistence/food/FoodRepositoryAdapterTest.kt"
Task: "MenuSearchControllerTest 부분일치+빈검색어400 in app/api/food/MenuSearchControllerTest.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → Phase 2 Foundational(입력 DTO·에러코드) → Phase 3 US1.
2. **STOP & VALIDATE**: 한국어명·번역명 부분 일치, 첫 페이지, 빈 결과 200, 빈 검색어 400 독립 검증.
3. 데모 가능(검색으로 메뉴 찾기).

### Incremental Delivery

1. Setup + Foundational → 입력 DTO·에러코드 준비.
2. US1 → 검색 매칭 수직 슬라이스(MVP) → 검증.
3. US2 → 무한 스크롤 연속·커서 오류 → 검증.
4. US3 → 카드 의미·언어 정합 → 검증.

### Notes

- Kotlin 주석 금지(고정) · BehaviorSpec 한국어 given/when/then(고정).
- **네이티브 검색 쿼리는 `status='ACTIVE'` 명시 필수**(`@SQLRestriction` 미적용) — 소프트삭제 제외 회귀 테스트(T005)로 강제.
- **대소문자 비구분은 콜레이션(`utf8mb4_0900_ai_ci`)이 처리 — `LOWER()` 금지**(research R2). 통합 테스트는 MySQL Testcontainers 실측.
- **JSON path 조립**: `lang=KO` → null(한국어명만), 그 외 `$."<lang.code>"`(하이픈 코드 큰따옴표 포함).
- **응답 DTO 재사용**: `Page`·`MenuSummaryResponse`·`BrowseMenusResult`·`MenuSummaryView` 신규 금지(KB-63 공유 스키마).
- **위험도 로직 단일 출처**: `MenuSummaryAssembler` 로 browse/search 공유(안전 직결).
- **경로 충돌 회피**: 검색은 `/api/v1/foods/search`(목록 `/api/v1/foods` 와 별도).
- 신규 Flyway 마이그레이션·인덱스 추가 금지(스키마 변경 없음).
