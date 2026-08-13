# Tasks: 스캔 응답에 회원 통화 환산 정보 제공

**Input**: Design documents from `/specs/kb-323-scan-currency-conversion/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scan-v2-currency.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). Every user story MUST include failing tests written BEFORE its implementation (Red → Green → Refactor).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

`:api` 모듈 단독 변경 — 소스 `api/src/main/kotlin/com/kbap/api/scan/`, 테스트 `api/src/test/kotlin/com/kbap/api/scan/`. DB·Flyway·`:common`·인프라 변경 없음.

## Phase 1: Setup (Shared Infrastructure)

**해당 없음** — 신규 모듈·의존성·마이그레이션·설정이 없다. 기존 `CurrencyCode`(`:common`)·`ScanControllerTest` 픽스처를 그대로 재사용한다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**해당 없음** — 선행 인프라 작업이 없다. 회원 통화 설정 기능(KB-322)은 develop 에 이미 머지되어 있다.

**Checkpoint**: 바로 User Story 1 시작 가능.

---

## Phase 3: User Story 1 - 자국 통화로 메뉴 가격 가늠 (Priority: P1) 🎯 MVP

**Goal**: 통화가 설정된 회원의 2.0 스캔 성공 응답에 `currency: { code, krwPerUnit }` 를 응답 수준 필드로 추가한다. 기존 필드·1.0 응답은 불변.

**Independent Test**: 통화(USD)가 설정된 회원으로 2.0 스캔을 호출해 `payload.currency.code == "USD"`·`payload.currency.krwPerUnit == 1416.0000` 을 확인하고, 같은 조건의 1.0 스캔 응답에는 `currency` 필드가 없음을 확인한다.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T001 [US1] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 에 Kotest BehaviorSpec(given/when/then 한국어) 시나리오 2건 추가 후 **Red 확인**: (a) 통화(USD)가 설정된 회원의 2.0 스캔(`X-API-Version: 2.0`) → `payload.currency.code == "USD"` && `payload.currency.krwPerUnit == 1416.0000` (BigDecimal 스냅샷 값), (b) 동일 회원의 1.0 스캔 → `payload.currency` 노드 부재. 회원 픽스처는 기존 테스트의 가입·온보딩 헬퍼를 재사용하되 통화가 USD 로 확정되도록 구성(국가 기반 자동 지정 또는 프로필 수정 API 경유). 실행: `./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"` — (a)가 실패(Red)해야 한다. (b)는 즉시 통과할 수 있다(기존 계약 고정 성격).

### Implementation for User Story 1

- [X] T002 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanResult.kt` 에 `val currency: CurrencyCode? = null` 필드 추가 (`com.kbap.common.domain.CurrencyCode` import)
- [X] T003 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanService.kt` 의 `scan()` 첫 줄 `memberService.getMember(memberId)` 를 `val member = ...` 로 받아, 반환하는 `ScanResult(items, degraded)` 에 `currency = member.profile.currency` 를 전달 (추가 조회 금지 — 기존 호출 재사용, research R5)
- [X] T004 [US1] `api/src/main/kotlin/com/kbap/api/scan/ScanV2Response.kt` 에 최상위 `val currency: CurrencyResponse?` 필드 + 중첩 `data class CurrencyResponse(code: String, krwPerUnit: BigDecimal)` 추가, `from(result)` 에서 `result.currency?.let { CurrencyResponse(it.name, it.krwPerUnit) }` 매핑, contracts/scan-v2-currency.md 의미에 맞는 `@Schema` 설명(참고용 고정 스냅샷·클라이언트 환산식 `price ÷ krwPerUnit`·미설정 시 null) 부여 — 완료 후 T001 테스트 **Green 확인**, 필요 시 Refactor

**Checkpoint**: 통화 설정 회원의 2.0 응답에 통화 정보가 실리고 1.0 응답은 불변 — US1 단독 검증 가능.

---

## Phase 4: User Story 2 - 통화 미설정 회원의 스캔 (Priority: P2)

**Goal**: 통화가 설정되지 않은 회원의 2.0 스캔은 `currency: null` 인 채 정상 성공한다(부분 성공 정책).

**Independent Test**: 통화 미설정 회원으로 2.0 스캔을 호출해 스캔 결과는 정상(항목 판정 포함)이고 `payload.currency == null` 임을 확인한다.

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T005 [US2] `api/src/test/kotlin/com/kbap/api/scan/ScanControllerTest.kt` 에 시나리오 추가: 통화가 설정되지 않은 회원(통화 미매핑 국가로 온보딩하거나 통화 미지정 픽스처)의 2.0 스캔 → HTTP 200 · `success == true` · `payload.results` 정상 · `payload.currency == null`. **주의**: US1 구현(T002~T004)이 null 경로를 이미 다루므로 US1 완료 후에는 즉시 Green 일 수 있다 — US1 구현 전에 T001 과 함께 작성해 Red 를 확인하는 것을 권장하며, 이후에는 계약 고정(회귀 방지) 테스트로 기록한다.

### Implementation for User Story 2

- [X] T006 [US2] T005 가 Red 인 경우에만: null 처리 누락 지점을 `ScanService.kt`/`ScanV2Response.kt` 에서 수정해 Green 확인. (US1 구현이 nullable 로 정확히 되어 있으면 코드 변경 0건 — 태스크를 "검증만"으로 종료)

**Checkpoint**: 두 스토리 모두 독립 검증 가능 — 스캔 성공률이 통화 설정 여부와 무관함이 테스트로 고정된다.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T007 전체 검증: `./gradlew :api:test` (ArchUnit `ModuleBoundaryTest` 포함 — 허용 맵 수정이 필요 없음을 확인) 후 `./gradlew build` 로 전 모듈 통과 확인, quickstart.md 시나리오 3건과 대조

---

## Dependencies & Execution Order

### Phase Dependencies

- Phase 1·2: 태스크 없음 — 즉시 Phase 3 시작.
- Phase 3 (US1): T001(Red) → T002 → T003 → T004(Green). T002~T004 는 같은 흐름의 연속 변경이라 순차.
- Phase 4 (US2): T005 는 T001 과 같은 파일이라 병렬 불가 — T001 직후(권장, US1 구현 전 Red 확보) 또는 US1 완료 후 작성. T006 은 T005 결과에 조건부.
- Phase 5: 모든 스토리 완료 후.

### User Story Dependencies

- US1: 선행 없음 (MVP).
- US2: 구현 코드를 US1 과 공유(같은 3개 파일) — 독립 테스트는 가능하나 구현은 US1 에 얹힌다.

### Parallel Opportunities

**없음** — 전 태스크가 동일 파일 묶음(`ScanControllerTest.kt` + scan 소스 3파일)을 순차로 만진다. [P] 마커 해당 태스크 없음. (테스트 2건을 T001 시점에 한 번에 작성하는 것이 유일한 배치 최적화다.)

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 — 실패 테스트 작성·Red 확인 (T005 동시 작성 권장)
2. T002 → T003 → T004 — 최소 구현·Green·Refactor
3. **STOP and VALIDATE**: US1 단독 검증 (`./gradlew :api:test --tests "com.kbap.api.scan.ScanControllerTest"`)

### Incremental Delivery

1. US1 완료 → 2.0 통화 정보 제공 (MVP — 배포 가능)
2. US2 완료 → 미설정 null 계약 고정 (회귀 방지)
3. T007 → 전 모듈 검증 후 PR

---

## Notes

- 태스크 단위 커밋 (헌법 Development Workflow)
- Kotlin 소스 주석 금지 — 의도는 이름·구조로 (컨벤션 2026-08-11)
- 새 경로·필터 등록 불필요 — 기존 `/api/scans` 엔드포인트의 응답만 확장
- 버전 번호 유지 — additive 필드는 2.0 매핑 그대로 (contracts 참조)
