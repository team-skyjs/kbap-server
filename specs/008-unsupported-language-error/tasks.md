---
description: "Task list for 미지원 언어 코드 strict 검증 (이슈 #18)"
---

# Tasks: 미지원 언어 코드 요청 시 에러 응답 (LanguageCode strict 검증)

**Input**: Design documents from `/specs/008-unsupported-language-error/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/food-detail-language.md, quickstart.md

**Tests**: Test-First 은 **NON-NEGOTIABLE**(헌법 원칙 I). 각 단계의 테스트를 구현 전에 작성하고 **Red 를 확인**한 뒤 Green 으로 넘어간다.

**Organization**: kernel strict 규칙은 두 스토리의 공통 선행이라 Foundational 로, 사용자 표면 동작(미지원 거절 US1 / 미지정 기본 US2)은 스토리별 단계로 둔다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1 / US2 (Setup·Foundational·Polish 는 라벨 없음)
- 모든 task 는 정확한 파일 경로를 포함한다

## Path Conventions

모듈러 모놀리스(기존 구조 재사용): `core/kernel/`, `application/client/`, `app/api/` 각 모듈의 `src/main/kotlin`·`src/test/kotlin`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: 신규 의존·모듈 없음(기존 재사용). 변경 전 기준선만 확인한다.

- [X] T001 변경 전 baseline 그린 확인 — `./gradlew :core:kernel:test :application:client:test :app:api:test` 실행해 현재 통과 상태를 기록한다(회귀 판단 기준).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: `LanguageCode` 의 strict 해석 규칙과 예외 — US1·US2 모두 이 규칙에 의존한다.

**⚠️ CRITICAL**: 이 단계 완료 전에는 어떤 스토리도 시작할 수 없다.

- [X] T002 [P] **[Red]** `LanguageCodeTest` 를 strict 계약으로 갱신 — `core/kernel/src/test/kotlin/com/meogo/core/kernel/lang/LanguageCodeTest.kt`. `from("xx")`·`from("EN")`·`from("ko-KR")` → `UnsupportedLanguageException`(`shouldThrow`), `from(null)`·`from("")`·`from("   ")` → `KO`, `from("ko")` 및 9개 대상 언어 정확 일치 → 각 코드, 예외 메시지에 지원 코드 10종(`ko, zh-Hans, en, ja, zh-Hant, vi, id, th, ru, es`) 포함을 검증. 기존 "미지원·EN → KO 폴백" assert 제거. **실행해 Red 확인**.
- [X] T003 `UnsupportedLanguageException` 신규 — `core/kernel/src/main/kotlin/com/meogo/core/kernel/lang/UnsupportedLanguageException.kt`. `IllegalArgumentException` 상속, 프로퍼티 `requestedCode: String`, 메시지는 입력 코드 + `LanguageCode.entries.joinToString(", ") { it.code }` 지원 목록을 포함(순수 Kotlin, Spring-free).
- [X] T004 `LanguageCode.from` 을 strict 규칙으로 교체 — `core/kernel/src/main/kotlin/com/meogo/core/kernel/lang/LanguageCode.kt`. 규칙: `code` null·빈·공백 → `KO`; `trim()` 후 `entries` 와 정확 일치 → 해당 코드; 그 외 → `throw UnsupportedLanguageException(...)`. **T002 를 실행해 Green 확인**.

**Checkpoint**: kernel 단위 그린 — strict 해석·예외·지원 목록 메시지 확립.

---

## Phase 3: User Story 1 - 미지원 언어 코드 거절 + 지원 목록 안내 (Priority: P1) 🎯 MVP

**Goal**: 값이 있으나 지원 목록에 없는 언어 코드로 음식 상세조회 시, 조용한 한국어 폴백 대신 **400 + `BaseResponse.fail`(지원 목록 안내)** 를 반환한다.

**Independent Test**: `GET /api/v1/foods/detail?menuName=된장찌개&lang=fr`(또는 `xx`·`EN`) → 400, `success=false`, `message` 에 지원 언어 10종 포함.

### Tests for User Story 1 (Test-First: 먼저 작성·FAIL 확인) ⚠️

- [X] T005 [P] [US1] **[Red]** `LanguageResolverTest` 를 strict 계약으로 갱신 — `application/client/src/test/kotlin/com/meogo/application/client/food/usecase/LanguageResolverTest.kt`. `resolve("xx")` → `UnsupportedLanguageException`(`shouldThrow`), `resolve(null)`·`resolve("")`·`resolve("   ")` → `KO`(유지), 지원 코드 → 해당 코드. 기존 "미지원 → KO 폴백" assert 제거.
- [X] T006 [P] [US1] **[Red]** `FoodDetailLanguageErrorTest` 신규(MockMvc) — `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailLanguageErrorTest.kt`. `@SpringBootTest`+`@AutoConfigureMockMvc`+`SpringExtension`, `FoodTestSeed.seedDoenjangStew`. `lang=fr`·`lang=xx`·`lang=EN` 각각 → `status().isBadRequest()`, `$.success=false`, `$.message` 에 지원 목록 10종(예: `zh-Hans`·`vi`·`es` 포함) 검증. **실행해 Red 확인**(현재는 200 폴백).
- [X] T007 [P] [US1] **[Red]** `FoodDetailLangTest` 의 `lang=xx` 케이스를 400 으로 갱신 — `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailLangTest.kt`. 기존 "`lang=xx` → 200 + 한국어 폴백" then 블록을 "`lang=xx` → `isBadRequest()` + `success=false` + 지원 목록 메시지" 로 교체(`lang=ja` → 일본어 200, `lang` 미지정 → 한국어 200 케이스는 유지). **실행해 Red 확인**.

### Implementation for User Story 1

- [X] T008 [US1] **[Green]** `GlobalExceptionHandler` 에 `UnsupportedLanguageException` 전용 핸들러 추가 — `app/api/src/main/kotlin/com/meogo/app/api/common/GlobalExceptionHandler.kt`. `@ExceptionHandler(UnsupportedLanguageException::class)` → `ResponseEntity.badRequest().body(BaseResponse.fail(e.message ?: "지원하지 않는 언어 코드입니다"))`. **T005·T006·T007 실행해 Green 확인**(T005 는 T004 이후 위임으로 통과).
- [X] T009 [US1] **[Refactor]** `FoodDetailApi` Swagger 문서 갱신 — `app/api/src/main/kotlin/com/meogo/app/api/food/FoodDetailApi.kt`. `lang` `@Parameter` 설명을 "미지정/빈/공백 시 ko, **지원 목록에 없는 코드는 400**" 으로 수정, 400 `@ApiResponse` 설명에 미지원 언어 코드 사유 추가.

**Checkpoint**: US1 완결 — 미지원 코드 400 + 지원 목록 안내가 독립 검증됨(MVP).

---

## Phase 4: User Story 2 - 언어 미지정 시 한국어 기본 유지 (Priority: P2)

**Goal**: 언어 미지정(null)·빈 문자열·공백 요청은 에러 없이 한국어로 응답한다(fail-fast 도입이 정상 흐름을 깨지 않음을 보장).

**Independent Test**: `GET /api/v1/foods/detail?menuName=된장찌개`(lang 생략) 및 `&lang=`(빈)·`&lang=%20%20%20`(공백) → 200, 한국어 음식명.

### Tests for User Story 2 (Test-First) ⚠️

- [X] T010 [US2] **[Red/회귀]** `FoodDetailLangTest` 에 미지정 기본 케이스 보강 — `app/api/src/test/kotlin/com/meogo/app/api/food/FoodDetailLangTest.kt`(T007 과 동일 파일이므로 **T007 이후 수행**). `lang` 생략 → 200 한국어(기존 유지), `lang=""`(빈) → 200 한국어, `lang="   "`(공백) → 200 한국어 then 블록 추가. **실행해 확인**(foundational 로 null·blank→KO 보장되어 대부분 즉시 Green — 회귀 가드).

### Implementation for User Story 2

- [X] T011 [US2] 미지정 기본 동작 재확인 — 프로덕션 변경 없음(kernel null·blank→KO 규칙이 담당). `./gradlew :application:client:test --tests "com.meogo.application.client.food.usecase.LanguageResolverTest"` 로 null·빈·공백 → KO 그린 재확인.

**Checkpoint**: US1·US2 모두 독립 동작 — 미지원 거절과 미지정 기본이 공존.

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: 전체 정합·문서 검증.

- [X] T012 [P] quickstart.md 검증 — `./gradlew build` 전체 그린 확인(전 모듈 컴파일+테스트).
- [X] T013 [P] spec 수용 기준 대비 테스트 매핑 점검 — spec.md SC-001~SC-004·FR-001~FR-008 이 T002/T006/T007/T010 테스트로 커버되는지 확인(누락 시 테스트 보강, 문서 변경 불요면 확인만).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup(Phase 1)**: 즉시 시작.
- **Foundational(Phase 2)**: Setup 후 — **모든 스토리를 블록**. T002(Red) → T003·T004(Green) 순서(T004 는 T003 의 예외 타입에 의존).
- **US1(Phase 3)**: Foundational 완료 후 시작 — MVP.
- **US2(Phase 4)**: Foundational 완료 후 시작. T010 은 T007 과 같은 파일이라 **T007 이후**.
- **Polish(Phase 5)**: US1·US2 완료 후.

### Within Each Story

- 테스트 먼저 작성·Red 확인 → 구현 Green → Refactor(헌법 원칙 I).
- US1: T005·T006·T007(Red) → T008(Green) → T009(Refactor).
- US2: T010(가드) → T011(확인).

### Parallel Opportunities

- Foundational: T002 는 단독 [P](테스트 파일). T003·T004 는 순차.
- US1 테스트: **T005·T006·T007 병렬 가능**([P] — 서로 다른 파일: application 테스트 / 신규 web 테스트 / 기존 web 테스트).
- Polish: T012·T013 병렬.
- ⚠️ 같은 파일 주의: T007·T010 은 `FoodDetailLangTest.kt` 공유 → 병렬 금지, 순차.

---

## Parallel Example: User Story 1 테스트

```bash
# US1 의 실패 테스트 3종을 병렬로 작성(서로 다른 파일):
Task: "LanguageResolverTest strict 갱신 (application/.../LanguageResolverTest.kt)"
Task: "FoodDetailLanguageErrorTest 신규 (app/api/.../food/FoodDetailLanguageErrorTest.kt)"
Task: "FoodDetailLangTest 의 lang=xx → 400 갱신 (app/api/.../food/FoodDetailLangTest.kt)"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Phase 1 Setup → 2. Phase 2 Foundational(kernel strict) → 3. Phase 3 US1.
4. **STOP & VALIDATE**: 미지원 코드 400 + 지원 목록 독립 검증.
5. 배포/데모 가능.

### Incremental Delivery

1. Foundational → 2. US1(미지원 거절, MVP) → 3. US2(미지정 기본 가드) → 4. Polish(전체 build).

---

## Notes

- Kotlin 소스 주석 금지(고정) — 테스트 포함 `.kt` 전체.
- 모든 테스트는 Kotest `BehaviorSpec`(given/when/then 한국어).
- 응답 규약 `ResponseEntity<BaseResponse<T>>`·경로 `/api/v1` 준수.
- 지원 목록 문자열은 `LanguageCode.entries` 단일 출처에서 생성(언어 추가 시 메시지 자동 동기화).
- 각 task/논리 단위마다 커밋.
- 헌법 원칙 V 는 v2.3.0 개정 완료 — 설계·헌법 정합.
- **추가 회귀 수정(리뷰 발견)**: `application/client/.../GetFoodDetailUseCaseTest.kt` 의 구정책 "미지원 lang=xx → ko 폴백" 단언 2건을 `shouldThrow<UnsupportedLanguageException>` 로 전환(정책 교체로 무효화된 스테일 테스트). 번역 부재→ko 폴백(지원 언어 en) 가드는 보존 — FR-007/SC-004 커버.
- **예외 계층 리팩터(사용자 지시)**: `UnsupportedLanguageException : IllegalArgumentException` → **도메인 예외 계층 + ErrorCode enum**으로 전환. `:core:kernel.error`(`ErrorCode` 계약 + `MeogoException` 루트) 신설, `kernel.lang`(`LanguageErrorCode` enum + `open class LanguageException(errorCode)`). **코드별 전용 예외 클래스 없이 throw 시 enum 전달**(`throw LanguageException(LanguageErrorCode.UNSUPPORTED_LANGUAGE)`). `GlobalExceptionHandler` 는 `MeogoException` 1개로 수렴 + `HttpStatus.resolve` 가드 + 로깅(4xx warn 스택제외 / 5xx error 스택포함). 상세: [[exception-hierarchy-pattern]] 메모리.
- **food 도메인 공통 적용(사용자 지시)**: `GetFoodDetailUseCase` 의 not-found `IllegalArgumentException("해당 음식 정보 없음")` 를 `:core:food` 의 `FoodErrorCode.NOT_FOUND` + `open class FoodException(errorCode)` 로 전환(`throw FoodException(FoodErrorCode.NOT_FOUND)`). **상태 400 유지**. 회귀: `GetFoodDetailUseCaseTest`(`shouldThrow<FoodException>`) 갱신, `FoodDetailErrorTest`·Swagger 는 400 그대로. #18 범위 밖의 도메인 확장이나 같은 브랜치에서 함께 처리.
- **테스트 강화(사용자 지시)**: `FoodDetailLanguageErrorTest` 의 `lang=fr` 케이스가 `$.message` 에 지원 언어 **10종 전체**(ko·zh-Hans·en·ja·zh-Hant·vi·id·th·ru·es) 포함을 검증 — 계약 회귀 방어.
