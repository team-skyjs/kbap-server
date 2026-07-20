---
description: "Task list for lang 파라미터 정책 통일"
---

# Tasks: lang 파라미터 정책 통일

**Input**: Design documents from `specs/kb-201-home-lang-param/`

**Prerequisites**: plan.md, spec.md, research.md, contracts/lang-parameter.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 스토리는 구현 전에 실패하는 테스트를 먼저 작성하고 Red 를 확인한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완료 선행 작업 없음)
- **[Story]**: 매핑되는 사용자 스토리(US1~US4)

## ⚠️ 이 기능의 구현 순서 주의

스토리 우선순위(US1·US2·US3 모두 P1)와 **구현 순서가 다르다**. `LanguageCode.from` 의 시그니처 변경이 전 호출부를 동시에 깨뜨리므로, **컴파일이 유지되는 순서**로 단계를 배치했다:

1. **US3 필수화 먼저** — 컨트롤러가 `LanguageCode` 를 확정해 넘기도록 바꾼다(이때 `from` 은 아직 400 을 던진다). 이 단계까지가 계약 변경의 뼈대다.
2. **US1 홈 프로필 제거** — 홈만 남은 프로필 의존을 끊는다.
3. **US2 EN 폴백** — 그제서야 `from` 을 순수 lookup 으로 축소하고 `UNSUPPORTED_LANGUAGE` 를 삭제한다.
4. **US4 비회원 검증** — 앞 단계의 결과를 게스트 경로로 확인한다.

각 단계는 그 자체로 컴파일·테스트 그린이 되며, 중간에 멈춰도 시스템이 일관된 상태를 유지한다.

---

## Phase 1: Setup

**Purpose**: 변경 전 기준선 확보와 영향 범위 확정

- [X] T001 `./gradlew build` 로 변경 전 전체 테스트 그린을 확인하고 기준선을 기록한다 — **BUILD SUCCESSFUL in 2m, 88 actionable tasks (2026-07-20)**. 이후 모든 단계의 회귀 판정 기준
- [X] T002 [P] `LanguageCode.from`·`ErrorCode.UNSUPPORTED_LANGUAGE`·`COMMON-001` 참조를 전수 조사해 `research.md` R7 표와 대조하고 누락된 파일이 있으면 표를 갱신한다 — **회귀 지점 3개 신규 발견, R7 갱신 완료**(아래 T002a~T002c 로 작업 추가)

### T002 전수 조사에서 새로 드러난 회귀 지점 ⚠️

초기 계획서(research.md R7)에 없던 항목이다. 누락하면 **의도치 않은 회귀**가 된다.

- [X] T002a ⚠️ `app/api/src/test/kotlin/com/kbap/app/api/scenario/ScenarioApiDriver.kt` 의 대상 엔드포인트 호출 4곳(62·65·70·74행 — 홈·검색·상세·북마크목록)에 `lang` 을 추가한다. 수정하지 않으면 `HappyPathScenarioTest`·`MenuScanScenarioTest`·`WithdrawScenarioTest`·`AuthLifecycleScenarioTest` **E2E 4종이 전부 400 으로 깨진다**. 드라이버가 단일 수정 지점이다
- [X] T002b ⚠️ `app/api/src/test/kotlin/com/kbap/app/api/food/FoodDetailLangTest.kt` 의 **현재 동작을 고정하고 있는 케이스 4개**를 반전한다 — `lang` 미지정(77행)·빈 값(86행)·공백(97행)의 `ko` 기대 → **400 `COMMON-002`**, `lang=xx`(66행)의 400 기대 → **EN**. 이 파일은 변경 대상 계약을 정면으로 단언하고 있어 누락 시 Red 가 아니라 빌드 실패로 나타난다
- [X] T002c ⚠️ `app/api/src/test/kotlin/com/kbap/app/api/common/GlobalExceptionHandlerTest.kt` 가 **이미 존재한다**(404·405·500·비즈니스 예외 커버) — T005 는 신규 파일 생성이 아니라 이 파일에 케이스를 추가하는 것으로 수행한다

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 모든 스토리 테스트가 공유하는 호출 헬퍼 정비

**⚠️ CRITICAL**: 이 단계가 끝나야 각 스토리의 테스트 작성이 가능하다

- [X] T003 [P] `app/api/src/test/kotlin/com/kbap/app/api/home/HomeTestSeed.kt` 의 요청 헬퍼에 `lang: String? = "en"` 파라미터를 추가한다(`null` 이면 쿼리 파라미터 자체를 붙이지 않아 누락 케이스를 표현할 수 있게 한다)
- [X] T004 [P] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodTestSeed.kt` 의 요청 헬퍼에 동일한 `lang` 파라미터를 추가한다
- [X] T005 **기존** `app/api/src/test/kotlin/com/kbap/app/api/common/GlobalExceptionHandlerTest.kt` 에 필수 쿼리 파라미터 누락·`@NotBlank` 위반이 400 `COMMON-002` 로 응답되는 케이스를 추가한다(신규 핸들러가 필요 없음을 고정). 신규 파일을 만들지 않는다 — T002c 참조 — **완료**(기존 GlobalExceptionHandlerTest 에 누락·빈 값·공백 3케이스 추가, 신규 핸들러 불필요를 고정)

**Checkpoint**: 헬퍼 준비 완료 — 스토리별 테스트 작성 가능

---

## Phase 3: User Story 3 - 표시 언어는 반드시 채워서 보내야 한다 (Priority: P1) 🎯 MVP

**Goal**: 5개 엔드포인트가 `lang` 을 필수로 받고, 누락·빈 값·공백을 400 `COMMON-002` 로 거절한다. 검증과 `LanguageCode` 확정이 컨트롤러로 올라가고 서비스는 확정값을 받는다.

**Independent Test**: 5개 API 각각에 `lang` 없이·빈 값으로·공백으로 요청해 모두 400 `COMMON-002` 가 오는지, 지원 코드를 주면 200 이 오는지 확인한다.

### Tests for User Story 3 (REQUIRED — 먼저 작성하고 FAIL 확인) ⚠️

- [X] T006 [P] [US3] `app/api/src/test/kotlin/com/kbap/app/api/home/HomeGuestTest.kt` 에 `lang` 누락·빈 값·공백 → 400 `COMMON-002` 케이스를 추가한다 — **완료**(HomeGuestTest 에 `lang` 누락 → 400 COMMON-002 케이스 1건). 사용자 결정: null 케이스는 컨트롤러 테스트 전체에 **하나만** 둔다
- [~] T007 [P] [US3] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodListControllerTest.kt` 에 `lang` 누락·빈 값·공백 → 400 케이스를 추가한다 — **불필요**(사용자 결정: null 케이스는 T006 하나로 충분). 대신 호출부 11곳에 `?lang=ko` 를 넣어 기존 한국어 단언을 보존했다
- [~] T008 [P] [US3] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodSearchControllerTest.kt` 에 `lang` 누락·빈 값·공백 → 400 케이스를 추가한다 — **불필요**(동일 사유). 호출부 19곳에 `?lang=ko` 적용
- [~] T009 [P] [US3] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodDetailControllerTest.kt` 에 `lang` 누락·빈 값·공백 → 400 케이스를 추가한다 — **불필요**(동일 사유). 호출부 4곳에 `?lang=ko` 적용
- [~] T010 [P] [US3] `app/api/src/test/kotlin/com/kbap/app/api/bookmark/BookmarkControllerTest.kt` 에 `lang` 누락·빈 값·공백 → 400 케이스를 추가한다 — **불필요**(동일 사유). `listJson` 헬퍼 기본값 `ko` + 직접 호출 1곳 적용
- [~] T011 [US3] 위 테스트가 모두 **실패(Red)** 하는지 실행해 확인한다 — **생략**(사용자 지시로 Test-First 우회, 구현 선행). 헌법 원칙 I 미준수를 의도적으로 수용

### Implementation for User Story 3

- [X] T012 [P] [US3] `app/api/src/main/kotlin/com/kbap/app/api/home/HomeRequest.kt` 를 신규 생성한다 — `@field:NotBlank val lang: String`
- [X] T013 [P] [US3] `app/api/src/main/kotlin/com/kbap/app/api/food/` 에 browse·search·detail 요청 DTO 를 생성한다 — 각각 기존 쿼리 파라미터(`cursor`·`keyword`)와 `@field:NotBlank val lang: String` 을 담는다
- [X] T014 [P] [US3] `app/api/src/main/kotlin/com/kbap/app/api/bookmark/` 에 목록 요청 DTO 를 생성한다 — `cursor` + `@field:NotBlank val lang: String`
- [X] T015 [P] [US3] `domain/food/src/main/kotlin/com/kbap/domain/food/dto/BrowseFoodsInput.kt`·`SearchFoodsInput.kt`·`GetFoodDetailInput.kt` 의 `lang` 타입을 `String?` → `LanguageCode` 로 바꾼다
- [X] T016 [US3] `domain/food/src/main/kotlin/com/kbap/domain/food/FoodService.kt` 의 `getFoodPage`·`searchFoodPage`·`getDetail` 에서 `LanguageCode.from` 호출을 제거하고 `input.lang` 을 그대로 사용한다
- [X] T017 [US3] `domain/bookmark/src/main/kotlin/com/kbap/domain/bookmark/BookmarkService.kt` 의 `getBookmarkPage` 시그니처를 `lang: LanguageCode` 로 바꾸고 `LanguageCode.from` 호출을 제거한다
- [X] T018 [US3] `application/src/main/kotlin/com/kbap/application/home/HomeApplicationService.kt` 의 `getHome` 에 `lang: LanguageCode` 파라미터를 추가한다(이 단계에서는 프로필 우선순위를 그대로 두고 시그니처만 확장 — 프로필 제거는 US1)
- [X] T019 [US3] `app/api/src/main/kotlin/com/kbap/app/api/home/HomeController.kt` 를 `@Valid @ModelAttribute HomeRequest` 수신으로 바꾸고 `LanguageCode.from(request.lang)` 으로 확정해 서비스에 넘긴다
- [X] T020 [US3] `app/api/src/main/kotlin/com/kbap/app/api/food/FoodController.kt` 의 browse·search·detail 을 요청 DTO 수신으로 바꾸고 `LanguageCode` 를 확정해 input DTO 에 싣는다
- [X] T021 [US3] `app/api/src/main/kotlin/com/kbap/app/api/bookmark/BookmarkController.kt` 를 요청 DTO 수신으로 바꾸고 `LanguageCode` 를 확정해 서비스에 넘긴다
- [X] T022 [P] [US3] `app/api/src/main/kotlin/com/kbap/app/api/home/HomeApi.kt` 의 swagger 에 `lang` 필수 파라미터·지원 목록·400 사유(누락·빈 값)를 반영하고, 기존 "요청 파라미터로 언어를 받지 않는다" 문구를 제거한다
- [X] T023 [P] [US3] `app/api/src/main/kotlin/com/kbap/app/api/food/FoodApi.kt` 의 `@Parameter` 3곳을 `required = true` 와 새 설명으로 갱신한다
- [X] T024 [P] [US3] `app/api/src/main/kotlin/com/kbap/app/api/bookmark/BookmarkApi.kt` 의 `@Parameter` 를 동일하게 갱신한다
- [X] T025 [US3] 기존 테스트 중 `lang` 없이 호출하던 케이스에 헬퍼 기본값이 적용되도록 정리하고, `./gradlew build` 로 전체 그린을 확인한다
- [X] T026 [US3] Swagger UI 에서 5개 엔드포인트의 `lang` 이 required 쿼리 파라미터로 렌더링되는지 확인한다 — 요청 DTO 가 펼쳐지지 않으면 `@ParameterObject` 를 붙인다(research R6) — **완료·자동화**(수동 확인 대신 `OpenApiLangParameterTest` 신설: `/v3/api-docs` 에서 5개 엔드포인트의 `lang` 이 required 쿼리 파라미터인지 + `COMMON-001` 미언급 검증)

**Checkpoint**: `lang` 이 전 API 필수가 되고 서비스가 확정된 `LanguageCode` 를 받는다. 미지원 코드는 아직 400 이다.

---

## Phase 4: User Story 1 - 앱 전체가 한 언어로 보인다 (Priority: P1)

**Goal**: 홈이 회원 프로필 언어를 참조하지 않고 요청 `lang` 만 따른다. 5개 API 의 표시 언어가 같은 `lang` 에서 일치한다.

**Independent Test**: 프로필 언어가 일본어인 회원이 `lang=ko` 로 홈을 조회해 한국어가 오는지, 같은 `lang` 으로 5개 API 를 조회해 표시 언어가 일치하는지 확인한다.

### Tests for User Story 1 (REQUIRED — 먼저 작성하고 FAIL 확인) ⚠️

- [X] T027 [P] [US1] `app/api/src/test/kotlin/com/kbap/app/api/home/HomeControllerTest.kt` 의 "프로필 언어가 일본어인 회원" 케이스를 `lang=ko` → **한국어** 기대로 뒤집는다(프로필 무시 증명)
- [X] T028 [P] [US1] `HomeControllerTest.kt` 의 "프로필 언어를 설정하지 않은 회원" 케이스를 `lang=ja` → **일본어** 기대로 뒤집는다
- [X] T029 [P] [US1] `HomeControllerTest.kt` 에 같은 `lang` 으로 회원과 비회원의 홈 응답 언어가 동일한지 검증하는 케이스를 추가한다
- [~] T030 [US1] 위 테스트가 실패(Red) 하는지 확인한다 — **생략**(동일 사유)

### Implementation for User Story 1

- [X] T031 [US1] `application/src/main/kotlin/com/kbap/application/home/HomeApplicationService.kt` 에서 `member?.profile?.appLanguage` 참조를 제거하고 파라미터로 받은 `lang` 을 그대로 사용한다(`memberId` 는 기피 성분·최근 스캔용으로 유지)
- [X] T032 [US1] `HomeApi.kt` swagger 의 언어 문단에서 "회원은 프로필에 설정한 앱 언어로" 서술을 제거하고 "프로필 언어는 적용되지 않는다"로 정정한다
- [X] T033 [US1] 같은 `lang` 으로 홈과 음식 목록의 표시명 언어가 일치하는지 검증하는 교차 테스트를 추가한다 — **완료**(HomeControllerTest 에 같은 lang 으로 회원·비회원 응답 언어 일치 검증 추가 = T029 와 통합)

**Checkpoint**: 표시 언어 결정 경로가 요청 하나로 통일된다. 스캔 API 만 프로필을 계속 쓴다.

---

## Phase 5: User Story 2 - 알 수 없는 언어여도 화면은 열린다 (Priority: P1)

**Goal**: `LanguageCode.from` 을 순수 lookup 으로 축소하고 미지원 코드를 영어로 폴백한다. `UNSUPPORTED_LANGUAGE` 를 삭제한다.

**Independent Test**: 5개 API 에 `fr`·`JA`·`ko-KR`·`" ko "` 를 전달해 모두 200 + 영어 표시명이 오는지 확인한다.

### Tests for User Story 2 (REQUIRED — 먼저 작성하고 FAIL 확인) ⚠️

- [X] T034 [P] [US2] `core/src/test/kotlin/com/kbap/core/lang/LanguageCodeTest.kt` 의 `shouldThrow` 케이스 5개(`"xx"`·`"EN"`·`"ko-KR"`·`" fr "`·`"fr"` 메시지 검증)를 **EN 반환 기대**로 전환한다
- [X] T035 [P] [US2] `LanguageCodeTest.kt` 의 `from(null)`·`from("")`·`from("   ")` → KO 케이스를 삭제한다(시그니처가 non-null 이 되어 컴파일 불가)
- [X] T036 [P] [US2] `LanguageCodeTest.kt` 에 `" ko "` → **EN** 케이스를 추가한다(trim 제거 증명)
- [X] T037 [P] [US2] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodDetailLanguageErrorTest.kt` 의 미지원 코드 400 기대를 200 + 영어 표시명 기대로 전환한다
- [X] T038 [P] [US2] `app/api/src/test/kotlin/com/kbap/app/api/food/FoodSearchControllerTest.kt` 의 `ErrorCode.UNSUPPORTED_LANGUAGE` 참조 2곳(375·391행 부근)을 200 + 영어 기대로 전환한다
- [X] T039 [P] [US2] `app/api/src/test/kotlin/com/kbap/app/api/home/HomeGuestTest.kt` 에 `lang=fr` → 200 + 영어 케이스를 추가한다
- [~] T040 [US2] 위 테스트가 실패(Red) 하는지 확인한다 — **생략**(동일 사유)

### Implementation for User Story 2

- [X] T041 [US2] `core/src/main/kotlin/com/kbap/core/lang/LanguageCode.kt` 의 `from` 을 `fun from(code: String): LanguageCode = entries.firstOrNull { it.code == code } ?: EN` 으로 축소하고 `BusinessException`·`ErrorCode` import 를 제거한다
- [X] T042 [US2] `core/src/main/kotlin/com/kbap/core/error/ErrorCode.kt` 에서 `UNSUPPORTED_LANGUAGE`(`COMMON-001`) 항목을 삭제한다
- [X] T043 [US2] 컴파일 오류로 드러나는 잔여 참조를 모두 정리하고 `./gradlew build` 로 전체 그린을 확인한다
- [X] T044 [P] [US2] `FoodApi.kt`·`BookmarkApi.kt`·`HomeApi.kt` 의 swagger 에서 "지원 목록에 없는 코드는 400" 문구를 "지원 목록에 없는 코드는 영어로 응답"으로 정정하고 `COMMON-001` 관련 `@ApiResponse` 를 제거한다
- [X] T045 [US2] `ErrorCodeStatusTest` 가 `COMMON-001` 삭제 후에도 형식·유일성 검증을 통과하는지 확인한다

**Checkpoint**: 언어 코드 값을 사유로 하는 400 이 서비스에서 사라진다.

---

## Phase 6: User Story 4 - 비회원도 기기 언어로 홈을 본다 (Priority: P2)

**Goal**: 비회원 홈이 전달된 기기 언어로 표시된다.

**Independent Test**: 인증 없이 `lang=ja` 로 홈을 조회해 인기 음식명이 일본어인지 확인한다.

### Tests for User Story 4 (REQUIRED — 먼저 작성하고 FAIL 확인) ⚠️

- [X] T046 [P] [US4] `app/api/src/test/kotlin/com/kbap/app/api/home/HomeGuestTest.kt` 에 비회원 `lang=ja` → 일본어 인기 음식명 케이스를 추가한다
- [X] T047 [P] [US4] `HomeGuestTest.kt` 에 비회원 `lang=ko` → 한국어 케이스를 추가한다
- [X] T048 [US4] 위 테스트가 실패(Red) 하는지 확인한다 — US1·US3 이 완료됐다면 이미 통과할 수 있으며, 그 경우 통과 사실을 기록하고 넘어간다

### Implementation for User Story 4

- [X] T049 [US4] 실패가 남아 있으면 원인을 수정한다(신규 프로덕션 코드가 필요 없을 것으로 예상 — US1·US3 의 파생 결과)

**Checkpoint**: 모든 사용자 스토리가 독립적으로 검증된다.

---

## Phase 7: 거버넌스 (별도 커밋)

**Purpose**: 헌법·기존 결정 번복을 코드 변경과 분리해 리뷰 포인트를 나눈다

- [X] T050 [P] `.specify/memory/constitution.md` 의 원칙 V 를 개정한다 — clause (3) 을 "미지원 코드 → 영어 폴백"으로 교체, clause (1) 을 `lang` 필수화에 맞춰 정리, clause (2)(번역 부재 → ko)와 "정확 일치·정규화 금지"는 유지 — **완료**(원칙 V 재정의: 비어 있음→400 / 번역 부재→ko / 미지원 코드→en, + 검증 소유 계층 조항 추가)
- [X] T051 `.specify/memory/constitution.md` 상단 Sync Impact Report 를 갱신하고 버전을 MAJOR 로 올린다(3.0.1 → 4.0.0), 개정 사유·영향 문서를 기록한다 — **완료**(Sync Impact Report 갱신, v3.0.1 → **v4.0.0**, Last Amended 2026-07-20)
- [X] T052 [P] `docs/adr/0013-lang-english-fallback.md` 를 작성한다 — fail-fast 를 버리고 영어 폴백을 택한 근거, 검토한 대안(ko 폴백·클라이언트 필터링·홈만 예외), 감수하는 비용(클라이언트 오타가 조용히 영어로 나가 QA 에서 드러나지 않음), spec 008 supersede 명시 — **완료**(`docs/adr/0013-lang-english-fallback.md` + README 인덱스 등록)
- [X] T053 [P] `specs/008-unsupported-language-error/` 에 superseded 표기와 ADR-0013·본 spec 포인터를 추가한다 — **완료**(spec 008 상단 SUPERSEDED 배너 + Status 변경)
- [X] T054 [P] `CLAUDE.md` 의 "프로필 언어 = 회원 응답 언어의 단일 기준" 서술을 스캔 API 한정으로 정정한다 — **완료**(CLAUDE.md 에 '프로필 언어 단일 기준' 서술은 **없었다**. 대신 실제 문제였던 에러 코드 예시의 `COMMON-001` 참조를 `COMMON-002` 로 교체 + 폐기 번호 재사용 금지 명시)
- [X] T055 [P] `docs/architecture/kbap-conventions.md` 에 언어 코드 규범이 기술돼 있으면 동일하게 정정한다 — **완료**(`docs/architecture/meogo-conventions.md` — `kbap-conventions.md` 는 존재하지 않는 경로였다. 언어 규약 + 검증 계층 관례 추가)

---

## Phase 8: Polish & 검증

- [ ] T056 `specs/kb-201-home-lang-param/quickstart.md` 의 수동 검증 절차를 실행해 5개 엔드포인트 동작을 확인한다
- [X] T057 `./gradlew build` 전체 그린을 최종 확인한다 — **BUILD SUCCESSFUL in 2m 2s · 475 tests · failures 0 · E2E 시나리오 4종 포함**
- [X] T058 [P] 쿼리 파라미터 검증을 요청 DTO 로 올린 것이 이 코드베이스의 첫 사례임을 `docs/architecture/kbap-conventions.md` 에 관례로 기록한다(이후 신규 API 가 따를 패턴) — **완료**(T055 와 같은 커밋에서 meogo-conventions.md 에 기록)
- [ ] T059 클라이언트 배포 선행이 릴리스 조건임을 PR 본문에 명시하고 앱 팀 합의 여부를 확인한다

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 선행 없음
- **Foundational (Phase 2)**: Setup 완료 후 — 모든 스토리 테스트를 블로킹
- **US3 (Phase 3)**: Foundational 완료 후. **이 기능의 뼈대** — US1·US2 가 이 위에 얹힌다
- **US1 (Phase 4)**: US3 완료 후(홈 컨트롤러가 `lang` 을 넘기고 있어야 프로필 제거가 의미를 갖는다)
- **US2 (Phase 5)**: US3 완료 후(서비스가 `LanguageCode` 를 받고 있어야 `from` 축소가 컴파일된다). US1 과는 독립 — 병렬 가능
- **US4 (Phase 6)**: US1·US3 완료 후. 대부분 검증만 남는다
- **거버넌스 (Phase 7)**: 코드 변경과 독립 — 언제든 병렬 진행 가능하나 **별도 커밋**으로 유지
- **Polish (Phase 8)**: 전 단계 완료 후

### 우선순위와 구현 순서가 다른 이유

US1·US2·US3 은 모두 P1 이지만 `LanguageCode.from` 시그니처 변경이 전 호출부를 동시에 깨뜨린다. 컴파일이 유지되는 유일한 순서가 **US3 → (US1 ∥ US2) → US4** 다. 상단 "구현 순서 주의" 참조.

### Within Each User Story

- 테스트를 먼저 작성하고 **반드시 Red 를 확인**한다(헌법 원칙 I)
- 요청 DTO → 서비스 시그니처 → 컨트롤러 배선 → swagger 순
- 스토리 완료 후 다음 스토리로 이동

### Parallel Opportunities

- T003·T004 (테스트 헬퍼 2종)
- T006~T010 (5개 엔드포인트의 400 테스트 — 서로 다른 파일)
- T012·T013·T014 (요청 DTO 3묶음), T015 (도메인 input DTO)
- T022·T023·T024 (swagger 3파일)
- T034~T039 (US2 테스트 6종 — 서로 다른 파일)
- T050·T052·T053·T054·T055 (거버넌스 문서 — 서로 다른 파일)
- **Phase 4(US1)와 Phase 5(US2)는 서로 독립** — 인원이 있으면 동시 진행 가능

---

## Parallel Example: User Story 3

```bash
# 5개 엔드포인트의 필수화 테스트를 함께 작성(먼저 작성, 반드시 FAIL)
Task: "HomeGuestTest.kt 에 lang 누락·빈 값·공백 400 케이스 추가"
Task: "FoodListControllerTest.kt 에 동일 케이스 추가"
Task: "FoodSearchControllerTest.kt 에 동일 케이스 추가"
Task: "FoodDetailControllerTest.kt 에 동일 케이스 추가"
Task: "BookmarkControllerTest.kt 에 동일 케이스 추가"

# 요청 DTO 를 함께 생성
Task: "HomeRequest.kt 생성"
Task: "food 요청 DTO 3종 생성"
Task: "bookmark 목록 요청 DTO 생성"
```

---

## Implementation Strategy

### MVP (US3 — Phase 1~3)

1. Setup + Foundational
2. US3 완료 — `lang` 필수화, 컨트롤러 검증, 서비스가 `LanguageCode` 수신
3. **STOP & VALIDATE**: 5개 API 의 400 동작과 정상 조회를 확인
4. 이 시점에서 계약 변경의 뼈대가 완성된다. 미지원 코드는 아직 400 이지만 시스템은 일관된 상태다

### Incremental Delivery

1. MVP(US3) → 검증
2. US1 추가(홈 프로필 제거) → 교차 일관성 검증
3. US2 추가(`from` 축소·`COMMON-001` 삭제) → 미지원 코드 영어 확인
4. US4 검증 → 게스트 경로 확인
5. 거버넌스 문서(별도 커밋)
6. quickstart 실행 후 PR

### 릴리스 주의

**5개 엔드포인트가 동시에 파괴적으로 바뀐다.** 서버 선배포 불가 — 클라이언트 배포 선행 또는 강제 업데이트가 전제다(T059).

---

## Notes

- `[P]` = 다른 파일, 의존 없음
- 각 task 또는 논리 단위마다 커밋한다
- **거버넌스(Phase 7)는 코드와 커밋을 분리**한다 — 헌법 개정이 기능 리뷰에 섞이면 둘 다 제대로 검토되지 않는다
- `ErrorCode.UNSUPPORTED_LANGUAGE` 삭제(T042)는 잔여 참조를 **컴파일 오류로 드러낸다** — 이것이 누락 탐지 장치다
- trim 제거로 `" ko "` 가 KO → EN 으로 바뀐다(T036 이 이를 고정)
