# Tasks: 회원 알림 설정 API — 활동 푸시·K-Bap 소식 두 그룹 재편

**Input**: Design documents from `/specs/kb-466-notification-settings-api/`

**Prerequisites**: plan.md, spec.md, research.md(R1~R13), data-model.md, contracts/notification-settings-api.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (헌법 원칙 I). 각 스토리는 실패하는 테스트를 먼저 쓰고(Red) 구현으로 통과시킨다(Green). 모든 테스트는 Kotest `BehaviorSpec`, `given/when/then` 한국어. api 통합은 `@IntegrationTest` + `@Autowired MockMvc`, common 리포지토리는 기존 `CommonTestApp` 컨텍스트.

**Organization**: 스토리별 단계. Foundational 은 저장 구조 교체와 KB-465 계약 이전이라 어느 스토리보다 먼저 끝나야 한다(ddl validate 를 통과해야 컨텍스트가 뜬다).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·의존 없음 → 병렬 가능
- **[Story]**: US1(조회)·US2(토글 수정)·US3(동의 켜기/끄기)
- 경로는 워크트리 루트(`.claude/worktrees/kb-466-notification-settings-api/`) 기준 상대 경로

## Path Conventions

- 도메인: `common/src/main/kotlin/com/kbap/common/domain/notification/`
- 에러 코드: `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt`
- API 기능: `api/src/main/kotlin/com/kbap/api/notification/`
- 조립: `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt`
- 마이그레이션: `api/src/main/resources/db/migration/`
- 테스트: `common/src/test/kotlin/com/kbap/common/domain/notification/`, `api/src/test/kotlin/com/kbap/api/notification/`, `api/src/test/kotlin/com/kbap/api/auth/`

---

## Phase 1: Setup

**Purpose**: 신규 프로젝트 초기화 없음 — 기존 `api.notification` 패키지(KB-465)에 얹는다. 마이그레이션 파일명만 확정한다.

- [x] T001 마이그레이션 파일 `api/src/main/resources/db/migration/V2026.09.07.21.07.25__notification_setting_two_groups.sql` 생성 — data-model.md §6 의 SQL 그대로(`notification_setting` DROP `helpful`·`review_reminder` + ADD `activity`·`meal_time` BOOLEAN NOT NULL DEFAULT TRUE; `notification_consent` ADD `consent_type VARCHAR(30) NOT NULL DEFAULT 'MARKETING_RECEIVE'` 후 `ALTER COLUMN consent_type DROP DEFAULT`). 파일 상단 SQL 주석에 KB-466·두 그룹 재편 사유 한 줄. KB-464 파일은 손대지 않는다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: 엔티티↔스키마 정합(ddl validate)과 동의 원장 종류 축, 그리고 KB-465 게스트 계약 이전. 이게 끝나야 어떤 스토리 테스트도 컨텍스트를 띄울 수 있다.

**⚠️ CRITICAL**: T002~T015 완료 전 스토리 작업 금지

### Tests (Red 먼저)

- [x] T002 [P] `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepositoryTest.kt` 를 새 필드로 갱신 — `given("설정 기록이 없는 회원")` 은 `defaultFor` 가 `activity=true`·`mealTime=true` 인지, `given("선호 설정 변경")` 은 `updateActivity(false)`·`updateMealTime(false)` 후 재조회가 반영되는지. `helpful`·`reviewReminder` 참조 전부 제거. 컴파일 실패 = Red 확인.
- [x] T003 [P] `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationConsentJpaRepositoryTest.kt` 를 `consentType` 으로 갱신 — 기존 `grantForMember`·`grantForInstallation` 호출에 `NotificationConsentType.MARKETING_RECEIVE` 인자 추가, `given("회원 동의")` 에 `then("종류가 다른 두 열린 행이 한 회원에 공존한다")`(PRIVACY v1 + RECEIVE v1 저장 후 `findOpenByMemberId` 2건, `groupBy { it.consentType }` 키 2개) 추가, `closeOpenByMemberId` 가 두 종류를 전부 닫는지 검증. 컴파일 실패 = Red.
- [x] T004 [P] `api/src/test/kotlin/com/kbap/api/notification/NotificationTokenControllerTest.kt` 의 `given("게스트 광고성 동의")` 를 두 종류 계약으로 갱신 — 헬퍼 `on(privacy: Int? = 1, receive: Int? = 1)` 로 바꿔 `settings = {marketing: true, privacyConsentVersion, receiveConsentVersion}` 전송, `Consent` 조회 헬퍼에 `consentType` 컬럼 추가. 시나리오: on → 종류별 열린 행 1개씩(총 2), 같은 버전 재요청 무변화, `receive` 만 2 로 올리면 RECEIVE 행만 닫히고 새 행(PRIVACY 무변화), off → 두 종류 전부 `revoked_at` 스탬프·행 보존, `marketing=true` 인데 한 버전 누락 → 400 `COMMON-002`, 회원 요청의 settings 무시. `given("잘못된 등록 요청")` 의 `marketingConsentVersion` 참조 제거. 실행해 실패(Red) 확인.
- [x] T005 [P] `api/src/test/kotlin/com/kbap/api/auth/AuthNotificationLinkTest.kt` 의 로그인 인수 시나리오를 종류별로 갱신 — 게스트 기기에 PRIVACY·RECEIVE 열린 행 2개를 심고, (a) 회원에게 열린 동의 없음 → 두 행 모두 `member_id` 인수·`granted_at` 보존, (b) 회원에게 RECEIVE 만 열려 있음 → 게스트 RECEIVE 행은 철회, PRIVACY 행은 인수. 실행해 실패(Red) 확인.

### Implementation

- [x] T006 [P] `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationConsentType.kt` 신규 — `enum class NotificationConsentType { MARKETING_PRIVACY, MARKETING_RECEIVE }`.
- [x] T007 [P] `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationSetting.kt` 변경 — 필드 `activity: Boolean = true`(`@Column(name = "activity", nullable = false)`)·`mealTime: Boolean = true`(`meal_time`)로 교체, `updateActivity(enabled)`·`updateMealTime(enabled)`, `preferences()` 는 `NotificationPreferences(activity, mealTime)`. `helpful`·`reviewReminder` 삭제.
- [x] T008 [P] `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationPreferences.kt` 변경 — `data class NotificationPreferences(val activity: Boolean = true, val mealTime: Boolean = true)` + `DEFAULT`.
- [x] T009 `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationConsent.kt` 변경(T006 후) — 필드 `consentType: NotificationConsentType`(`@Enumerated(EnumType.STRING) @Column(name = "consent_type", nullable = false, length = 30)`, 기본값 `MARKETING_RECEIVE`) 추가, companion `grantForMember(memberId, installationId, type, consentVersion, now)`·`grantForInstallation(installationId, type, consentVersion, now)` 시그니처 갱신.
- [x] T010 [P] `common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt` 에 `MARKETING_CONSENT_REQUIRED("NOTIFICATION-001", 400, "K-Bap 소식 수신 동의 후 설정할 수 있습니다")` 추가. `ErrorCodeStatusTest` 가 형식·유일성을 검증하므로 `:common:test` 로 통과 확인.
- [x] T011 `./gradlew :common:test --tests "com.kbap.common.domain.notification.*"` 실행 — T002·T003 Green 확인. Flyway 가 T001 을 적용하고 `ddl-auto=validate` 가 통과해야 한다(실패하면 컬럼명·길이 불일치 수정).
- [x] T012 `api/src/main/kotlin/com/kbap/api/notification/NotificationConsentService.kt` 신규(`@Service`, T006·T009 후) — `NotificationConsentJpaRepository` 주입. 메서드(전부 `@Transactional`): `grantForMember(memberId: Long, installationId: String?, versions: Map<NotificationConsentType, Int>, now: LocalDateTime)` — `findOpenByMemberId` 를 `groupBy { consentType }` 로 나눠 종류마다 R7 규칙(버전 다르면 `revoke(now)`, 같은 버전 있으면 무변화, 없으면 `save(grantForMember(...))`); `revokeForMember(memberId, now)` — `closeOpenByMemberId`; `grantForInstallation(installationId, versions, now)`·`revokeForInstallation(installationId, now)` — `findOpenGuestByInstallationId` 기반 동일 규칙; `isMarketingEnabled(open: List<NotificationConsent>): Boolean` — PRIVACY·RECEIVE 둘 다 열린 행 존재. 조회 헬퍼 `findOpenByMemberId(memberId)` 는 리포지토리 직접 사용(창구 서비스 금지)이므로 두지 않는다.
- [x] T013 `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenRegisterRequest.kt` 변경 — `MarketingSettingsRequest` 를 `{ marketing: Boolean?, privacyConsentVersion: Int?, receiveConsentVersion: Int? }` 로 교체(각 버전 `@field:Positive` + `@field:Max(65535)`), `@get:AssertTrue val versionsPresentWhenOptedIn = marketing != true || (privacyConsentVersion != null && receiveConsentVersion != null)`, `marketingConsentVersion` 삭제, Schema 설명에 두 동의 명칭. `NotificationTokenApi.kt` 의 문서 문구도 맞춘다.
- [x] T014 `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenService.kt` 변경(T012·T013 후) — `NotificationConsentService` 주입, `applyGuestMarketingConsent` 를 `marketing == true → grantForInstallation(installationId, mapOf(PRIVACY to p, RECEIVE to r), now)` / `false → revokeForInstallation` 위임으로 축소, `linkOnLogin` 의 인수 분기를 종류별로: `val memberOpenTypes = findOpenByMemberId(memberId).map { it.consentType }.toSet()`; 게스트 열린 행 각각 `if (it.consentType in memberOpenTypes) it.revoke(now) else it.claim(memberId)`. 나머지 메서드 무변경.
- [x] T015 `./gradlew :api:test --tests "com.kbap.api.notification.NotificationTokenControllerTest" --tests "com.kbap.api.auth.AuthNotificationLinkTest"` 실행 — T004·T005 Green 확인. 기존 `AuthControllerTest`·기타 회귀는 Phase 6 전체 빌드에서.

**Checkpoint**: 저장 구조·게스트 계약이 새 모델로 이전됨. 설정 API 스토리 시작 가능.

---

## Phase 3: User Story 1 - 회원이 자신의 알림 설정을 조회한다 (Priority: P1) 🎯 MVP

**Goal**: `GET /api/notifications/settings` 가 활동/소식·K-Bap 소식(켜짐·식사 시간 알림·두 동의)을 서버 정본으로 돌려준다. 설정 없으면 기본값, 조회는 기록을 만들지 않는다.

**Independent Test**: 설정 없는 회원 → 기본값 응답. 리포지토리로 설정·동의를 직접 심은 회원 → 심은 값 그대로. 게스트 → 401.

### Tests for User Story 1 ⚠️

- [x] T016 [US1] `api/src/test/kotlin/com/kbap/api/notification/NotificationSettingControllerTest.kt` 신규(`@IntegrationTest`, `BehaviorSpec`, `SpringExtension`) — `beforeSpec` 에서 `TestTables.clearAll(dataSource)`. 헬퍼: `login(sub)`(기존 토큰 테스트의 `FakeSocialTokenVerifier` 경로로 회원 생성 + access 토큰), `get(access)`·`patch(access, body)`(헤더 `X-API-Version: 1.1`·`Authorization`), `seedSetting(memberId, activity, mealTime)`·`seedConsent(memberId, type, version, grantedAt, revokedAt?)`(리포지토리 직접 저장). `given("알림 설정 조회")`: then 5개 — (1) 설정 없음 → `activity=true, kbapNews.enabled=false, mealTime=false, privacyConsent=null, receiveConsent=null` 이고 `notification_setting` 행 수 0, (2) activity=false 심음 → false, (3) PRIVACY v1 + RECEIVE v2 열림 + mealTime=false → `enabled=true`, `privacyConsent.version=1`, `receiveConsent.version=2`, `grantedAt` 채워짐, `mealTime=false`, (4) RECEIVE 만 열림(PRIVACY 철회됨) → `enabled=false`, `privacyConsent=null`, `receiveConsent` 채워짐, `mealTime=false`(저장값 true 여도), (5) 인증 없음 → 401. `X-API-Version: 1.0` → 404 도 then 하나. 실행해 실패(Red) 확인.

### Implementation for User Story 1

- [x] T017 [P] [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationSettingsResponse.kt` 신규 — `data class NotificationSettingsResponse(val activity: Boolean, val kbapNews: KbapNewsResponse)`, `data class KbapNewsResponse(val enabled: Boolean, val mealTime: Boolean, val privacyConsent: ConsentResponse?, val receiveConsent: ConsentResponse?)`, `data class ConsentResponse(val version: Int, val grantedAt: LocalDateTime)`. `@Schema` 설명은 contracts 표의 의미 그대로. companion `from(result: NotificationSettingsResult)`.
- [x] T018 [P] [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationSettingService.kt` 신규(`@Service`) — 주입 `NotificationSettingJpaRepository`·`NotificationConsentJpaRepository`·`NotificationConsentService`·`MemberService`. 결과 타입 `NotificationSettingsResult(activity, kbapNewsEnabled, mealTime, privacyConsent: NotificationConsent?, receiveConsent: NotificationConsent?)` 같은 파일. `@Transactional(readOnly = true) fun getSettings(memberId: Long): NotificationSettingsResult` — `memberService.getMember(memberId)` 로 활성 회원 검증, `preferences = settingRepository.findByMemberId(memberId)?.preferences() ?: NotificationPreferences.DEFAULT`, `open = consentRepository.findOpenByMemberId(memberId)`, `enabled = consentService.isMarketingEnabled(open)`, 종류별 최신(`maxByOrNull { grantedAt }`) 행, `mealTime = preferences.mealTime && enabled`. 내부 조립은 private `assemble(memberId)` 로 두어 US2·US3 의 `updateSettings` 가 재사용.
- [x] T019 [P] [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationSettingApi.kt` 신규 — `@Tag(name = "Notification")`, `getSettings` 오퍼레이션 `@Operation`·`@ApiResponses`(200·401·404 1.0)·`@SecurityRequirement("bearerAuth")`. swagger 애너테이션만.
- [x] T020 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationSettingController.kt` 신규(T017·T018·T019 후) — `@RestController @RequestMapping(ApiPaths.API + "/notifications")`, `@GetMapping("/settings") fun getSettings(@AuthMemberId memberId: Long): ResponseEntity<BaseResponse<NotificationSettingsResponse>>`.
- [x] T021 [US1] `api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt` — `jwtAuthenticationFilterRegistration` 의 `addUrlPatterns` 에 `"${ApiPaths.API}/notifications/settings"` 추가(정확 경로. `/notifications/*` 금지 — 토큰 등록의 게스트 경로가 깨진다).
- [x] T022 [US1] `./gradlew :api:test --tests "com.kbap.api.notification.NotificationSettingControllerTest"` — T016 조회 then 전부 Green.

**Checkpoint**: 조회만으로 앱 팀이 설정 화면을 서버 정본으로 그릴 수 있다.

---

## Phase 4: User Story 2 - 회원이 활동/소식·식사 시간 알림 토글을 켜고 끈다 (Priority: P2)

**Goal**: `PATCH /api/notifications/settings` 부분 수정으로 `activity`·`kbapNews.mealTime` 을 바꾼다. 소식이 꺼진 회원의 `mealTime=true` 는 `NOTIFICATION-001`. 빈 본문은 무변화.

**Independent Test**: activity off → 조회 off·나머지 불변. 동의 심어 둔 회원이 mealTime off → 그것만 바뀜. 동의 없는 회원 mealTime on → 400 NOTIFICATION-001.

### Tests for User Story 2 ⚠️

- [x] T023 [US2] `NotificationSettingControllerTest.kt` 에 `given("토글 수정")` 추가 — then: (1) 설정 없음 + `{activity:false}` → 200, 응답 `activity=false`, 행 생성, `kbapNews` 기본값 유지, (2) activity=false 상태 + `{activity:true}` → true, (3) 두 동의 열림 + mealTime=true 상태 + `{kbapNews:{mealTime:false}}` → `mealTime=false`, `enabled=true` 유지, 원장 무변화(행 수 동일·revoked_at 없음), (4) 동의 없음 + `{kbapNews:{mealTime:true}}` → 400 `code=NOTIFICATION-001`, 설정 행 미생성, (5) 동의 없음 + `{kbapNews:{mealTime:false}}` → 200(끄기는 허용, 값 보존), (6) `{}` → 200 현재 설정, 행 미생성. 실행해 실패(Red) 확인.

### Implementation for User Story 2

- [x] T024 [P] [US2] `api/src/main/kotlin/com/kbap/api/notification/NotificationSettingsUpdateRequest.kt` 신규 — `data class NotificationSettingsUpdateRequest(val activity: Boolean? = null, @field:Valid val kbapNews: KbapNewsUpdateRequest? = null)`, `data class KbapNewsUpdateRequest(val enabled: Boolean? = null, val mealTime: Boolean? = null, @field:Positive @field:Max(65535) val privacyConsentVersion: Int? = null, @field:Positive @field:Max(65535) val receiveConsentVersion: Int? = null)` + `@get:AssertTrue(message = "enabled 가 true 면 두 동의 버전이 모두 필요합니다") val versionsPresentWhenEnabled = enabled != true || (privacyConsentVersion != null && receiveConsentVersion != null)`. `@Schema` 로 contracts 규칙 서술. (US3 의 `enabled` 필드까지 여기서 정의한다 — DTO 파일은 한 번만.)
- [x] T025 [US2] `NotificationSettingService.kt` 에 `@Transactional fun updateSettings(memberId: Long, installationId: String?, request: NotificationSettingsUpdateRequest): NotificationSettingsResult` 추가 — `memberService.getMember`, `now = LocalDateTime.now()`, `setting = settingRepository.findByMemberId(memberId)`; 순서: `request.activity?.let { settingOrCreate().updateActivity(it) }` → (`kbapNews.enabled` 처리는 US3 T028 에서 채움, 여기서는 호출 지점만 둔다) → `kbapNews.mealTime?.let { if (it && !consentService.isMarketingEnabled(findOpenByMemberId)) throw BusinessException(MARKETING_CONSENT_REQUIRED); settingOrCreate().updateMealTime(it) }` → `assemble(memberId)` 반환. `settingOrCreate()` 는 없을 때만 `save(NotificationSetting.defaultFor(memberId))`(빈 본문·거절 경로에서는 행이 생기지 않게 lazy).
- [x] T026 [US2] `NotificationSettingController.kt` 에 `@PatchMapping("/settings") fun updateSettings(@AuthMemberId memberId: Long, @RequestHeader(ApiHeaders.INSTALLATION_ID, required = false) installationId: String?, @Valid @RequestBody request: NotificationSettingsUpdateRequest)` 추가, `NotificationSettingApi.kt` 에 오퍼레이션 문서(400 COMMON-002·NOTIFICATION-001 포함).
- [x] T027 [US2] `./gradlew :api:test --tests "com.kbap.api.notification.NotificationSettingControllerTest"` — 토글 수정 then 전부 Green.

**Checkpoint**: 활동/소식·식사 시간 알림 토글이 서버 정본으로 동작.

---

## Phase 5: User Story 3 - 회원이 K-Bap 소식을 켜며 두 가지 동의를 남기고, 끄며 철회한다 (Priority: P2)

**Goal**: `kbapNews.enabled=true` + 두 버전 → 종류별 원장 grant(같은 버전 무변화·다른 버전 닫고 새 행), `enabled=false` → 두 종류 전부 철회(행 보존). 켜기 시 mealTime 은 저장값(기본 true) 그대로 복원.

**Independent Test**: 켜기 → 종류별 열린 행 1개씩·mealTime=true → 같은 버전 재요청 무변화 → receive 만 v2 → RECEIVE 만 닫고 새 행 → mealTime off → 끄기 → 전부 철회·행 보존 → 다시 켜기 → mealTime=false 복원.

### Tests for User Story 3 ⚠️

- [x] T028 [US3] `NotificationSettingControllerTest.kt` 에 `given("K-Bap 소식 동의")` 추가 — then: (1) 동의 없음 + `{kbapNews:{enabled:true, privacyConsentVersion:1, receiveConsentVersion:1}}` → 200, `enabled=true`, `mealTime=true`, 두 consent `version=1`·`grantedAt` 채워짐, 원장 행 2(종류별 1), (2) 한 버전 누락 → 400 `COMMON-002`, 원장·설정 무변화, (3) 같은 버전 재요청 → 원장 행 수·id 동일, (4) `{enabled:true, privacy:1, receive:2}` → RECEIVE v1 행 `revoked_at` 스탬프 + RECEIVE v2 새 행, PRIVACY 행 그대로(총 3행), (5) mealTime=false 로 둔 뒤 `{enabled:false}` → 두 종류 열린 행 모두 `revoked_at`, 행 삭제 없음, 응답 `enabled=false, mealTime=false, consents null`, (6) 동의 없음 + `{enabled:false}` → 200 무변화, (7) 본문에 `grantedAt` 같은 시각 필드를 실어도 무시(서버 시각), (8) (5) 이후 다시 켜기 → `mealTime=false` 복원(기본값 true 로 안 돌아감), (9) `{enabled:true, …, mealTime:false}` 한 요청 → enabled 먼저 반영돼 200·mealTime=false, (10) `X-Installation-Id` 헤더를 실어 켜면 원장 행의 `installation_id` 에 기록됨. 실행해 실패(Red) 확인.

### Implementation for User Story 3

- [x] T029 [US3] `NotificationSettingService.updateSettings` 의 `kbapNews.enabled` 경로 채우기(T025 의 호출 지점) — `true` → `consentService.grantForMember(memberId, installationId, mapOf(MARKETING_PRIVACY to privacyConsentVersion!!, MARKETING_RECEIVE to receiveConsentVersion!!), now)`; `false` → `consentService.revokeForMember(memberId, now)`. `enabled` 반영 뒤에 `mealTime` 검증이 오도록 순서 유지(같은 요청의 켜기 + mealTime=true 허용). `isMarketingEnabled` 판정은 grant 후 재조회한 열린 행으로.
- [x] T030 [US3] `./gradlew :api:test --tests "com.kbap.api.notification.*"` — US3 then 전부 Green, US1·US2 회귀 없음.

**Checkpoint**: 세 스토리 완료. 설정 화면 전체가 서버 정본으로 동작.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T031 [P] `./gradlew build` — 전 모듈 컴파일·ArchUnit(`ModuleBoundaryTest`)·전체 회귀. `ReviewListControllerTest` 등 `helpful` 문자열이 리뷰 도메인 것인지 확인(알림과 무관하면 무변경).
- [x] T032 [P] `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenApi.kt`·`NotificationSettingApi.kt` swagger 문구 최종 점검 — 두 동의 명칭(마케팅 목적 개인정보 수집·이용 / 광고성 정보 수신), 그룹 명칭(활동 푸시·K-Bap에서 보내는 소식), 1.0 요청 404 안내.
- [x] T033 [P] Jira KB-466 본문을 확정 계약으로 갱신(경로 `/api/notifications/settings`, 중첩 응답, 두 동의) 및 KB-465 본문의 `settings` 필드 변경 반영 — `mcp__atlassian__editJiraIssue`. 앱 팀 공유 메모 한 줄(계약 변경·호환 없음).
- [x] T034 [P] 지식 위키 `../kbap-agenthub/wiki/push-notification-consent-model.md`(없으면 신규, 있으면 갱신) — 두 그룹 재편 결정(활동/소식 단일 토글, K-Bap 소식 = 두 동의, 식사 시간 알림 단일 토글, `meal_time` 기본 TRUE 로 tri-state 회피, 판정 = 두 종류 열린 동의) 기록 + `INDEX.md` 한 줄, 허브에서 커밋.
- [x] T035 `commit-after-task` 스킬로 커밋(Phase 별 커밋이 이미 있으면 최종 정리 커밋만) 후 `open-draft-pr-to-develop` 으로 develop 대상 draft PR.

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2 → US1 → US2 → US3 → Phase 6** 순차. US2·US3 는 US1 의 서비스·컨트롤러 파일에 메서드를 추가하므로 US1 뒤에 온다(파일 공유). US3 는 US2 의 `updateSettings` 골격과 요청 DTO 를 쓴다.
- Phase 2 안: T002·T003·T004·T005(테스트, 병렬) → T006·T007·T008·T010(병렬) → T009(T006 후) → T011 → T012(T009 후) → T013 → T014(T012·T013 후) → T015.
- US1 안: T016 → T017·T018·T019(병렬) → T020 → T021 → T022.
- 데이터 흐름: T001 마이그레이션 없이는 T011 의 ddl validate 가 실패한다. T001 을 가장 먼저.

## Parallel Execution Examples

- Phase 2 Red: T002·T003(common)·T004·T005(api) 네 테스트 파일을 동시에 갱신.
- Phase 2 Green: T006 enum·T007 setting 엔티티·T008 값 객체·T010 ErrorCode 는 서로 다른 파일 → 동시.
- US1: T017 응답 DTO·T018 서비스·T019 swagger 인터페이스 동시 작성 후 T020 컨트롤러가 셋을 묶는다.
- Phase 6: T031 빌드·T032 문서·T033 Jira·T034 위키 동시.

## Implementation Strategy

- **MVP = Phase 1 + Phase 2 + US1**: 마이그레이션·엔티티·게스트 계약 이전과 조회 API. 이 시점에 dev 배포하면 앱 팀이 화면을 서버 정본으로 그리기 시작할 수 있다.
- 이어서 US2(토글)·US3(동의)를 각각 커밋 단위로. US3 까지 끝나야 Jira DoD 충족.
- 각 Phase 끝에 `commit-after-task` 로 커밋해 Red→Green 이력을 남긴다.

## Format Validation

35개 태스크 전부 `- [ ] Tnnn [P?] [USn?] 설명 + 경로` 형식. 스토리 단계(T016~T030)는 `[US1]`·`[US2]`·`[US3]` 라벨, Setup·Foundational·Polish 는 라벨 없음.
