# Tasks: 스캔 응답 DB 매칭 음식명 번역 (KB-189)

**Input**: Design documents from `specs/kb-189-scan-name-translation/`

**Prerequisites**: plan.md, spec.md, research.md, quickstart.md (data-model·contracts 없음 — 스키마·API 필드 구조 변경 0)

**Tests**: Test-First (헌법 원칙 I). 동작이 **변경**되는 유일 지점은 US1(매칭 항목 name 번역)이므로 US1 만 Red→Green 사이클을 돈다. US2(미매칭 유지)·US3(ko 폴백)는 US1 의 단일 분기(`if (matched) displayName else menu.name`)가 함께 결정하는 **기존 동작 보존/폴백 규칙**이라, 회귀 가드 테스트를 동반 작성한다(KB-170 선례 — 계약 이동 지점만 Red).

**Organization**: 스토리별 phase. 단, 구현 파일이 `ScanService.kt` 한 지점이라 모든 테스트 태스크는 같은 파일(`ScanControllerTest.kt`)을 순차 수정한다 — [P] 없음.

## Path Conventions

- 프로덕션: `domain/scan/src/main/kotlin/com/kbap/domain/scan/ScanService.kt`, `app/api/src/main/kotlin/com/kbap/app/api/scan/ScanResponse.kt`
- 테스트: `app/api/src/test/kotlin/com/kbap/app/api/scan/ScanControllerTest.kt`
- 실행: `./gradlew :app:api:test --tests "com.kbap.app.api.scan.ScanControllerTest"`

---

## Phase 1: Setup

없음 — 신규 모듈·의존·설정 0. 기존 테스트 인프라(MockMvc·Testcontainers·FakeVision) 그대로 재사용.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: US1·US3 테스트가 쓸 시드 파라미터 확장 (기존 호출부는 기본값으로 무변경)

- [x] T001 `ScanControllerTest.kt` 시드 헬퍼 확장 — `seedMember` 에 `profile: String = "{}"` 파라미터(appLanguage JSON 주입용), `seedReadyFood` 에 `nameTranslations: String = "{}"` 파라미터 추가, 기존 호출부 컴파일 유지 확인 (`app/api/src/test/kotlin/com/kbap/app/api/scan/ScanControllerTest.kt`)

**Checkpoint**: 기존 테스트 전부 green 유지 상태에서 시드 확장 완료

---

## Phase 3: User Story 1 - DB 매칭 음식명이 회원 앱 언어로 표시된다 (P1) 🎯 MVP

**Goal**: 매칭(READY) 항목의 `name` 을 회원 앱 언어의 번역명으로 조립 — 버그 수정의 본체

**Independent Test**: 앱 언어 `en` 회원 + `name_translations.en` 보유 READY 음식 스캔 → `results[].name` = 영어 번역명

### Tests for User Story 1 (Red — 구현 전 작성·실패 확인) ⚠️

- [x] T002 [US1] Red 테스트 작성 — `ScanControllerTest.kt` 에 (a) 신규 시나리오: 앱 언어 `en` 회원(profile `{"appLanguage":"en"}`) + `name_translations` `{"en":"Kimchi Stew"}` READY 음식 스캔 → `results[0].name` = `"Kimchi Stew"` 단언, (b) 기존 매칭 시나리오의 name 단언 갱신: `"Kimchi 김치찌개"` → `"김치찌개"` (appLanguage 미설정 회원 → 헌법 V-1 ko 기본 — US3 미설정 엣지 겸 커버) (`app/api/src/test/kotlin/com/kbap/app/api/scan/ScanControllerTest.kt`)
- [x] T003 [US1] Red 확인 — `./gradlew :app:api:test --tests "com.kbap.app.api.scan.ScanControllerTest"` 실행, (a)·(b) 가 현재 구현(`name = menu.name`)에서 **실패**함을 확인

### Implementation for User Story 1

- [x] T004 [US1] Green 구현 — `ScanService.scanMenuBoardImage` 에 `val lang = memberService.getMember(memberId).profile.appLanguage ?: LanguageCode.KO` 추가, 항목 매핑에서 `matched` 를 지역 변수로 추출해 `name = if (matched) food!!.displayName(lang) else menu.name` 조립 (`domain/scan/src/main/kotlin/com/kbap/domain/scan/ScanService.kt`)
- [x] T005 [US1] Green 확인 — 동일 테스트 실행, T002 포함 `ScanControllerTest` 전체 통과 확인

**Checkpoint**: 번역 응답 동작 완성 — MVP. 이력 저장(`recordHistory` 는 `menu.name` 사용)·scan_count 등 기존 단언도 함께 green 이어야 한다

---

## Phase 4: User Story 2 - DB 미매칭 항목은 기존 동작을 유지한다 (P2)

**Goal**: 미매칭·INCOMPLETE 항목의 `name` = 비전 추출 이름 유지를 회귀 가드로 고정

**Independent Test**: DB 부재 메뉴 스캔 → `name` = 비전 추출값 그대로

### Tests for User Story 2 (회귀 가드 — US1 분기가 보존한 기존 동작의 고정)

- [x] T006 [US2] 미매칭 유지 단언 추가 — 기존 매칭/미매칭 혼합 시나리오의 미매칭 항목(`results[1]`)에 `name` = `"Bulgogi 미등록불고기501"` 단언 추가(현재 name 무단언), INCOMPLETE 케이스는 미등록명 스캔이 `createIncomplete` 등록 직후 INCOMPLETE 이므로 동일 시나리오가 커버함을 확인 (`app/api/src/test/kotlin/com/kbap/app/api/scan/ScanControllerTest.kt`)
- [x] T007 [US2] 통과 확인 — 테스트 실행, 추가 단언 green(기존 동작 보존 검증이므로 작성 즉시 통과가 정상)

**Checkpoint**: US1 번역 + US2 유지가 같은 응답 안에서 항목별 독립 동작(spec 엣지 케이스)

---

## Phase 5: User Story 3 - 번역 부재 시 한국어로 폴백한다 (P3)

**Goal**: 회원 앱 언어 번역 키 부재 시 `LocalizedText.resolve` ko 폴백 동작을 응답 경로에서 고정

**Independent Test**: 앱 언어 `en` 회원 + 번역 없는 READY 음식 스캔 → `name` = 한국어 이름

### Tests for User Story 3 (회귀 가드 — 기존 LocalizedText 폴백 규칙의 응답 경로 검증)

- [x] T008 [US3] 폴백 시나리오 추가 — 앱 언어 `en` 회원 + `name_translations` `{}` READY 음식 스캔 → `results[0].name` = 한국어 이름 단언 (미설정 회원 → ko 기본 케이스는 T002-(b)가 이미 커버) (`app/api/src/test/kotlin/com/kbap/app/api/scan/ScanControllerTest.kt`)
- [x] T009 [US3] 통과 확인 — 테스트 실행, 전 시나리오 green

**Checkpoint**: spec 의 세 스토리 + 엣지(미설정·빈 번역·혼합) 전부 테스트로 고정

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T010 `ScanResponse.kt` `name` 필드 Swagger 설명 갱신 — "사진에 표기된 그대로의 메뉴명…비전 인식이 읽은 원문" → 새 의미(매칭=회원 앱 언어 번역명·번역 부재 시 한국어, 미매칭=사진 원문) (`app/api/src/main/kotlin/com/kbap/app/api/scan/ScanResponse.kt`)
- [x] T011 전체 회귀 — `./gradlew test` green 확인 (E2E `MenuScanScenarioTest` 는 스캔 name 무단언이라 영향 없음을 확인 완료 — 실패 시 재점검), quickstart.md §1 시나리오 대조

---

## Dependencies & Execution Order

- **Phase 2 (T001)** → 모든 스토리 테스트의 전제 (시드 파라미터).
- **US1 (T002→T003→T004→T005)**: 엄격 순차 — Red 확인 전 구현 금지(헌법 I).
- **US2 (T006→T007)·US3 (T008→T009)**: US1 완료 후 순차. 같은 테스트 파일을 수정하므로 [P] 불가. US2·US3 상호 순서는 무관하나 우선순위대로 진행.
- **Polish (T010→T011)**: T010 은 문서만이라 언제든 가능하나, T011 전체 회귀는 마지막.

### Parallel Opportunities

없음 — 프로덕션 1지점 + 테스트 1파일 순차 수정이 가장 짧은 경로다(파일 충돌 방지).

---

## Implementation Strategy

**MVP = US1** (T001~T005): 이것만으로 버그가 고쳐지고 배포 가능하다. US2·US3 는 같은 분기가 이미 보장하는 동작을 테스트로 고정하는 회귀 가드, Polish 는 문서 정합·전체 회귀다. 태스크당(또는 논리 단위당) 커밋한다.
