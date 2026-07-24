---
description: "Task list for KB-111 홈 화면 조회"
---

# Tasks: 홈 화면 조회 — 기피 성분·인기 음식 5개·최근 스캔 10개

**Input**: Design documents from `specs/kb-111-home-screen/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/home-api.md

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 유스케이스/스토리는 구현 전 실패 테스트(Kotest `BehaviorSpec`, given/when/then 한국어)를 먼저 작성해 Red 를 확인한다.

**Organization**: 스토리별 phase. 의존 순서상 US3(스캔 이력)·US4(프로바이더 교체) 인프라가 US1(회원 홈)보다 먼저 온다 — 실행 순서 = 의존 순서.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 서로 다른 파일·선행 의존 없음 → 병렬 가능
- **[Story]**: US1~US4 (spec.md 스토리)

## Path Conventions

모듈러 모놀리스: `core/<ctx>/`, `application/client/`, `infra/persistence/`, `app/api/`. 패키지는 모듈 경로 미러링.

---

## Phase 1: Setup (공유 인프라)

**Purpose**: 신규 `:core:scan` 컨텍스트 골격 + 스캔 이력 테이블

- [X] T001 `settings.gradle.kts` 의 core 컨테이너에 `":core:scan"` include 추가 (kernel/food/member/avoidance 옆)
- [X] T002 [P] `core/scan/build.gradle.kts` 생성 — `plugins { id("meogo.domain-conventions") }` 한 줄
- [X] T003 [P] 스캔 이력 마이그레이션 작성 `app/api/src/main/resources/db/migration/V2026.07.12.HH.mm.ss__create_scan_history_table.sql` — data-model.md 의 `scan_history`(member_id·food_id·status·created_at·updated_at, `idx_scan_history_recent(member_id, created_at)`, FK 없음). 파일명 `HH.mm.ss` 는 생성 시각 로컬로 zero-pad

**Checkpoint**: `./gradlew :core:scan:build` 가 (빈 모듈이라도) 성공하고 마이그레이션 SQL 이 로컬 MySQL 에 적용된다.

---

## Phase 2: Foundational (선택 인증 — 모든 스토리 선행)

**Purpose**: 헤더 없음→비회원 / 무효·만료 토큰→401 을 처리하는 선택 인증 리졸버. US1·US2·US3·US4 의 컨트롤러가 모두 사용.

**⚠️ CRITICAL**: 이 phase 완료 전에는 어떤 스토리 컨트롤러도 배선할 수 없다.

- [X] T004 [P] `@AuthMemberIdOrNull` 파라미터 애너테이션 생성 `app/api/src/main/kotlin/com/meogo/app/api/common/auth/AuthMemberIdOrNull.kt` (Target VALUE_PARAMETER)
- [X] T005 `AuthMemberIdOrNullArgumentResolver` 구현 `app/api/src/main/kotlin/com/meogo/app/api/common/auth/AuthMemberIdOrNullArgumentResolver.kt` — `TokenParser` 주입, `Authorization` 헤더 없음/`Bearer ` 아님→`null`, 있으면 `parseAccessToken(token).memberId`(위조·만료 시 `AuthException` 전파 → `GlobalExceptionHandler` 401). 지원 파라미터 = `@AuthMemberIdOrNull` + 타입 `Long?`
- [X] T006 `WebMvcAuthConfig.addArgumentResolvers` 에 `AuthMemberIdOrNullArgumentResolver(tokenParser)` 등록 (수정) `app/api/src/main/kotlin/com/meogo/app/api/common/auth/WebMvcAuthConfig.kt`

**Checkpoint**: 리졸버 동작은 US1(유효 토큰)·US2(헤더 없음·무효 토큰 401) 컨트롤러 통합 테스트로 검증한다(별도 단위 테스트 없이 실사용 슬라이스로 커버).

---

## Phase 3: US3 — 스캔 이력 기록 + 최근 스캔 규칙 (Priority: P1) 🎯

**Goal**: 회원 스캔 시 매칭된 완성(READY) 음식이 회원별로 기록되고, 최근 스캔 조회가 dedup·시각 내림차순·READY·limit 규칙을 만족한다. (최근 스캔 섹션의 데이터 원천.)

**Independent Test**: 회원으로 같은/다른 메뉴를 여러 번 스캔 → `findRecentReadyFoodIds` 가 dedup·정렬·10개·READY 만 반환하는지 영속 통합 테스트로, 비회원 미기록·매칭 READY 만 기록을 유스케이스 단위로 검증.

### Tests (먼저 작성 — Red)

- [X] T007 [P] [US3] `ScanUseCase` 이력 기록 단위 테스트(실패) `application/client/src/test/kotlin/com/meogo/application/client/scan/usecase/ScanUseCaseHistoryTest.kt` — 페이크 `ScanHistoryRepository`: 회원 memberId 로 매칭 READY 음식만 `saveAll`, 비회원(null) 미기록, 미매칭·비-READY 미기록
- [X] T008 [P] [US3] `ScanHistoryRepositoryAdapter` 영속 통합 테스트(실패) `infra/persistence/src/test/kotlin/com/meogo/infra/persistence/scan/ScanHistoryRepositoryAdapterTest.kt` — Testcontainers: 같은 food 중복→최신 1건, 시각 내림차순, READY 아닌 food 제외, limit 준수

### Implementation (Green)

- [X] T009 [P] [US3] `ScanHistory` 도메인 `core/scan/src/main/kotlin/com/meogo/core/scan/ScanHistory.kt` — `@AggregateRoot`, memberId·foodId·scannedAt(=createdAt), `record(memberId, foodId)` 팩토리, 불변
- [X] T010 [P] [US3] `ScanHistoryRepository` port `core/scan/src/main/kotlin/com/meogo/core/scan/ScanHistoryRepository.kt` — `saveAll(records)`, `findRecentReadyFoodIds(memberId, limit): List<Long>`
- [X] T011 [US3] `infra/persistence/build.gradle.kts` 에 `"implementation"(project(":core:scan"))` 추가, `application/client/build.gradle.kts` 에도 `"implementation"(project(":core:scan"))` 추가
- [X] T012 [US3] `ScanHistoryJpaEntity` `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/scan/ScanHistoryJpaEntity.kt` — `BaseEntity` 상속(scanned_at 컬럼 없이 created_at 재사용), `toDomain()`/`from(domain)`
- [X] T013 [US3] `ScanHistoryJpaRepository` `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/scan/ScanHistoryJpaRepository.kt` — `findRecentReadyFoodIds` 네이티브(research R3 SQL: JOIN food READY·ACTIVE, GROUP BY food_id, MAX(created_at) DESC, LIMIT)
- [X] T014 [US3] `ScanHistoryRepositoryAdapter` `infra/persistence/src/main/kotlin/com/meogo/infra/persistence/scan/ScanHistoryRepositoryAdapter.kt` — port 구현, 엔티티 변환만 위임
- [X] T015 [US3] `ScanInput` 에 `memberId: Long?` 추가 `application/client/src/main/kotlin/com/meogo/application/client/scan/dto/ScanInput.kt`
- [X] T016 [US3] `ScanUseCase` 수정 `application/client/src/main/kotlin/com/meogo/application/client/scan/usecase/ScanUseCase.kt` — `ScanHistoryRepository` 주입, 매칭 결과에서 READY(foodId non-null & isReady) 항목을 memberId!=null 일 때 `ScanHistory.record` 로 모아 `saveAll`(외부 정제 호출 뒤 단발 write)
- [X] T017 [US3] `ScanController` 에 `@AuthMemberIdOrNull` 배선 `app/api/src/main/kotlin/com/meogo/app/api/scan/ScanController.kt` — memberId 를 `ScanRequest.toInput(memberId)` 로 전달 (요청/응답 스키마 불변)

**Checkpoint**: T007·T008 Green. 스캔 후 이력이 쌓이고 최근 조회 규칙이 지켜진다. (홈 노출은 US1.)

---

## Phase 4: US4 — 기피 성분 프로바이더 교체 (Priority: P2)

**Goal**: 고정 5개를 반환하던 임시 프로바이더를 회원 프로필 기반으로 교체 → 기존 음식 위험도 판정이 회원별로 정확해진다. 기존 기능 무회귀.

**Independent Test**: 서로 다른 기피 성분을 가진 두 회원(토큰)으로 음식 목록/상세를 호출해 각자 프로필 기준 위험도가 나오는지, 비회원은 미강조(UNKNOWN)인지 통합 테스트로 검증.

### Tests (먼저 작성 — Red)

- [X] T018 [P] [US4] `MemberAvoidedSubstanceProvider` 단위 테스트(실패) `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/MemberAvoidedSubstanceProviderTest.kt` — 페이크 `MemberRepository`: null→empty, 회원 프로필 코드 반환, 회원 미존재·미설정→empty

### Implementation (Green)

- [X] T019 [US4] `AvoidedSubstanceProvider` 시그니처 변경 `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/AvoidedSubstanceProvider.kt` — `avoidedCodes(memberId: Long?): Set<AvoidanceSubstanceCode>`
- [X] T020 [US4] `MemberAvoidedSubstanceProvider` 신규 `@Component` + `MockAvoidedSubstanceProvider` 삭제 `application/client/src/main/kotlin/com/meogo/application/client/food/usecase/MemberAvoidedSubstanceProvider.kt` — `MemberRepository` 주입, memberId→프로필 `avoidanceSubstanceCodes.value`→`AvoidanceSubstanceCode` enum, 실패 시 empty
- [X] T021 [US4] Browse/Search/GetFoodDetail Input 에 `memberId: Long?` 추가 + 각 UseCase 에서 `avoidedSubstanceProvider.avoidedCodes(input.memberId)` 호출로 변경 `application/client/src/main/kotlin/com/meogo/application/client/food/dto/{BrowseFoodsInput,SearchFoodsInput,GetFoodDetailInput}.kt`, `.../food/usecase/{BrowseFoodsUseCase,SearchFoodsUseCase,GetFoodDetailUseCase}.kt`
- [X] T022 [US4] 음식 컨트롤러 3종(list·search·detail)에 `@AuthMemberIdOrNull` 배선 → Input.memberId 전달 `app/api/src/main/kotlin/com/meogo/app/api/food/*Controller.kt`
- [X] T023 [US4] 회귀 갱신 — 기존 food/scan **단위** 테스트 페이크 프로바이더 시그니처(`memberId`)에 맞추고, food **통합** 테스트(FoodList/Search/Detail·Scan ControllerTest)를 회원+토큰+프로필 시드(특정 기피) 또는 비회원 empty(UNKNOWN) 기대로 갱신 `app/api/src/test/kotlin/com/meogo/app/api/food/*Test.kt`, `.../scan/*Test.kt`, `application/client/src/test/.../food/usecase/*Test.kt`

**Checkpoint**: T018 Green + 전체 food/scan 테스트 회귀 통과. 음식 위험도가 회원별로 판정된다.

---

## Phase 5: US1 — 회원의 홈 화면 통합 조회 (Priority: P1) 🎯 MVP 완성

**Goal**: 로그인 회원이 `GET /api/v1/home` 한 번으로 기피 성분·인기 음식 5·최근 스캔 10을 프로필 언어로 받는다.

**Depends on**: US3(최근 스캔 read), US4(회원 기피 프로바이더), Foundational(선택 인증).

**Independent Test**: 온보딩 완료 회원 토큰으로 홈 조회 → 세 섹션이 한 응답에 포함되고 프로필 언어로 번역, 기피 없음/스캔 없음이 각각 []로 오는지 통합 테스트.

### Tests (먼저 작성 — Red)

- [X] T024 [P] [US1] `HomeQueryUseCase` 단위 테스트(실패) `application/client/src/test/kotlin/com/meogo/application/client/home/HomeQueryUseCaseTest.kt` — 페이크 repo: 회원 3섹션 조합, 언어=appLanguage, 최근 스캔 foodId 순서대로 재정렬, 기피/스캔 없음 [], appLanguage null→EN
- [X] T025 [P] [US1] `HomeController` 회원 통합 테스트(실패) `app/api/src/test/kotlin/com/meogo/app/api/home/HomeControllerTest.kt` — MockMvc+Testcontainers: 회원 세 섹션/일본어 번역/기피 없음 []/스캔 없음 []

### Implementation (Green)

- [X] T026 [US1] `FoodRepository` 확장 `core/food/src/main/kotlin/com/meogo/core/food/FoodRepository.kt` — `findRandomReady(size)`, `findAllReadyByIds(ids)`; 어댑터 `infra/persistence/.../food/FoodRepositoryAdapter.kt` + `FoodJpaRepository.kt` 에 `findRandomReadyIds(size)` 네이티브(`ORDER BY RAND()`) 추가, 기존 `findByIdInWithAvoidanceSubstances` 재사용
- [X] T027 [P] [US1] `HomeResult`·`AvoidedSubstanceView` DTO `application/client/src/main/kotlin/com/meogo/application/client/home/dto/{HomeResult,AvoidedSubstanceView}.kt`
- [X] T028 [US1] `HomeQueryUseCase` `application/client/src/main/kotlin/com/meogo/application/client/home/HomeQueryUseCase.kt` — `@Transactional(readOnly=true)`: memberId→Member 조회(lang=appLanguage?:EN, avoided codes), 기피 섹션=`AvoidanceSubstanceRepository.findByCodes`+`displayName(lang)`, 인기=`findRandomReady(5)`→`FoodSummaryView.from(.., avoidedRefs)`, 최근=`findRecentReadyFoodIds(memberId,10)`→`findAllReadyByIds`→foodId 순 재정렬→`FoodSummaryView`
- [X] T029 [US1] `HomeResponse` DTO + `HomeApi`(swagger) + `HomeController` `app/api/src/main/kotlin/com/meogo/app/api/home/{HomeResponse,HomeApi,HomeController}.kt` — `@RequestMapping(ApiPaths.V1 + "/home")`, `@AuthMemberIdOrNull`, `ResponseEntity<BaseResponse<HomeResponse>>`, `HomeResponse.from(result)`

**Checkpoint**: T024·T025 Green. 회원 홈이 완결(MVP = Phase 1~5).

---

## Phase 6: US2 — 비회원의 홈 화면 조회 (Priority: P2)

**Goal**: 토큰 없는 사용자가 홈에서 인기 음식 5개(영어)를 보고, 개인화 섹션은 null. 무효/만료 토큰은 401.

**Depends on**: US1(홈 엔드포인트).

**Independent Test**: 인증 없이 홈 조회 → avoidedSubstances/recentScans null·인기음식 영어. 위조/만료 토큰 → 401.

### Tests (먼저 작성 — Red)

- [X] T030 [P] [US2] `HomeController` 비회원 통합 테스트(실패) `app/api/src/test/kotlin/com/meogo/app/api/home/HomeGuestTest.kt` — 헤더 없음: avoided·recent null·인기음식 영어·위험도 UNKNOWN; 위조 토큰·만료 토큰 → 401 BaseResponse.fail

### Implementation (Green)

- [X] T031 [US2] `HomeQueryUseCase` 비회원 분기 확정 `application/client/src/main/kotlin/com/meogo/application/client/home/HomeQueryUseCase.kt` — memberId null → avoidedSubstances=null·recentScans=null·lang=EN·인기음식만(avoided empty). (T028 에서 분기 구현 시 이 태스크는 검증·보정)

**Checkpoint**: T030 Green. 비회원/무효토큰 경로 완결.

---

## Phase 7: Polish & 교차 관심사

- [X] T032 [P] `ModuleBoundaryTest` 가 `:core:scan` 을 커버하는지 확인·보정(도메인 Spring/ORM-free, 의존 방향) `app/api/src/test/kotlin/com/meogo/app/api/architecture/ModuleBoundaryTest.kt`
- [X] T033 [P] `ScanApi` swagger 설명 갱신 — "스캔 내역은 저장하지 않으며" → 회원 토큰 시 이력 기록됨으로 정정 `app/api/src/main/kotlin/com/meogo/app/api/scan/ScanApi.kt`
- [X] T034 [P] `HomeApi` swagger 예시(회원/비회원/401) 보강 `app/api/src/main/kotlin/com/meogo/app/api/home/HomeApi.kt`
- [X] T035 로컬 MySQL 에 scan_history 마이그레이션 적용 후 `./gradlew build` 전체 통과 확인 + quickstart.md 시나리오 1~4 수동 검증

---

## Dependencies & 실행 순서

```
Phase 1 (Setup: :core:scan, 마이그레이션)
        ↓
Phase 2 (Foundational: 선택 인증 리졸버)
        ↓
Phase 3 (US3 스캔 이력) ──┐
        ↓                 │  US1 은 US3(최근 read)+US4(기피 프로바이더) 필요
Phase 4 (US4 프로바이더) ──┤
        ↓                 │
Phase 5 (US1 회원 홈) ◀───┘
        ↓
Phase 6 (US2 비회원 홈)   (US1 엔드포인트 의존)
        ↓
Phase 7 (Polish)
```

- **US3·US4 는 서로 독립** — Phase 3·4 는 병렬 진행 가능(다른 파일군). 단 T011(build.gradle 의존 추가)은 US3 선.
- **US1 은 US3+US4 완료 후** 시작(홈이 두 인프라를 조합).
- **US2 는 US1 후**(같은 엔드포인트의 비회원 분기).

## Parallel 예시

- Phase 1: T002·T003 병렬.
- Phase 3 tests: T007·T008 병렬. domain/port: T009·T010 병렬.
- Phase 4 vs Phase 3: 다른 개발자가 US4(T018~T022)를 US3 와 병렬 진행 가능(단 회귀 갱신 T023 은 T019~T022 후).
- Phase 5 tests: T024·T025 병렬, DTO T027 병렬.

## Implementation Strategy

- **MVP = Phase 1~5**(회원 홈 완결). US3·US4 는 홈이 성립하기 위한 필수 인프라라 MVP 에 포함된다.
- **증분 전달**: US3(스캔 쌓임) → US4(음식 개인화) → US1(회원 홈) → US2(비회원/401) 순으로 각 checkpoint 에서 독립 검증.
- **TDD 준수**(헌법 I): 각 phase 의 Tests 태스크를 먼저 Red 로 만든 뒤 Implementation 으로 Green.
- **회귀 리스크**: 프로바이더 교체(US4)가 기존 food/scan 통합 테스트를 깨뜨린다 — T023 에서 반드시 함께 갱신(미갱신 시 CI 실패).
