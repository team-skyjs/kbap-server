---
description: "Task list for 메뉴 스캔 수신 메뉴명 정제"
---

# Tasks: 메뉴 스캔 수신 메뉴명 정제 (LLM 추출 + 매칭)

**Input**: Design documents from `specs/kb-90-menu-name-refinement/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 I). 각 스토리는 구현 전에 실패 테스트를 먼저 작성·확인(Red→Green→Refactor)한다. 모든 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).

**흐름**: 정규화(빈 키→NOT_FOOD 게이트) → 비지 않은 전 항목 LLM 1콜(표준명|NOT_FOOD) → 표준명 exact 매치(hit=MATCHED/miss=PENDING+대기열)/NOT_FOOD=제외. **LLM 장애·미구성 시 정규화 exact 매치 폴백**(hit=MATCHED, miss=원문 PENDING+대기열).

**Organization**: US1(P1)=정상 경로 전체(MVP, 정제 서비스 구성 전제). US2(P2)=폴백 견고성.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·모듈, 선행 미완 의존 없음 → 병렬 가능
- 경로는 repository 루트 기준(모듈러 모놀리스, ADR-0008)

---

## Phase 1: Setup

- [X] T001 `application/client/build.gradle.kts` 에 `"implementation"(project(":core:food"))` 존재 확인·추가 (매칭에 `FoodRepository` 사용)
- [ ] T002 `app/api/build.gradle.kts` 에 `"runtimeOnly"(project(":infra:llm"))` 추가 (`ScannedNameInterpreter` 어댑터 런타임 조립 — 컴파일 의존 X)

---

## Phase 2: Foundational (모든 스토리 공통 선행 — 매칭 상태 골격)

**목적**: 스캔 항목의 매칭 상태(MATCHED/PENDING/NOT_FOOD)를 도메인·영속·응답에 관통시켜, US1·US2 가 스키마를 다시 고치지 않게 한다.

- [X] T003 [P] `core/scan/src/test/kotlin/com/meogo/core/scan/MenuItemMatchTest.kt` — `MenuItemMatch` 3상태(MATCHED foodId/PENDING/NOT_FOOD) 생성·불변 실패 테스트
- [X] T004 `core/scan/src/main/kotlin/com/meogo/core/scan/MenuItemMatch.kt` — sealed 값타입 구현(MATCHED 는 `foodId: Long`)
- [X] T005 `core/scan/src/test/kotlin/com/meogo/core/scan/MenuScanTest.kt` — `ScannedMenuItem` 에 `match: MenuItemMatch` 필드 추가 실패 테스트 보강
- [X] T006 `core/scan/src/main/kotlin/com/meogo/core/scan/ScannedMenuItem.kt` — `match` 필드 추가(불변, 상태 변경은 새 인스턴스 반환)
- [X] T007 `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/scan/ScannedMenuItemJpaEntity.kt` — `match_status VARCHAR(20)`·`matched_food_id BIGINT NULL` 매핑 + `toDomain`/`from` 에 match 반영
- [X] T008 `app/api/src/main/resources/db/migration/V<생성시각>__add_scanned_menu_item_match.sql` — `scanned_menu_items` 에 두 컬럼 추가(점 구분 timestamp, 순서 비의존)
- [X] T009 `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/scan/MenuScanRepositoryAdapterTest.kt` — match 상태 round-trip(저장→복원) 실패 테스트 보강
- [X] T010 `application/client/.../scan/dto/SubmitMenuScanResult.kt` + `app/api/.../scan/SubmitMenuScanResponse.kt` — 항목에 `matchStatus`·`foodId` 추가(기존 필드 유지 = 하위 호환), 매핑 배선

**Checkpoint**: 매칭 상태가 도메인·영속·응답에 관통. 아직 라우팅 로직 없음.

---

## Phase 3: User Story 1 — 정제해 매칭하고 미상은 대기열로 (Priority: P1) 🎯 MVP

**Goal**: 정규화 게이트 → 전부 LLM 음식명 추출 → 표준명 exact 매치 → MATCHED/PENDING+대기열/NOT_FOOD.

**Independent Test**: 저장된 `김치찌개` 에 `"김치찌개 kimchi jjigae"`·`"김치찌게"` → 둘 다 `김치찌개` MATCHED. `"우주라면"` → PENDING+`pending_menus` 1행. `"원산지 중국"`·`"MacBook Air F9"` → NOT_FOOD(대기열 미등록). (fake interpreter 로 검증)

### Tests (먼저 작성·Red 확인)

- [X] T011 [P] [US1] `core/kernel/src/test/kotlin/com/meogo/core/kernel/menu/KoreanMenuNameNormalizerTest.kt` — 혼합 로마자·선두기호·공백·숫자/라틴전용(빈 키)·띄어쓰기변형 표본으로 `matchKey` 계약(contracts/ports.md) 실패 테스트
- [X] T012 [P] [US1] `core/kernel/src/test/kotlin/com/meogo/core/kernel/scan/InterpretedNameTest.kt` — `InterpretedName`(StandardName blank 불가/NotFood) 값타입 실패 테스트
- [ ] T013 [P] [US1] `infra/llm/src/test/kotlin/com/meogo/infra/llm/menu/ScannedNameParserTest.kt` — LLM 배열 응답 파싱(정상·NOT_FOOD·부분 실패 예외) 실패 테스트
- [ ] T014 [P] [US1] `infra/llm/src/test/kotlin/com/meogo/infra/llm/menu/UpstageScannedNameInterpreterTest.kt` — fake `LlmModelCaller` 로 배열 1콜·입력순서 1:1·프롬프트 조립 검증
- [ ] T015 [P] [US1] `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/FoodRepositoryAdapterTest.kt` — `findByKoreanMatchKey`(hit 1/0/동음이의 2→최소 id) Testcontainers 실패 테스트 보강
- [ ] T016 [P] [US1] `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/food/FoodMatchKeySyncTest.kt` — kernel `matchKey` == foods 생성컬럼 `korean_match_key`(표본) 동등성 Testcontainers 검증
- [ ] T017 [P] [US1] `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/pending/PendingMenuRepositoryAdapterTest.kt` — `enqueue` 신규 삽입·동일 값 dedup(unique) Testcontainers 실패 테스트
- [ ] T018 [US1] `application/client/src/test/kotlin/com/meogo/application/client/scan/usecase/SubmitMenuScanUseCaseTest.kt` — 라우팅: 빈 키→NOT_FOOD(LLM 스킵), StandardName hit→MATCHED, miss→PENDING+enqueue, NotFood→NOT_FOOD(대기열 미등록), 스캔당 LLM 1콜 실패 테스트
- [ ] T019 [US1] `app/api/src/test/kotlin/com/meogo/app/api/scan/MenuScanControllerTest.kt` — MockMvc: fake interpreter 로 contracts/scan-api.md 4항목 시나리오 + 같은 미등록 2회→대기열 1행 (보강)

### Implementation

- [X] T020 [P] [US1] `core/kernel/src/main/kotlin/com/meogo/core/kernel/menu/KoreanMenuNameNormalizer.kt` — 순수 `matchKey(raw)`: NFC 후 `[가-힣]` 만 남김
- [X] T021 [P] [US1] `core/kernel/src/main/kotlin/com/meogo/core/kernel/scan/ScannedNameInterpreter.kt` — port `interpret(texts): List<InterpretedName>` + `InterpretedName` sealed
- [ ] T022 [P] [US1] `core/food/src/main/kotlin/com/meogo/core/food/FoodRepository.kt` — `findByKoreanMatchKey(key: String): List<Food>` port 추가
- [X] T023 [P] [US1] `core/scan/src/main/kotlin/com/meogo/core/scan/PendingMenu.kt` + `PendingMenuRepository.kt` — `enqueue(value)` port·큐 상태 값타입
- [ ] T024 [US1] `app/api/src/main/resources/db/migration/V<생성시각>__add_foods_korean_match_key.sql` — `korean_match_key` 생성 저장 컬럼(`REGEXP_REPLACE(korean_name,'[^가-힣]','')`) + `idx_foods_korean_match_key` (MySQL 8 전용)
- [ ] T025 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/food/FoodJpaEntity.kt`·`FoodJpaRepository.kt`·`FoodRepositoryAdapter.kt` — `korean_match_key` 읽기전용 매핑 + `findByKoreanMatchKey` 쿼리·어댑터(동음이의 시 최소 id + 경고 로깅)
- [ ] T026 [US1] `infra/llm/src/main/kotlin/com/meogo/infra/llm/menu/ScannedNameParser.kt` — 배열 응답 파서(research `ScoringResponseParser` 패턴)
- [ ] T027 [US1] `infra/llm/src/main/kotlin/com/meogo/infra/llm/menu/UpstageScannedNameInterpreter.kt` — 단일 Upstage `LlmModelCaller`, `@ConditionalOnProperty(meogo.llm.upstage.*)`, system 프롬프트+배열 JSON
- [ ] T028 [US1] `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/pending/PendingMenuJpaEntity.kt`·`PendingMenuJpaRepository.kt`·`PendingMenuRepositoryAdapter.kt` — BaseEntity 상속, `enqueue`=INSERT ON DUPLICATE KEY no-op
- [ ] T029 [US1] `app/api/src/main/resources/db/migration/V<생성시각>__create_pending_menus.sql` — `pending_menus`(standard_name UNIQUE, queue_status, BaseEntity 컬럼)
- [ ] T030 [US1] `application/client/src/main/kotlin/com/meogo/application/client/scan/usecase/SubmitMenuScanUseCase.kt` — 정규화 게이트(빈 키→NOT_FOOD) → 비지않은 전 항목 `interpret` 1콜 → StandardName exact 매치(hit=MATCHED/miss=PENDING+enqueue), NotFood→NOT_FOOD. LLM 호출은 트랜잭션 밖
- [ ] T031 [US1] `SubmitMenuScanResult`/`SubmitMenuScanResponse` 매핑 — matchStatus(MATCHED+foodId/PENDING/NOT_FOOD) 노출

**Checkpoint**: 정상 경로 완성 — 정제 서비스 구성 시 잡음·오탈자·미등록·비음식이 각 경로로. MVP 배포 가능.

---

## Phase 4: User Story 2 — 정제 서비스 장애에도 아는 메뉴 매칭 (Priority: P2)

**Goal**: LLM 미구성·실패·타임아웃 시 정규화 exact 매치 폴백 — 아는 메뉴 MATCHED 유지, 나머지 원문 PENDING+대기열, 스캔 성공.

**Independent Test**: 예외/타임아웃/부재(null) fake interpreter + 아는 메뉴 `"김치찌개"` + 잡음 → 김치찌개 MATCHED, 잡음 PENDING(원문 enqueue), 200. LLM 미구성 부팅 → web 정상.

### Tests (먼저 작성·Red 확인)

- [ ] T032 [P] [US2] `application/client/src/test/kotlin/com/meogo/application/client/scan/usecase/SubmitMenuScanUseCaseTest.kt` — interpreter 예외/타임아웃/null → 정규화 exact 매치 폴백(hit=MATCHED, miss=원문 PENDING+enqueue), 아는 메뉴 무영향 실패 테스트 보강
- [ ] T033 [US2] `app/api/src/test/kotlin/com/meogo/app/api/scan/MenuScanControllerTest.kt` — 실패 fake 주입 시 아는 메뉴 MATCHED + 잡음 PENDING + 200 (보강)

### Implementation

- [ ] T034 [US2] `application/client/src/main/kotlin/com/meogo/application/client/scan/usecase/SubmitMenuScanUseCase.kt` — interpreter 를 nullable/Optional 주입, 부재·예외·타임아웃 catch → 정규화 exact 매치 폴백(hit=MATCHED, miss=원문 PENDING+enqueue)
- [ ] T035 [US2] `SubmitMenuScanUseCase` 트랜잭션 경계 확정 — 저장(스캔) → 트랜잭션 밖 `interpret` → 결과로 항목 상태·대기열 확정 저장(Additional Constraints)

**Checkpoint**: 외부 장애가 부분 강등으로 격리. 스펙 2스토리 완성.

---

## Phase 5: Polish & Cross-Cutting

- [ ] T036 [P] `app/api/src/test/kotlin/com/meogo/app/api/scan/MenuScanRefinementRegressionTest.kt` — 실측 6종+"메뉴판"·잡음 혼합 회귀(SC-001): 6종 매칭/PENDING, "메뉴판"·잡음 미매칭
- [ ] T037 [P] `app/api/.../architecture/ModuleBoundaryTest.kt` 통과 확인 — kernel port·infra 조립 경계 위반 없음(신규 모듈 배치 검증)
- [ ] T038 [P] `app/api/src/main/kotlin/com/meogo/app/api/scan/SubmitMenuScanResponse.kt`·`MenuScanApi.kt` — 신규 필드 `@Schema` + Operation 설명에 matchStatus 의미 반영
- [ ] T039 로컬 docker MySQL 로 신규 Flyway 3종 검증(DROP+CREATE 부팅) — 생성 컬럼 `REGEXP_REPLACE`·pending_menus unique 실제 적용 확인([[flyway-migration-validation-gap]])
- [ ] T040 [P] `MockCyclingRiskAssessor` 경로 정리 — 매칭 결과와 무관하게 mock 위험도 유지 명시(범위 밖 표식), 기존 스캔 예제/테스트가 신규 필드로 깨지지 않게 갱신

---

## Dependencies & Execution Order

- **Setup(T001–T002)** → **Foundational(T003–T010)** → US1 → US2 → Polish.
- **US1(T011–T031)**: Foundational 완료 후. 완료 시 MVP(정제 서비스 구성 전제) 배포 가능. T002 build 배선 필요.
- **US2(T032–T035)**: US1 완료 후(정규화·매치·enqueue·interpreter 주입 존재 전제). 폴백 분기만 추가.
- **Polish(T036–T040)**: 해당 스토리 완료 후. T037/T039 는 언제든.

## Parallel Opportunities

- Foundational: T003(도메인)·T007(persistence)·T010(응답)은 파일 달라 부분 병렬.
- US1 테스트: T011–T017 병렬(kernel·llm·persistence 서로 다른 모듈). 구현 T020·T021·T022·T023 병렬.
- Polish: T036·T037·T038·T040 병렬.

## Implementation Strategy

**MVP = US1(P1)**. Setup+Foundational+US1 로 정상 경로(정규화→전부 LLM→매치→라우팅+대기열)가 성립 — 정제 서비스가 구성된 환경에서 단독 배포 가능. US2 로 LLM 장애·미구성 폴백(아는 메뉴 가용성)을 증분 추가한다. 각 스토리 끝 Checkpoint 에서 독립 검증한다.
