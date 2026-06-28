---
description: "Task list — 메뉴 스캔 제출·판정 & 음식 상세 조회 (mock 슬라이스)"
---

# Tasks: 메뉴 스캔 제출·판정 & 음식 상세 조회 (mock 슬라이스)

**Input**: Design documents from `specs/001-menu-scan-mock/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Test-First는 **NON-NEGOTIABLE**(헌법 원칙 I). 각 유스케이스는 구현 전에 **실패하는 테스트를 먼저** 작성(Red → Green → Refactor). 모든 테스트는 Kotest **`BehaviorSpec`**(given/when/then 한국어, CLAUDE.md 고정).

**Organization**: 작업은 user story 단위. US1(스캔)만으로 MVP 성립.

> **재정합(2026-06-28)**: 본 tasks 는 `/speckit-clarify`(Session 2026-06-28)·`/speckit-plan` 재정합을 반영. 주요: 응답 봉투 `BaseResponse`/`payload`, 영속은 **중앙 `:meogo-api:persistence`**(도메인은 순수 model+port), web 모듈 `presentation`, application 입출력 `Input/Result`(Command/Query 금지), 도메인 생성입력 `CreationSpec`, `ScannedMenuItem.receivedOrder` 제거, **US2 미수록 메뉴 400**, 재료 `riskStatus` = 4단계 `RiskLevel` 재사용, `inclusionPercent`(연속 %)와 `0/1/2`(후속 LLM 스코어링)는 별개. **US1(Phase 1~3)은 이미 구현 완료** — 아래 설명을 as-built 로 정정해 둠. 잔여 작업은 **US2(Phase 4)**.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1 = 메뉴 스캔 제출+판정, US2 = 음식 상세 조회
- 패키지: 모든 meogo-api 하위 모듈은 `com.meogo.api.<영역>` — 도메인 `com.meogo.api.<context>`(순수), 계층 `com.meogo.api.application`, 영속 `com.meogo.api.persistence.<context>`, 커널 `com.meogo.api.core`, web `com.meogo.api.presentation`, 진입점 `com.meogo.api`.

---

## Phase 1: Setup (Shared Infrastructure) — ✅ 완료

- [X] T001 빈 모듈 빌드/테스트 베이스라인 확인 — `./gradlew :meogo-api:presentation:test` 통과
- [X] T002 [P] Flyway 마이그레이션 디렉터리 — `meogo-api/presentation/src/main/resources/db/migration/`

---

## Phase 2: Foundational (Blocking Prerequisites) — ✅ 완료

**Purpose**: 두 user story 공통 커널·web 공통·영속 베이스.

- [X] T003 [P] `RiskLevel` enum(SAFE/CAUTION/DANGER/UNKNOWN) — `meogo-api/core/src/main/kotlin/com/meogo/api/core/risk/RiskLevel.kt`
- [X] T004 [P] `BaseResponse<T>`(success/payload/message + ok/fail) + `ApiPaths`(V1=`/api/v1`) — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/common/{BaseResponse.kt,ApiPaths.kt}` (CLAUDE.md "API 응답 규약")
- [X] T005 [P] `BaseResponse` 팩토리 단위 테스트(ok/fail 형태) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/common/`
- [X] T006 `GlobalExceptionHandler`(Bean Validation 위반·`HttpMessageNotReadable`·`IllegalArgumentException` → 400 `BaseResponse.fail`) — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/common/GlobalExceptionHandler.kt`
- [X] T006a [P] 영속 베이스 `BaseEntity`(@MappedSuperclass: id·status(EntityStatus 소프트삭제·`@SQLRestriction`)·createdAt·updatedAt) + `EntityStatus` — `meogo-api/persistence/src/main/kotlin/com/meogo/api/persistence/{BaseEntity.kt,EntityStatus.kt}`

**Checkpoint**: 커널·응답 봉투·예외 매핑·영속 베이스 준비 → user story 시작 가능

---

## Phase 3: User Story 1 — 메뉴 스캔 제출 후 항목별 위험도 받기 (P1) 🎯 MVP — ✅ 완료

**Goal**: `POST /api/v1/menu-scans` — items(itemId·rawMenuName·boundingBox) 배열 → 배열 순서 기준 mock 4단계 위험도를 itemId로 매칭 반환 + MySQL 최소 저장.

**Independent Test**: 4개 항목 → 200, results 4개 itemId 1:1·4단계 포함. 잘못된 요청 → 400. 저장은 repository 테스트로 확인.

### Tests for US1 (먼저 작성, FAIL 확인) ⚠️ — ✅

- [X] T007 [P] [US1] `BoundingBoxTest`(정규화 좌표 불변식, 위반 시 예외) — `meogo-api/scan/src/test/kotlin/com/meogo/api/scan/BoundingBoxTest.kt`
- [X] T008 [P] [US1] `MenuScanTest`(불변식 항목 1..100, itemId 스캔 내 유일; `MenuScan.create(CreationSpec)`) — `meogo-api/scan/src/test/kotlin/com/meogo/api/scan/MenuScanTest.kt`
- [X] T009 [P] [US1] `MockCyclingRiskAssessorTest`(index%4 → 4단계, 재순환) — `meogo-api/application/src/test/kotlin/com/meogo/api/application/scan/MockCyclingRiskAssessorTest.kt`
- [X] T010 [P] [US1] `MenuScanRepositoryAdapterTest`(H2 `@SpringBootTest`, scanId·항목·boundingBox·결과 보존, 소프트삭제, SC-006) — `meogo-api/persistence/src/test/kotlin/com/meogo/api/persistence/scan/MenuScanRepositoryAdapterTest.kt`
- [X] T011 [P] [US1] `MenuScanControllerTest`(MockMvc 200, results itemId 매칭, 4단계 분포) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/scan/MenuScanControllerTest.kt`
- [X] T012 [P] [US1] `MenuScanValidationTest`(400: 빈 items·101개·itemId 중복·rawMenuName blank·boundingBox 누락·width=0·x=-1·`x+width>1`) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/scan/MenuScanValidationTest.kt`

### Implementation for US1 — ✅

**도메인 (scan, 순수)**

- [X] T013 [P] [US1] `BoundingBox` VO(정규화 비율, init 불변식 검증) — `meogo-api/scan/.../scan/BoundingBox.kt`
- [X] T014 [P] [US1] `MenuItemAssessment` VO(riskLevel, reason) — `meogo-api/scan/.../scan/MenuItemAssessment.kt`
- [X] T015 [P] [US1] `ScanStatus` enum(COMPLETED) — `meogo-api/scan/.../scan/ScanStatus.kt`
- [X] T016 [US1] `ScannedMenuItem`(itemId, rawMenuName, boundingBox, assessment) — **receivedOrder 없음** — `meogo-api/scan/.../scan/ScannedMenuItem.kt`
- [X] T017 [US1] `MenuScan` Aggregate(불변식 1..100·itemId 유일, `create(CreationSpec)`/`reconstitute`) — `meogo-api/scan/.../scan/MenuScan.kt`
- [X] T018 [US1] `MenuScanRepository` 도메인 port(save/findById) — `meogo-api/scan/.../scan/MenuScanRepository.kt`

**영속 (`:meogo-api:persistence` · com.meogo.api.persistence.scan) + 마이그레이션**

- [X] T019 [US1] Flyway `V1__create_scan_tables.sql`(menu_scan, scanned_menu_item — BaseEntity 컬럼 status/created_at/updated_at, scan_status, **received_order 없음**) — `meogo-api/presentation/src/main/resources/db/migration/V1__create_scan_tables.sql`
- [X] T020 [P] [US1] JPA 엔티티 `MenuScanJpaEntity`·`ScannedMenuItemJpaEntity`(BaseEntity 상속, `toDomain()`/`from(domain)`, LAZY+fetch join) — `meogo-api/persistence/.../persistence/scan/`
- [X] T021 [US1] Spring Data `MenuScanJpaRepository` — `meogo-api/persistence/.../persistence/scan/MenuScanJpaRepository.kt`
- [X] T022 [US1] `MenuScanRepositoryAdapter`(도메인 port 구현, `Entity.from`/`entity.toDomain`만 호출) — `meogo-api/persistence/.../persistence/scan/MenuScanRepositoryAdapter.kt`

**판정 seam + 유스케이스 (application)**

- [X] T023 [P] [US1] `MenuItemRiskAssessor` 인터페이스(판정 seam, FR-013) — `meogo-api/application/.../application/scan/MenuItemRiskAssessor.kt`
- [X] T024 [P] [US1] `MockCyclingRiskAssessor`(index%4) — `meogo-api/application/.../application/scan/MockCyclingRiskAssessor.kt`
- [X] T025 [P] [US1] `SubmitMenuScanInput` / `SubmitMenuScanResult` application 타입(Command/Result 아님) — `meogo-api/application/.../application/scan/{SubmitMenuScanInput.kt,SubmitMenuScanResult.kt}`
- [X] T026 [US1] `SubmitMenuScanUseCase`(@Transactional: Input→도메인 조립[mapIndexed index 로 mock 판정], 저장, `SubmitMenuScanResult` 반환) — `meogo-api/application/.../application/scan/SubmitMenuScanUseCase.kt`

**web (presentation/scan)**

- [X] T027 [P] [US1] `MenuScanApi`(springdoc) + `SubmitMenuScanRequest`(Bean Validation + `toInput()`)·`SubmitMenuScanResponse`(`from(result)`) — `meogo-api/presentation/.../presentation/scan/`
- [X] T028 [US1] `MenuScanController`(POST `ApiPaths.V1 + "/menu-scans"`, `request.toInput()` → usecase → `BaseResponse.ok(SubmitMenuScanResponse.from(result))`) — `meogo-api/presentation/.../presentation/scan/MenuScanController.kt`
- [X] T029 [US1] US1 테스트 GREEN — `./gradlew :meogo-api:scan:test :meogo-api:application:test :meogo-api:persistence:test :meogo-api:presentation:test`

**Checkpoint**: US1 단독 동작·테스트 가능 → MVP 완성.

---

## Phase 4: User Story 2 — 메뉴명으로 음식 상세 정보 조회 (P2) — ✅ 완료

**Goal**: `GET /api/v1/foods/detail?menuName=&lang=` — seed 음식 상세(요청 `lang` 음식명·대표 이미지·재료 목록[재료명·아이콘·포함%·mock `riskStatus`(4단계 `RiskLevel`)]) 반환. `lang` 미지원/미지정 → `ko` 폴백. **미수록 메뉴 → 400**, menuName 누락/blank → 400. 음식·재료명은 `ko` 원문 + 9개 대상 언어 저장(seed 보유).

**Independent Test**: seed("된장찌개") + `lang=en` → 200 영어명·재료명. `lang=ja` → 일본어. `lang` 미지정/`xx` → ko 폴백. 앞뒤 공백 trim. 미수록 메뉴 → 400("해당 음식 정보 없음"). menuName 누락/blank → 400("menuName은 필수입니다").

### Tests for US2 (먼저 작성, 반드시 FAIL 확인) ⚠️

- [X] T030 [P] [US2] `MockIngredientRiskMarkerTest`(첫 재료 CAUTION, 나머지 SAFE; 반환은 `RiskLevel`) — `meogo-api/application/src/test/kotlin/com/meogo/api/application/food/MockIngredientRiskMarkerTest.kt`
- [X] T031 [P] [US2] `LanguageResolverTest`(지원 코드 통과; 미지원/미지정/blank → `ko` 폴백) — `meogo-api/application/src/test/kotlin/com/meogo/api/application/food/LanguageResolverTest.kt`
- [X] T032 [P] [US2] 도메인 `FoodNameForTest`(`Food.nameFor`/`Ingredient.nameFor` — 번역 있으면 해당 언어, 없으면 `ko` 폴백) — `meogo-api/food/src/test/kotlin/com/meogo/api/food/FoodNameForTest.kt`
- [X] T033 [P] [US2] 영속 `FoodRepositoryAdapterTest`(H2 `@SpringBootTest`; `findByKoreanName` trim·9개 번역·재료 fetch join 로드·소프트삭제) — `meogo-api/persistence/src/test/kotlin/com/meogo/api/persistence/food/FoodRepositoryAdapterTest.kt` *(persistence 테스트 boot app 이 food 엔티티를 스캔하도록 구성)*
- [X] T034 [P] [US2] web 계약 `FoodDetailControllerTest`(MockMvc 200, `lang=en` 영어명 + 재료 %/riskStatus + trim, `payload` 봉투) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/food/FoodDetailControllerTest.kt`
- [X] T035 [P] [US2] web `FoodDetailLangTest`(다국어 `lang=ja`; `lang=xx`/미지정 → `ko` 폴백 200) — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/food/FoodDetailLangTest.kt`
- [X] T036 [P] [US2] web `FoodDetailErrorTest`(미수록 메뉴 → **400** "해당 음식 정보 없음"; menuName 누락/blank → 400 "menuName은 필수입니다") — `meogo-api/presentation/src/test/kotlin/com/meogo/api/presentation/food/FoodDetailErrorTest.kt`

### Implementation for US2

**도메인 (food, 순수 — Spring/ORM-free)**

- [X] T037 [P] [US2] `LanguageCode` enum(`ko` + 9개; `from(code): LanguageCode`/미지원→`ko` 폴백 헬퍼) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/LanguageCode.kt`
- [X] T038 [P] [US2] `Ingredient`(koreanName, names: Map<LanguageCode,String>, iconRef?, `nameFor(lang)`; 불변) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/Ingredient.kt`
- [X] T039 [P] [US2] `FoodIngredient`(ingredient: Ingredient, inclusionPercent 0~100, displayOrder; 불변) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/FoodIngredient.kt`
- [X] T040 [US2] `Food` Aggregate Root(koreanName=`ko` 매칭키, names: Map<LanguageCode,String>, imageRef?, ingredients, `nameFor(lang)`; 불변) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/Food.kt` (T037·T038·T039)
- [X] T041 [US2] `FoodRepository` 도메인 port(`findByKoreanName(name): Food?`) — `meogo-api/food/src/main/kotlin/com/meogo/api/food/FoodRepository.kt`

**영속 (`:meogo-api:persistence` · com.meogo.api.persistence.food) + 마이그레이션/시드**

- [X] T042 [US2] Flyway `V2__create_food_tables.sql`(food·food_name_translation·ingredient·ingredient_name_translation·food_ingredient — 각 테이블 BaseEntity 컬럼 status/created_at/updated_at 포함) — `meogo-api/presentation/src/main/resources/db/migration/V2__create_food_tables.sql` (data-model 스키마)
- [X] T043 [US2] Flyway `V3__seed_food_data.sql`(된장찌개 + 재료 5종 + inclusion_percent + **각 음식·재료의 9개 언어 번역 행 전부**, status=ACTIVE) — `meogo-api/presentation/src/main/resources/db/migration/V3__seed_food_data.sql`
- [X] T044 [P] [US2] JPA 엔티티 `FoodJpaEntity`·`FoodNameTranslationJpaEntity`·`IngredientJpaEntity`·`IngredientNameTranslationJpaEntity`·`FoodIngredientJpaEntity`(BaseEntity 상속, LAZY 연관, `toDomain()`/`from(domain)`) — `meogo-api/persistence/src/main/kotlin/com/meogo/api/persistence/food/`
- [X] T045 [US2] Spring Data `FoodJpaRepository`(`findByKoreanName` + 재료·번역 **fetch join**으로 LAZY 초기화) — `meogo-api/persistence/.../persistence/food/FoodJpaRepository.kt` (T044)
- [X] T046 [US2] `FoodRepositoryAdapter`(`FoodRepository` port 구현, `Entity.from`/`entity.toDomain`만 호출, 번역행 → names 맵) — `meogo-api/persistence/.../persistence/food/FoodRepositoryAdapter.kt` (T041·T045)

**상세 조회 seam + 유스케이스 (application/food)**

- [X] T047 [P] [US2] `IngredientRiskMarker` 인터페이스 + `MockIngredientRiskMarker`(첫 재료 CAUTION, 나머지 SAFE; `RiskLevel` 반환) — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/{IngredientRiskMarker.kt,MockIngredientRiskMarker.kt}`
- [X] T048 [P] [US2] `LanguageResolver`(입력 `lang` → 지원 `LanguageCode`/`ko` 폴백; 향후 회원 출처 교체점) — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/LanguageResolver.kt`
- [X] T049 [P] [US2] `GetFoodDetailInput`(menuName, lang?) / `GetFoodDetailResult`(name, imageRef?, ingredients: List<IngredientView(name, iconRef?, inclusionPercent, riskStatus: RiskLevel)>) — Query 아님 — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/{GetFoodDetailInput.kt,GetFoodDetailResult.kt}`
- [X] T050 [US2] `GetFoodDetailUseCase`(@Transactional(readOnly): lang resolve → menuName trim → `findByKoreanName`, **null이면 `IllegalArgumentException("해당 음식 정보 없음")`**(→기존 400 핸들러), `nameFor(lang)` 매핑, `IngredientRiskMarker`로 riskStatus 부여 → `GetFoodDetailResult`) — `meogo-api/application/src/main/kotlin/com/meogo/api/application/food/GetFoodDetailUseCase.kt` (T041·T047·T048·T049)

**web (presentation/food)**

- [X] T051 [P] [US2] `FoodDetailApi`(springdoc `@Operation`/`@Parameter`(menuName·lang)·`@ApiResponses` 200/400, `ResponseEntity<BaseResponse<FoodDetailResponse>>`) — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/food/FoodDetailApi.kt`
- [X] T052 [P] [US2] `FoodDetailResponse` DTO(name, imageRef?, ingredients[name, iconRef?, inclusionPercent, riskStatus]) + `from(result: GetFoodDetailResult)` — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/food/FoodDetailResponse.kt`
- [X] T053 [US2] `FoodDetailController`(GET `ApiPaths.V1 + "/foods/detail"`, `@RequestParam menuName`·`@RequestParam(required=false) lang`; menuName blank → `IllegalArgumentException("menuName은 필수입니다")`; `GetFoodDetailInput` 조립 → usecase → `BaseResponse.ok(FoodDetailResponse.from(result))`) — `meogo-api/presentation/src/main/kotlin/com/meogo/api/presentation/food/FoodDetailController.kt` (T050·T051·T052)
- [X] T054 [US2] 미수록 메뉴·menuName blank 모두 `IllegalArgumentException` → **기존 `GlobalExceptionHandler` 400 매핑**으로 처리됨을 확인(핸들러 변경 불필요; 메시지로 구분). 404 매핑은 두지 않음 — `meogo-api/presentation/.../common/GlobalExceptionHandler.kt`
- [X] T055 [US2] US2 테스트 GREEN — `./gradlew :meogo-api:food:test :meogo-api:persistence:test :meogo-api:application:test :meogo-api:presentation:test`

**Checkpoint**: US1·US2 모두 독립 동작.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [ ] T056 [P] springdoc/Swagger 어노테이션 확인 — 두 엔드포인트 설명·예시·`BaseResponse` 봉투 노출(`/swagger-ui.html`) — `meogo-api/presentation/.../scan/MenuScanApi.kt`, `.../food/FoodDetailApi.kt`
- [ ] T057 quickstart.md curl 시나리오 수동 검증(local 프로필 `:meogo-api:presentation:bootRun`) — `specs/001-menu-scan-mock/quickstart.md`
- [ ] T058 [P] follow-up: `docs/architecture/domains/food.md`의 `0/1/2`를 '퍼센티지 산출용 후속 LLM per-recipe 스코어링 입력값'으로 위치 정리(표시·저장값은 연속 % `inclusionPercent` — 둘은 **별개 개념**)
- [ ] T059 전체 회귀 — `./gradlew build` 통과 + Success Criteria(SC-001~008) 체크리스트 대조

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(P1)** ✅ → **Foundational(P2)** ✅ → user stories
- **US1(P3)** ✅ 완료(MVP)
- **US2(P4)**: Foundational 후 시작. US1과 독립(food 도메인·persistence/food·application/food·presentation/food 분리) → 파일 충돌 없음. `GlobalExceptionHandler`는 **변경 없이 재사용**(미수록·blank 모두 `IllegalArgumentException`→400)
- **Polish(P5)**: US2 완료 후

### Within US2 (TDD, 헌법 I)

테스트 먼저(T030~T036, FAIL) → 도메인(T037~T041) → 영속/마이그레이션·시드(T042~T046) → application(T047~T050) → web(T051~T054) → GREEN(T055).

### Parallel Opportunities (US2)

- 테스트 T030~T036 전부 [P]
- 도메인 T037·T038·T039 [P] (T040은 의존)
- JPA 엔티티 T044 [P], seam/타입 T047·T048·T049 [P], web DTO/Api T051·T052 [P]

---

## Parallel Example: User Story 2 테스트(먼저 작성, 모두 FAIL)

```bash
Task: "MockIngredientRiskMarkerTest in meogo-api/application/src/test/.../food/MockIngredientRiskMarkerTest.kt"
Task: "LanguageResolverTest in meogo-api/application/src/test/.../food/LanguageResolverTest.kt"
Task: "FoodNameForTest in meogo-api/food/src/test/.../food/FoodNameForTest.kt"
Task: "FoodRepositoryAdapterTest in meogo-api/persistence/src/test/.../persistence/food/FoodRepositoryAdapterTest.kt"
Task: "FoodDetailControllerTest in meogo-api/presentation/src/test/.../food/FoodDetailControllerTest.kt"
Task: "FoodDetailLangTest in meogo-api/presentation/src/test/.../food/FoodDetailLangTest.kt"
Task: "FoodDetailErrorTest in meogo-api/presentation/src/test/.../food/FoodDetailErrorTest.kt"
```

---

## Implementation Strategy

### MVP First (US1) — ✅ 완료

Setup → Foundational → US1 → 데모(스캔 200/400 + 저장).

### Incremental Delivery

1. (완료) Setup + Foundational + US1(MVP)
2. **US2(음식 상세)** → 독립 검증 → 데모
3. Polish

---

## Notes

- [P] = 다른 파일·의존 없음. 같은 파일 수정 task는 [P] 아님.
- 각 task는 **실패 테스트 먼저 → 최소 구현 → 리팩터**(헌법 I). 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).
- **작업/논리 단위마다 커밋**(헌법 Development Workflow).
- 헌법 IV(+ADR-0006): JPA 엔티티·Spring Data·RepositoryAdapter 는 **중앙 `:meogo-api:persistence`**(`com.meogo.api.persistence.*`)에 둔다 — application/presentation 은 import 안 함. 도메인은 순수 model+port. 도메인↔JPA 변환은 JPA 엔티티 안(`toDomain`/`from`).
- 헌법 V(v2.0.0): 음식 콘텐츠는 `ko` 원문 + 9개 대상 언어 저장 — seed가 번역 직접 보유. 실제 번역 생성(배치)·회원 언어 해석은 비범위.
- 미수록 메뉴 = **400**(clarify 2026-06-28). real 동작(스캔 미수록 → UNKNOWN, research 대기열)은 다음 사이클(ADR-0003/0004).
- 재료 `riskStatus`는 4단계 `RiskLevel` 재사용(저장 안 함, application mock marker 부여). UI '안전/문의 필요' 2상태는 클라이언트 매핑.
