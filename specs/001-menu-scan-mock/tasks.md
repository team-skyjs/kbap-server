---
description: "Task list — 메뉴 스캔 제출·판정 & 음식 상세 조회 (mock 슬라이스)"
---

# Tasks: 메뉴 스캔 제출·판정 & 음식 상세 조회 (mock 슬라이스)

**Input**: Design documents from `specs/001-menu-scan-mock/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Test-First는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 유스케이스는 구현 전에 **실패하는 테스트를 먼저** 작성(Red → Green → Refactor).

**Organization**: 작업은 user story 단위로 묶어 독립 구현·테스트가 가능하게 한다. US1(스캔)만으로 MVP 성립.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1 = 메뉴 스캔 제출+판정, US2 = 음식 상세 조회
- 경로는 멀티모듈 기준(plan.md Structure). 패키지: **모든 meogo-api 하위 모듈은 `com.meogo.api.<모듈명>`** — 도메인 `com.meogo.api.<context>`, 계층 `com.meogo.api.{application,infra}`, 커널 `com.meogo.api.core`, web `com.meogo.api.presentation`, 진입점 `com.meogo.api`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 빌드 베이스라인 확인 및 마이그레이션 위치 준비

- [X] T001 빈 모듈 빌드/테스트 베이스라인 확인 — `./gradlew :meogo-api:presentation:test` 가 현재 통과(스캐폴드 정상)함을 확인
- [X] T002 [P] Flyway 마이그레이션 디렉터리 생성 — `meogo-api/presentation/src/main/resources/db/migration/` (비어 있으면 `.gitkeep`)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 두 user story가 공통으로 의존하는 커널·web 공통. **이 단계 완료 전 어떤 story도 시작 불가.**

- [X] T003 [P] `RiskLevel` enum(SAFE/CAUTION/DANGER/UNKNOWN) 생성 — `meogo-api/core/src/main/kotlin/com/meogo/api/core/risk/RiskLevel.kt`
- [X] T004 [P] `ApiResponse<T>`(success/data/message + ok/fail 팩토리) 생성 — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/common/ApiResponse.kt` (CLAUDE.md "API 응답 규약" 그대로)
- [X] T005 [P] `ApiResponse` 팩토리 단위 테스트(ok/fail 형태) 작성 — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/common/ApiResponseTest.kt`
- [X] T006 `GlobalExceptionHandler` 기본 골격(Bean Validation 위반 → 400 `ApiResponse.fail`) 생성 — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/common/GlobalExceptionHandler.kt`

**Checkpoint**: 커널·응답 봉투·예외 매핑 준비 완료 → user story 시작 가능

---

## Phase 3: User Story 1 — 메뉴 스캔 제출 후 항목별 위험도 받기 (Priority: P1) 🎯 MVP

**Goal**: `POST /api/v1/menu-scans` — items(itemId·rawMenuName·boundingBox) 배열을 받아 배열 순서 기준 mock 4단계 위험도를 itemId로 매칭해 반환하고, 스캔·항목·결과를 MySQL에 최소 저장.

**Independent Test**: 4개 항목 제출 → 200, results 4개가 itemId로 1:1 매칭되고 4단계를 모두 포함. 잘못된 요청(빈 목록·100개 초과·itemId 중복·rawMenuName blank·boundingBox 누락/좌표오류) → 400. 저장은 repository 테스트로 확인.

### Tests for User Story 1 (먼저 작성, 반드시 FAIL 확인) ⚠️

- [X] T007 [P] [US1] 도메인 단위 테스트 `BoundingBox` 정규화 좌표 검증(`x≥0,y≥0,width>0,height>0,x+width≤1,y+height≤1`; 위반 시 예외) — `meogo-api/scan/src/test/kotlin/com/meogo/api/scan/BoundingBoxTest.kt`
- [X] T008 [P] [US1] 도메인 단위 테스트 `MenuScan` 불변식(항목 1..100, itemId 스캔 내 유일) — `meogo-api/scan/src/test/kotlin/com/meogo/api/scan/MenuScanTest.kt`
- [X] T009 [P] [US1] 단위 테스트 `MockCyclingRiskAssessor`(index%4 → SAFE/CAUTION/DANGER/UNKNOWN, 5번째 재순환) — `meogo-api/application/src/test/kotlin/com/meogo/api/application/scan/MockCyclingRiskAssessorTest.kt`
- [X] T010 [P] [US1] 영속 테스트 `MenuScanRepositoryAdapter` 저장/조회(H2, scanId·항목·boundingBox·결과 보존, SC-006) — `meogo-api/scan/src/test/kotlin/com/meogo/api/scan/infrastructure/MenuScanRepositoryAdapterTest.kt`
- [X] T011 [P] [US1] web 계약 테스트(MockMvc) 정상 흐름(200, results itemId 매칭, 4단계 분포) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/scan/MenuScanControllerTest.kt`
- [X] T012 [P] [US1] web 검증 테스트(400: 빈 items·101개·itemId 중복·rawMenuName blank·boundingBox 누락·width=0·x=-1·`x+width>1`) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/scan/MenuScanValidationTest.kt`

### Implementation for User Story 1

**도메인 (scan)**

- [X] T013 [P] [US1] `BoundingBox` 값 객체(정규화 비율, 생성 시 불변식 `x≥0,y≥0,width>0,height>0,x+width≤1,y+height≤1` 검증) — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/BoundingBox.kt`
- [X] T014 [P] [US1] `MenuItemAssessment` 값 객체(riskLevel, reason) — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/MenuItemAssessment.kt`
- [X] T015 [P] [US1] `ScanStatus` enum(COMPLETED) — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/ScanStatus.kt`
- [X] T016 [US1] `ScannedMenuItem`(itemId, rawMenuName, boundingBox, receivedOrder, assessment) — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/ScannedMenuItem.kt` (T013·T014)
- [X] T017 [US1] `MenuScan` Aggregate Root(불변식 1..100·itemId 유일, factory) — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/MenuScan.kt` (T015·T016)
- [X] T018 [US1] `MenuScanRepository` DomainRepository 인터페이스(save/findById) — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/MenuScanRepository.kt`

**영속 (scan/infrastructure) + 마이그레이션**

- [X] T019 [US1] Flyway `V1__create_scan_tables.sql`(menu_scan, scanned_menu_item) — `meogo-api/presentation/src/main/resources/db/migration/V1__create_scan_tables.sql` (data-model.md 스키마)
- [X] T020 [P] [US1] JPA 엔티티 `MenuScanJpaEntity`, `ScannedMenuItemJpaEntity` — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/infrastructure/`
- [X] T021 [US1] Spring Data `MenuScanJpaRepository` — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/infrastructure/MenuScanJpaRepository.kt` (T020)
- [X] T022 [US1] `MenuScanRepositoryAdapter`(도메인 ⇄ JPA 매핑, `MenuScanRepository` 구현) — `meogo-api/scan/src/main/kotlin/com/meogo/api/scan/infrastructure/MenuScanRepositoryAdapter.kt` (T018·T021)

**판정 seam + 유스케이스 (application)**

- [X] T023 [P] [US1] `MenuItemRiskAssessor` 인터페이스(판정 seam, FR-013) — `meogo-api/application/src/main/kotlin/com/meogo/api/application/scan/MenuItemRiskAssessor.kt`
- [X] T024 [P] [US1] `MockCyclingRiskAssessor`(index%4 순환, reason 문구) 빈 — `meogo-api/application/src/main/kotlin/com/meogo/api/application/scan/MockCyclingRiskAssessor.kt`
- [X] T025 [P] [US1] `SubmitMenuScanCommand` / `MenuScanResult` application 타입 — `meogo-api/application/src/main/kotlin/com/meogo/api/application/scan/SubmitMenuScanCommand.kt`, `MenuScanResult.kt`
- [X] T026 [US1] `SubmitMenuScanUseCase`(@Transactional: command→도메인 조립, 판정 부여, 저장, 결과 반환) — `meogo-api/application/src/main/kotlin/com/meogo/api/application/scan/SubmitMenuScanUseCase.kt` (T017·T018·T023·T025)

**web (api)**

- [X] T027 [P] [US1] 요청/응답 DTO + Bean Validation(@NotEmpty/@Size(max=100)/@NotBlank/@NotNull/@Valid, BoundingBox @PositiveOrZero(x,y)·@Positive(w,h)·@DecimalMax("1.0"), 교차 제약 `x+width≤1`·`y+height≤1`은 @AssertTrue) — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/scan/dto/`
- [X] T028 [US1] `MenuScanController`(POST /api/v1/menu-scans, DTO→Command, itemId 중복 수동 검증→400, `ApiResponse.ok`) — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/scan/MenuScanController.kt` (T026·T027)
- [X] T029 [US1] mock 빈 와이어링 확인 후 US1 테스트 GREEN — `./gradlew :meogo-api:scan:test :meogo-api:application:test :meogo-api:presentation:test` 통과(T007~T012 통과)

**Checkpoint**: US1 단독으로 동작·테스트 가능 → MVP 완성. 데모 가능.

---

## Phase 4: User Story 2 — 메뉴명으로 음식 상세 정보 조회 (Priority: P2)

**Goal**: `GET /api/v1/foods/detail?menuName=&lang=` — seed 음식 상세(요청 `lang` 음식명·대표 이미지·재료 목록[재료명·아이콘·포함%·mock riskStatus]) 반환. `lang` 미지원/미지정 → `ko` 폴백. 없으면 404, menuName 누락/blank 400. 음식·재료명은 `ko` 원문 + 9개 대상 언어로 저장(seed 보유).

**Independent Test**: seed 메뉴명("된장찌개") + `lang=en` → 200 영어 음식명·재료명. `lang=ja` → 일본어. `lang` 미지정/`xx` → ko 폴백. 앞뒤 공백 trim. 없는 메뉴명 → 404. menuName 누락/blank → 400.

### Tests for User Story 2 (먼저 작성, 반드시 FAIL 확인) ⚠️

- [ ] T030 [P] [US2] 단위 테스트 `MockIngredientRiskMarker`(첫 재료 CAUTION, 나머지 SAFE) — `meogo-api/application/src/test/kotlin/com/meogo/api/application/food/MockIngredientRiskMarkerTest.kt`
- [ ] T030a [P] [US2] 단위 테스트 `LanguageResolver`(지원 코드 통과, 미지원/미지정 → ko 폴백) — `meogo-api/application/src/test/kotlin/com/meogo/api/application/food/LanguageResolverTest.kt`
- [ ] T030b [P] [US2] 도메인 단위 테스트 `Food.nameFor(lang)`/`Ingredient.nameFor(lang)`(번역 있으면 해당 언어, 없으면 ko 폴백) — `meogo-api/food/src/test/kotlin/com/meogo/api/food/FoodNameForTest.kt`
- [ ] T031 [P] [US2] 영속 테스트 `FoodRepositoryAdapter.findByKoreanName`(H2 + seed, trim, **번역 9개 로드 확인**) — `meogo-api/food/src/test/kotlin/com/meogo/api/food/infrastructure/FoodRepositoryAdapterTest.kt`
- [ ] T032 [P] [US2] web 계약 테스트(MockMvc) 200(`lang=en` 영어명 + 재료 %/riskStatus + trim) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/food/FoodDetailControllerTest.kt`
- [ ] T032a [P] [US2] web 테스트 다국어/폴백(`lang=ja` 일본어, `lang=xx`/미지정 → ko) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/food/FoodDetailLangTest.kt`
- [ ] T033 [P] [US2] web 테스트 404(없는 메뉴) + 400(menuName 누락/blank) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/food/FoodDetailErrorTest.kt`

### Implementation for User Story 2

**도메인 (food)**

- [ ] T034 [P] [US2] `LanguageCode` enum(ko + 9개 대상 언어, 미지원 → ko 폴백 헬퍼) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/LanguageCode.kt`
- [ ] T034a [P] [US2] `Ingredient`(koreanName, names: Map<LangCode,String>, iconRef, `nameFor(lang)`) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/Ingredient.kt`
- [ ] T035 [P] [US2] `FoodIngredient`(ingredientId, inclusionPercent 0~100, displayOrder) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/FoodIngredient.kt`
- [ ] T036 [US2] `Food` Aggregate Root(koreanName=ko 매칭키, names: Map<LangCode,String>, imageRef, ingredients, `nameFor(lang)`) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/Food.kt` (T034·T034a·T035)
- [ ] T037 [US2] `FoodRepository` 인터페이스(findByKoreanName — 음식+재료+번역 로드) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/FoodRepository.kt`

**영속 (food/infrastructure) + 마이그레이션/시드**

- [ ] T038 [US2] Flyway `V2__create_food_tables.sql`(food, food_name_translation, ingredient, ingredient_name_translation, food_ingredient) — `meogo-api/presentation/src/main/resources/db/migration/V2__create_food_tables.sql` (data-model 스키마)
- [ ] T039 [US2] Flyway `V3__seed_food_data.sql`(된장찌개 + 재료 5종 + 포함% + **각 음식·재료의 9개 언어 번역 행 전부**) — `meogo-api/presentation/src/main/resources/db/migration/V3__seed_food_data.sql`
- [ ] T040 [P] [US2] JPA 엔티티 `FoodJpaEntity`·`FoodNameTranslationJpaEntity`·`IngredientJpaEntity`·`IngredientNameTranslationJpaEntity`·`FoodIngredientJpaEntity` — `meogo-api/food/src/main/kotlin/com/meogo/api/food/infrastructure/`
- [ ] T041 [US2] Spring Data `FoodJpaRepository`(findByKoreanName, 번역 fetch) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/infrastructure/FoodJpaRepository.kt` (T040)
- [ ] T042 [US2] `FoodRepositoryAdapter`(도메인 ⇄ JPA 매핑, 번역 → names 맵) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/infrastructure/FoodRepositoryAdapter.kt` (T037·T041)

**상세 조회 seam + 유스케이스 (application)**

- [ ] T043 [P] [US2] `IngredientRiskMarker` 인터페이스 + `MockIngredientRiskMarker`(첫 재료 CAUTION) — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/IngredientRiskMarker.kt`, `MockIngredientRiskMarker.kt`
- [ ] T043a [P] [US2] `LanguageResolver`(입력 lang → 지원 LangCode/ko 폴백; 향후 회원 출처 교체점) — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/LanguageResolver.kt`
- [ ] T044 [P] [US2] `GetFoodDetailQuery`(menuName, lang?) / `FoodDetailResult`(name, imageRef, ingredients[IngredientView(name,iconRef,inclusionPercent,riskStatus)]) / `FoodNotFoundException` — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/`
- [ ] T045 [US2] `GetFoodDetailUseCase`(lang resolve → menuName trim→findByKoreanName, 미발견 예외, nameFor(lang) 매핑, riskStatus 부여) — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/GetFoodDetailUseCase.kt` (T037·T043·T043a·T044)

**web (api)**

- [ ] T046 [P] [US2] `FoodDetailResponse` DTO(name, imageRef, ingredients[name,iconRef,inclusionPercent,riskStatus]) — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/food/dto/FoodDetailResponse.kt`
- [ ] T047 [US2] `FoodDetailController`(GET /api/v1/foods/detail, `menuName`(@NotBlank→400)·`lang`(선택, 미지정/미지원→ko 폴백), `ApiResponse.ok`) — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/food/FoodDetailController.kt` (T045·T046)
- [ ] T048 [US2] `GlobalExceptionHandler`에 `FoodNotFoundException`→404 `ApiResponse.fail("해당 음식 정보 없음")` 매핑 추가 — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/common/GlobalExceptionHandler.kt` (T006 확장)
- [ ] T049 [US2] US2 테스트 GREEN 확인 — `./gradlew :meogo-api:food:test :meogo-api:application:test :meogo-api:presentation:test` 통과(T030~T033, T030a/b·T032a 포함)

**Checkpoint**: US1·US2 모두 독립 동작.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [ ] T050 [P] springdoc/Swagger 어노테이션으로 두 엔드포인트 문서화 — `meogo-api/presentation/.../MenuScanController.kt`, `FoodDetailController.kt`
- [ ] T051 quickstart.md curl 시나리오 수동 검증(local 프로필 bootRun) — `specs/001-menu-scan-mock/quickstart.md`
- [ ] T052 [P] follow-up 메모 반영: `docs/architecture/domains/food.md`의 포함도 `0/1/2` → 연속 %(0~100) 문서 reconcile
- [ ] T053 전체 회귀 — `./gradlew test` 통과 + Success Criteria(SC-001~008) 체크리스트 대조

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)**: 의존 없음, 즉시 시작
- **Foundational(P2)**: Setup 후. **모든 user story를 BLOCK** (RiskLevel·ApiResponse·예외 핸들러 공통)
- **US1(P3)**: Foundational 후 시작. 다른 story에 의존 없음 → MVP
- **US2(P4)**: Foundational 후 시작. US1과 독립(컨트롤러·도메인 모듈 분리). 단 `GlobalExceptionHandler`(T048)는 T006 위에 확장
- **Polish(P5)**: 원하는 story 완료 후

### User Story Dependencies

- **US1**: Foundational만 의존. 단독 완결(스캔 도메인·application·api)
- **US2**: Foundational만 의존. food 도메인·application·api로 US1과 파일 충돌 없음 → 병렬 가능

### Within Each Story (TDD, 헌법 I)

- 테스트 먼저 작성·FAIL 확인 → 도메인 모델 → 영속/마이그레이션 → application → web
- 모델 → 서비스 → 엔드포인트 순서. story 완료 후 다음 우선순위로.

### Parallel Opportunities

- Setup T002 [P]
- Foundational T003·T004·T005 [P] (T006은 T004 의존)
- US1 테스트 T007~T012 모두 [P]
- US1 도메인 VO T013·T014·T015 [P]; application seam T023·T024·T025 [P]; DTO T027 [P]
- US2 테스트 T030~T033 [P]; 도메인 T034·T035 [P]; T040·T043·T044·T046 [P]
- Foundational 완료 후 **US1·US2를 다른 개발자가 병렬** 진행 가능(모듈 분리)

---

## Parallel Example: User Story 1 테스트(먼저 작성, 모두 FAIL)

```bash
Task: "BoundingBoxTest in meogo-api/scan/src/test/.../BoundingBoxTest.kt"
Task: "MenuScanTest in meogo-api/scan/src/test/.../MenuScanTest.kt"
Task: "MockCyclingRiskAssessorTest in meogo-api/application/src/test/.../MockCyclingRiskAssessorTest.kt"
Task: "MenuScanRepositoryAdapterTest in meogo-api/scan/src/test/.../infrastructure/MenuScanRepositoryAdapterTest.kt"
Task: "MenuScanControllerTest in meogo-api/presentation/src/test/.../scan/MenuScanControllerTest.kt"
Task: "MenuScanValidationTest in meogo-api/presentation/src/test/.../scan/MenuScanValidationTest.kt"
```

---

## Implementation Strategy

### MVP First (US1만)

1. Phase 1 Setup → 2. Phase 2 Foundational → 3. Phase 3 US1 → **STOP & VALIDATE**(스캔 200/400 + 저장) → 데모.

### Incremental Delivery

1. Setup + Foundational → 기반 완성
2. US1(스캔) → 독립 검증 → 데모(MVP)
3. US2(음식 상세) → 독립 검증 → 데모
4. Polish

### Parallel Team Strategy

Foundational 완료 후 — 개발자 A: US1(scan), 개발자 B: US2(food). 모듈이 분리돼 충돌 없음(`GlobalExceptionHandler` 확장만 조율).

---

## Notes

- [P] = 다른 파일·의존 없음. 같은 파일 수정 task는 [P] 아님(예: T006↔T048 GlobalExceptionHandler).
- 각 task는 **실패 테스트 먼저 → 최소 구현 → 리팩터**. 구현 전 테스트 FAIL 확인(헌법 I).
- **작업/논리 단위마다 커밋**(헌법 Development Workflow).
- 헌법 IV: api/application은 JPA 엔티티를 import하지 않는다 — 매핑은 `infrastructure` 어댑터 안에서만.
- 헌법 V(v2.0.0): 음식 콘텐츠는 ko 원문 + 9개 대상 언어 저장 — seed가 번역 직접 보유. 실제 번역 생성(배치)·회원 언어 해석은 비범위.
