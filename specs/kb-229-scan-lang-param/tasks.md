# Tasks: 프로필 언어 설정 제거 및 메뉴판 스캔 언어 요청 파라미터 전환

**Input**: Design documents from `/specs/kb-229-scan-lang-param/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scan-api.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 태스크는 실패 테스트 작성·Red 확인 후 구현한다. 테스트는 Kotest BehaviorSpec(given/when/then 한국어).

**Organization**: 유저 스토리별 그룹화 — US1(스캔 lang 파라미터)이 프로필 언어의 마지막 사용처를 끊고, US2(프로필 appLanguage 제거)가 그 뒤를 정리한다.

## Format: `[ID] [P?] [Story] Description`

## Phase 1: Setup

없음 — 신규 의존성·스키마·인프라 변경이 없는 기존 모듈 내 수정이다(Flyway 마이그레이션 0건).

---

## Phase 2: Foundational (Blocking Prerequisites)

없음 — 두 스토리가 기존 인프라(GlobalExceptionHandler·LanguageCode·Testcontainers)를 그대로 쓴다. US2 는 US1 완료에만 의존한다(스토리 간 순서 의존이지 공통 선행 작업이 아님).

---

## Phase 3: User Story 1 - 스캔 결과를 요청한 언어로 받기 (Priority: P1) 🎯 MVP

**Goal**: `POST /api/v1/scans?lang={code}` — lang 필수 쿼리 파라미터로 번역 언어를 결정하고, 회원 프로필 `appLanguage` 의존을 제거한다.

**Independent Test**: 프로필 설정과 무관하게 `lang=en`/`lang=ja` 로 각각 스캔해 응답 `items[].name` 언어가 요청을 따르는지, `lang=fr`(미지원) → 200 영어, lang 누락·빈 값 → 400 인지 확인.

### Tests for User Story 1 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [X] T001 [US1] `app/api/src/test/kotlin/com/kbap/app/api/scan/ScanControllerTest.kt` 에 lang 시나리오 실패 테스트 추가 — (1) `param("lang","en")` 스캔 시 매칭 메뉴 `name` 이 영어 번역, (2) `lang=ja` 시 일본어 번역, (3) 미지원 코드 `lang=fr` → 200 + 영어 번역, (4) lang 누락 → 400, (5) `lang=" "` 공백 → 400. 기존 프로필 언어 기반 시나리오는 요청 lang 기반으로 수정. **Red 확인**(컴파일 실패도 Red — `scanMenuBoardImage` 시그니처 변경 전이므로 MockMvc 400/기존 동작으로 실패)

### Implementation for User Story 1

- [X] T002 [P] [US1] `app/api/src/main/kotlin/com/kbap/app/api/scan/ScanLangRequest.kt` 신규 — `lang: String` + `@field:NotBlank` + swagger `@Schema`(지원 코드 목록·en 폴백 서술, `HomeRequest` 와 동일 문구)
- [X] T003 [US1] `domain/scan/src/main/kotlin/com/kbap/domain/scan/ScanService.kt` — `scanMenuBoardImage` 에 `lang: LanguageCode` 파라미터 추가, `profile.appLanguage ?: KO` 조회 삭제. `memberService.getMember(memberId)` 존재 확인 호출은 유지(비전 비용 가드 — research R2)
- [X] T004 [US1] `app/api/src/main/kotlin/com/kbap/app/api/scan/ScanController.kt` — `@Valid @ModelAttribute langRequest: ScanLangRequest` 파라미터 추가, `LanguageCode.from(langRequest.lang)` 확정 후 서비스 호출 (**Green 확인** — T001 전 시나리오 통과)
- [X] T005 [US1] `app/api/src/main/kotlin/com/kbap/app/api/scan/ScanApi.kt` — 인터페이스 시그니처 동기화 + swagger 문서 애너테이션(lang 파라미터 명세). `:domain:scan:test`·`:app:api:test` 통과 확인(Refactor)

**Checkpoint**: 스캔 API 가 프로필과 무관하게 요청 lang 으로 응답 — US1 단독 배포 가능(프로필 appLanguage 는 아직 존재하나 아무도 읽지 않음)

---

## Phase 4: User Story 2 - 프로필에서 언어 설정이 사라진다 (Priority: P2)

**Goal**: `appLanguage` 를 회원 프로필 전면에서 제거 — 도메인 모델·JSON 직렬화·온보딩/수정 입력·조회 응답·swagger. 기존 row 의 legacy 키와 구버전 앱 요청은 무시.

**Independent Test**: appLanguage 없이 온보딩 성공, 내 프로필 조회 응답에 appLanguage 키 부재, appLanguage 를 포함한 구버전 요청도 성공, legacy JSON(`"appLanguage":"ko"` 키 보유) row 조회 성공.

### Tests for User Story 2 (REQUIRED — Test-First: write these tests FIRST, ensure they FAIL) ⚠️

- [ ] T006 [P] [US2] `domain/member/src/test/kotlin/com/kbap/domain/member/model/MemberProfileTest.kt`·`MemberTest.kt` — appLanguage 관련 기존 테스트 제거·수정 + **legacy JSON 관용 회귀 테스트 신규**: `"appLanguage"` 키가 포함된 JSON 을 `MemberProfileJson` 으로 역직렬화해도 예외 없이 읽히는지(Hibernate 매퍼와 동일한 기본 ObjectMapper 로 검증 — research R3). **Red 확인**
- [ ] T007 [P] [US2] `app/api/src/test/kotlin/com/kbap/app/api/member/MemberControllerTest.kt` — (1) appLanguage 없는 온보딩 성공, (2) appLanguage 를 포함해 보내도 무시하고 성공(구버전 호환 — FR-007), (3) 프로필 수정 동일, (4) 내 프로필 조회 응답 JSON 에 `appLanguage` 키 부재. **Red 확인**

### Implementation for User Story 2

- [ ] T008 [US2] `domain/member/src/main/kotlin/com/kbap/domain/member/model/MemberProfileJson.kt` — `appLanguage` 필드 제거 + 클래스에 `@JsonIgnoreProperties(ignoreUnknown = true)` 명시(research R3)
- [ ] T009 [US2] `domain/member/src/main/kotlin/com/kbap/domain/member/model/MemberProfile.kt`·`Member.kt` — `appLanguage` 필드·`validatedLanguage` 헬퍼·`of`/`updatedWith`/`completeOnboarding`/`updateProfile` 파라미터 제거
- [ ] T010 [US2] `domain/member/src/main/kotlin/com/kbap/domain/member/` — `dto/MemberProfileInput.kt`·`dto/ProfileUpdateInput.kt`·`dto/MyProfileResult.kt` 에서 `appLanguage` 제거, `MemberService.kt` 전달 인자 정리 (**T006 Green 확인**)
- [ ] T011 [US2] `app/api/src/main/kotlin/com/kbap/app/api/member/` — `OnboardingRequest.kt`·`ProfileUpdateRequest.kt`·`MyProfileResponse.kt` 에서 `appLanguage` 제거, `MemberApi.kt` swagger 예시 갱신 (**T007 Green 확인**)
- [ ] T012 [US2] 테스트 시드·드라이버 정리 — `app/api/src/test/kotlin/com/kbap/app/api/{home/HomeTestSeed.kt, food/FoodTestSeed.kt, auth/AuthControllerTest.kt, scenario/ScenarioApiDriver.kt}` 의 appLanguage 잔재 제거, `:domain:member:test`·`:app:api:test` 통과 확인(Refactor)

**Checkpoint**: 서버에 언어 저장·노출 경로 0개(SC-004) — 두 스토리 모두 독립 검증 완료

---

## Phase 5: Polish & Cross-Cutting Concerns

- [ ] T013 `./gradlew build` 전체 통과(ArchUnit `ModuleBoundaryTest` 포함) + quickstart.md 수동 확인 항목 점검(Swagger UI 에서 scans lang 파라미터 노출·member 예시 appLanguage 소멸)

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup·Foundational: 없음 — 바로 US1 착수 가능
- **US1 (Phase 3) → US2 (Phase 4)**: US2 는 US1 완료에 의존한다 — `ScanService` 가 `profile.appLanguage` 를 읽는 동안 필드를 지우면 컴파일이 깨진다(제거 순서: 사용처 끊기 → 필드 제거)
- Polish (Phase 5): US1·US2 완료 후

### Within Each User Story

- 실패 테스트 작성·Red 확인 → 최소 구현(Green) → Refactor (Constitution I)
- US1: T001 → (T002 ∥ T003, 서로 다른 파일) → T004 → T005
- US2: (T006 ∥ T007) → T008 → T009 → T010 → T011 → T012

### Parallel Opportunities

- T002 ∥ T003 (신규 DTO vs 도메인 서비스 — 다른 모듈)
- T006 ∥ T007 (도메인 테스트 vs web 테스트 — 다른 모듈)
- 스토리 간 병렬은 불가(US2 가 US1 에 순서 의존)

---

## Implementation Strategy

**MVP = US1 단독**: 스캔 API 가 lang 파라미터로 동작하면 사용자 가치(언어 일치)가 전달된다 — 프로필 필드는 죽은 채로 남지만 무해하므로 US1 만으로 배포 가능. US2 는 같은 브랜치에서 이어서 정리(둘이 합쳐 KB-229 완결). 태스크/논리 단위마다 커밋한다.

---

## Notes

- 시드-동기화 주의: 테스트 시드(`HomeTestSeed`·`FoodTestSeed`)와 `ScenarioApiDriver` 는 온보딩 요청 body 를 만들어 쓰므로 T011 계약 변경 시 T012 에서 반드시 함께 갱신 — 누락 시 컴파일이 아니라 요청 400 으로 조용히 깨질 수 있다
- Flyway 마이그레이션·`AvoidanceCatalogSeedSyncTest` 영향 없음(스키마·시드 불변)
