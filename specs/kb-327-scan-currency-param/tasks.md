# Tasks: 스캔 2.0 통화 환산 기준을 currency 요청 파라미터로 전환

**Input**: Design documents from `/specs/kb-327-scan-currency-param/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scan-v2-currency-param.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). Every user story MUST include failing tests written BEFORE its implementation (Red → Green → Refactor).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

`:api` 모듈 단독 변경 — 소스 `api/src/main/kotlin/com/kbap/api/scan/`, 테스트 `api/src/test/kotlin/com/kbap/api/scan/`. DB·Flyway·`:common`·인프라 변경 없음.

## Phase 1: Setup (Shared Infrastructure)

**해당 없음** — 신규 모듈·의존성·마이그레이션·설정이 없다. 기존 `CurrencyCode`·`ErrorCode.INVALID_CURRENCY_CODE`(`MEMBER-010`)·`ScanControllerTest` 픽스처를 그대로 재사용한다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**해당 없음** — 스캔 2.0 통화 환산 정보(KB-323)는 develop 에 이미 머지되어 있다.

**Checkpoint**: 바로 User Story 1 시작 가능.

---

## Phase 3: User Story 1 - 요청 파라미터로 환산 통화 지정 (Priority: P1) 🎯 MVP

**Goal**: 2.0 스캔 요청의 `currency` 쿼리 파라미터가 회원 프로필 설정보다 우선해 응답 통화 환산 정보를 결정한다. 지원하지 않는 값은 요청 경계에서 `MEMBER-010`(400)으로 스캔 실행 전에 실패한다 (contracts/scan-v2-currency-param.md).

**Independent Test**: 프로필 통화가 USD 인 회원으로 `currency=JPY` 를 지정해 2.0 스캔(`X-API-Version: 2.0`)을 호출해 `payload.currency.code == "JPY"` && `payload.currency.krwPerUnit == 8.8906` 을 확인하고, `currency=XXX` 호출이 400·`code == "MEMBER-010"` 으로 실패하는지 확인한다.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T001 [US1] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 에 Kotest BehaviorSpec(given/when/then 한국어) 시나리오 3건 추가 후 **Red 확인**: (a) 프로필 통화 USD 회원 + `currency=JPY` 파라미터 2.0 스캔 → `payload.currency.code == "JPY"` && `payload.currency.krwPerUnit == 8.8906` (프로필 무시), (b) 프로필 통화 미설정 회원 + `currency=USD` → `payload.currency.code == "USD"` && `payload.currency.krwPerUnit == 1416.0000`, (c) `currency=XXX` → HTTP 400 · `success == false` · `code == "MEMBER-010"` · 스캔 횟수 미증가(비전 호출 전 차단). 회원 픽스처는 기존 가입·온보딩 헬퍼 재사용. 실행: `./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"` — 3건 모두 실패(Red)해야 한다 (현재는 파라미터가 무시되므로 (a)(b)는 프로필 기준 값, (c)는 200 이 나온다).

### Implementation for User Story 1

- [X] T002 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt` — `scanMenuBoardImageV2` 와 내부 `scan()` 에 `requestedCurrency: CurrencyCode? = null` 파라미터 추가, 통화 결정을 `currency = requestedCurrency ?: member.profile.currency` 로 변경 (raw 문자열 검증을 서비스에 두지 않는다 — 확정 타입 수신, 헌법 V·research R4)
- [X] T003 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanV2Controller.kt` — `@RequestParam(required = false) currency: String?` 추가 후 `currency?.let { CurrencyCode.from(it) ?: throw BusinessException(ErrorCode.INVALID_CURRENCY_CODE) }` 로 확정해 서비스에 전달(검증은 요청 경계 소유 — research R4), `api/src/main/kotlin/com/kbap/api/scan/ScanV2Api.kt` — 같은 파라미터에 swagger `@Parameter` 문서 추가(선택값·ISO 4217·프로필보다 우선·미전달 시 프로필 fallback·잘못된 값 MEMBER-010. Spring 애너테이션은 컨트롤러에, 문서는 인터페이스에 규약 준수)
- [X] T004 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanV2Response.kt` — `currency` 필드 `@Schema` 설명을 "요청 `currency` 파라미터 우선, 미전달 시 회원 프로필 통화, 둘 다 없으면 null" 로 갱신 — 완료 후 T001 테스트 **Green 확인**, 필요 시 Refactor

**Checkpoint**: 파라미터 지정 스캔이 프로필과 무관하게 동작 — US1 단독 검증 가능.

---

## Phase 4: User Story 2 - 파라미터 미전달 시 기존 동작 보존 (Priority: P2)

**Goal**: `currency` 파라미터 없는 2.0 스캔은 도입 전과 완전히 동일하다 — 프로필 통화 fallback, 프로필도 없으면 `currency: null` 성공 (contracts 결정표 3·4행).

**Independent Test**: 프로필 통화 USD 회원으로 파라미터 없이 2.0 스캔 → `payload.currency.code == "USD"`, 통화 미설정 회원 → `payload.currency == null` 확인.

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T005 [US2] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 에 시나리오 추가: (a) 프로필 통화 USD 회원의 파라미터 없는 2.0 스캔 → `payload.currency.code == "USD"` && `payload.currency.krwPerUnit == 1416.0000`, (b) 통화 미설정 회원의 파라미터 없는 2.0 스캔 → HTTP 200 · `payload.currency == null` · `payload.results` 정상. **주의**: KB-323 기존 테스트가 이미 이 계약을 덮고 있으면 중복 작성하지 말고 해당 테스트가 그대로 통과함을 확인하는 것으로 대체한다(회귀 방지 계약 고정). 신규 작성분은 T002 구현 전 Red 가 아닐 수 있다 — fallback 이 기존 동작이므로 즉시 Green 이면 계약 고정 테스트로 기록한다.

### Implementation for User Story 2

- [X] T006 [US2] T005 가 Red 인 경우에만: fallback 누락 지점을 `ScanService.kt` 에서 수정해 Green 확인. (T002 의 `?: member.profile.currency` 가 정확하면 코드 변경 0건 — 태스크를 "검증만"으로 종료)

**Checkpoint**: 두 스토리 모두 독립 검증 가능 — 기존 클라이언트 동작 불변이 테스트로 고정된다.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T007 전체 검증: `./gradlew :api:test` (ArchUnit `ModuleBoundaryTest` 포함 — 허용 맵 수정 불필요 확인) 통과 후 `./gradlew build` 로 전 모듈 통과 확인. quickstart.md 시나리오와 대조하고, Swagger `X-API-Version: 2.0` 그룹에 `currency` 파라미터가 노출되는지 확인

---

## Phase 6: 계약 개정 — currency 필수화·프로필 fallback 제거 (2026-08-12)

> 사용자 결정: fallback 경로가 남으면 회원/비회원의 미전달 동작이 갈라진다 — `currency` 를 필수로 하고 스캔 경로의 프로필 통화 참조를 제거한다 (research R2 개정). US2 는 "미전달 시 기존 동작 보존"에서 "누락 시 명시적 거절"로 대체됐다.

- [X] T008 `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` — `v2Scan` 헬퍼 기본 `currency = "USD"` 로 전 v2 시나리오가 필수 계약을 충족하게 하고, KB-323 의 프로필 fallback 시나리오 2건(프로필 USD → USD / 미설정 → null)을 제거·대체: 신규 "currency 누락 → 400 COMMON-002 · 스캔 미실행" 시나리오 추가 후 **Red 확인**(당시 구현은 fallback 200)
- [X] T009 구현: `ScanV2Controller` — `@RequestParam currency: String`(필수, 누락은 Spring 검증 → `GlobalExceptionHandler` 의 `ErrorResponse` 분기에서 400 COMMON-002), `ScanService.scanMenuBoardImageV2` — `requestedCurrency: CurrencyCode`(non-null)·프로필 통화 참조 제거(`member.profile.currency` 미사용), `ScanV2Api`/`ScanV2Response` — 필수·프로필 미참조로 문서 갱신 — **Green 확인** 후 spec·plan·research·data-model·contracts·quickstart 정합화
- [X] T010 Refactor: 서비스의 순수 통과 파라미터 제거 — `ScanService.scanMenuBoardImageV2` 시그니처에서 `requestedCurrency` 제거, `ScanResult.currency` 필드 제거(KB-323 이전 형태 복귀), 통화 결합을 컨트롤러 응답 조립로 이동(`ScanV2Response.from(result, currency)` — currency 필드 non-null 화). 검증은 스캔 실행 전 유지(미지원 값 → 스캔 카운트 미증가 테스트가 고정). 테스트 무수정 **Green 확인**(계약 불변 증명)

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1·2: 태스크 없음 — 즉시 Phase 3 시작.
- Phase 3 (US1): T001(Red) → T002 → T003 → T004(Green). 서비스 시그니처(T002)가 컨트롤러 호출부(T003)보다 먼저 바뀌어야 컴파일이 유지된다 — 순차.
- Phase 4 (US2): T005 는 T001 과 같은 파일이라 병렬 불가 — T001 시점에 함께 작성 권장. T006 은 T005 결과에 조건부.
- Phase 5: 모든 스토리 완료 후.

### User Story Dependencies

- US1: 선행 없음 (MVP).
- US2: 구현 코드를 US1 과 공유(`ScanService.kt`) — 독립 테스트는 가능하나 구현은 US1 에 얹힌다.

### Parallel Opportunities

**없음** — 전 태스크가 동일 파일 묶음(`ScanControllerTest.kt` + scan 소스 4파일)을 순차로 만진다. [P] 마커 해당 태스크 없음. (T001+T005 테스트를 한 번에 작성하는 것이 유일한 배치 최적화다.)

---

## Implementation Strategy

**MVP = US1 단독**: 파라미터 우선 + 잘못된 값 400 까지가 이 기능의 핵심 가치다. US2 는 기존 동작의 회귀 방지 고정이라 구현 비용이 거의 0이며, US1 구현(T002)의 `?:` fallback 이 곧 US2 구현이다. 전체가 한 PR 로 나가는 SP-1 작업이므로 스토리 단위 배포 분할은 하지 않되, 테스트는 스토리별로 독립 검증 가능하게 둔다.
