# Tasks: 알림 설정 기기별 분리 — (회원, 기기) 단위 토글, 광고성 동의는 회원 단위 유지

**Input**: Design documents from `/specs/kb-544-device-notification-settings/`

**Prerequisites**: plan.md, spec.md, research.md(결정 1~10), data-model.md, contracts/notification-settings.md, quickstart.md

**Tests**: Test-First 는 헌법 원칙 I(NON-NEGOTIABLE). 각 스토리는 실패 테스트를 먼저 쓰고 Red 를 확인한 뒤 구현한다. 모든 테스트는 Kotest `BehaviorSpec`, `given/when/then` 한국어. Kotest 는 Gradle `--tests` 필터를 무시하므로 Red/Green 은 모듈 단위(`:common:test`·`:api:test`)로 확인한다.

**Organization**: 스토리별 구분. Foundational(엔티티·리포지토리·마이그레이션)이 끝나면 US1→US2→US3→US4 순서가 자연스럽지만, US3(발송)·US4(생명주기)는 US1·US2 와 파일이 겹치지 않아 병행 가능하다.

**규율**: Kotlin 소스 주석 금지(SQL 주석은 허용). 새 클래스는 새 계약 요청 DTO 한 벌(T009)뿐, 새 패키지 없음. 격리수준·락·재시도 등 부가 방어 추가 금지. 서비스 메서드는 명시적 `@Transactional`. 태스크(또는 논리 단위)마다 커밋.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·미완 태스크 의존 없음 → 병행 가능
- **[Story]**: US1~US4(spec.md)

## Path Conventions

- `:common` — `common/src/{main,test}/kotlin/com/kbap/common/domain/notification/`
- `:api` — `api/src/{main,test}/kotlin/com/kbap/api/notification/`, `api/src/main/resources/db/migration/`

---

## Phase 1: Setup

**Purpose**: 기존 프로젝트라 초기화 작업은 없다. 버전 마커 전제만 고정한다.

- [x] T001 research.md 결정 2 의 새 계약 버전 마커 `2.1` 을 확정값으로 채택한다(FE 회신이 다르면 T011·T012 의 `version = "2.1+"` 두 곳만 바꾼다). 코드 변경 없음 — 이 태스크는 확인 체크다.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: `notification_setting` 을 (회원, 기기) 단위로 확장하고 구 계약 경로를 NULL 행으로 고정한다. 모든 스토리가 이 스키마·리포지토리에 의존한다.

**⚠️ CRITICAL**: 이 단계가 끝나야 스토리 작업을 시작할 수 있다.

### Tests (Red 먼저)

- [x] T002 `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepositoryTest.kt` 를 기기 단위로 재작성한다. `given("기기별 설정 저장")`: (a) 같은 회원의 기기 두 대(`defaultFor(1L, "dev-a")`·`defaultFor(1L, "dev-b")`)를 저장하면 둘 다 남고 `findByMemberIdAndInstallationId(1L, "dev-a")`·`("dev-b")` 가 각각 자기 행을 돌려준다, (b) 같은 (회원, 기기) 쌍을 두 번 `saveAndFlush` 하면 `DataIntegrityViolationException`, (c) `defaultFor(1L)`(구 계약, installationId null) 행은 `findByMemberIdAndInstallationIdIsNull(1L)` 로만 보이고 `findByMemberIdAndInstallationId(1L, "dev-a")` 에는 안 잡힌다, (d) 기본값은 activity·mealTime·news 전부 false. `given("탈퇴 정리 조회")`: `findByMemberIdAndInstallationIdIsNotNull(1L)` 이 NULL 행을 빼고 기기 행만 돌려준다. 기존 `given("선호 설정 변경")` 은 `updateNews(true)` 를 더해 세 토글 저장을 검증하도록 손본다. **컴파일 실패(Red) 확인**: `./gradlew :common:test`.

### Implementation

- [x] T003 `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationSetting.kt` — data-model.md 대로 변경: `@Table` 의 `uniqueConstraints` 를 `uk_notification_setting_member_installation(member_id, installation_id)` 하나로 교체, 필드 `installationId: String? = null`(`@Column(name = "installation_id", length = 36)`)·`news: Boolean = false`(`@Column(name = "news", nullable = false)`) 추가, `fun updateNews(enabled: Boolean)` 추가, companion 에 `fun defaultFor(memberId: Long, installationId: String) = NotificationSetting(memberId = memberId, installationId = installationId)` 추가(기존 `defaultFor(memberId)` 유지).
- [x] T004 `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepository.kt` — `findByMemberId` 를 삭제하고 파생 쿼리 3개를 추가: `findByMemberIdAndInstallationIdIsNull(memberId: Long): NotificationSetting?`, `findByMemberIdAndInstallationId(memberId: Long, installationId: String): NotificationSetting?`, `findByMemberIdAndInstallationIdIsNotNull(memberId: Long): List<NotificationSetting>`. `findByMemberIdIn` 유지.
- [x] T005 `api/src/main/kotlin/com/kbap/api/notification/NotificationService.kt` — 구 계약 경로만 컴파일되게 최소 수정: `settingOf`·`assemble` 의 `findByMemberId(memberId)` 를 `findByMemberIdAndInstallationIdIsNull(memberId)` 로 교체. 동작 변화 없음(구 계약은 NULL 행만 본다 — 결정 4).
- [x] T006 마이그레이션 생성 `api/src/main/resources/db/migration/V<yyyy.MM.dd.HH.mm.ss — 파일 생성 시각>__notification_setting_per_device.sql` — data-model.md 의 SQL 그대로: 첫 ALTER 에서 `installation_id VARCHAR(36) NULL AFTER member_id`·`news BOOLEAN NOT NULL DEFAULT FALSE AFTER meal_time`·`UNIQUE KEY uk_notification_setting_member_installation (member_id, installation_id)` 추가, 두 번째 ALTER 에서 `DROP INDEX uk_notification_setting_member`. 파일 머리에 SQL 주석으로 KB-544 결정(기기 단위 재정의·NULL 행은 구 계약·고유키 교체 순서 이유)을 적는다. 기존 행 UPDATE/DELETE 없음.
- [x] T007 Green 확인: `./gradlew :common:test` 와 `./gradlew :api:test` 전부 통과. 특히 api 의 `NotificationSettingControllerTest`·`AuthNotificationLinkTest`·`NotificationTokenControllerTest` 는 **한 줄도 바꾸지 않은 채** 통과해야 한다(SC-004 — 마이그레이션이 `ddl-auto=validate` 를 통과했다는 증거). `PushTargetResolverTest` 는 아직 회원 단위 로직이라 통과한다(변경은 US3).

**Checkpoint**: 스키마·엔티티·리포지토리가 기기 단위. 구 계약은 무변경 동작.

---

## Phase 3: User Story 1 — 기기마다 알림 설정을 따로 켜고 끈다 (Priority: P1) 🎯 MVP

**Goal**: `X-API-Version: 2.1` + `X-Installation-Id`(필수) 로 이 기기의 설정을 조회·수정한다. 설정 없는 기기는 전부 꺼짐(행 생성 없음). 구 버전 헤더는 종전 동작.

**Independent Test**: 회원 하나로 기기 A·B 헤더를 번갈아 PATCH/GET 하면 서로 다른 값이 돌아오고, 1.1 헤더 GET 은 NULL 행(구 계약) 값을 돌려준다.

### Tests for User Story 1 (Red 먼저) ⚠️

- [x] T008 [US1] `api/src/test/kotlin/com/kbap/api/notification/NotificationSettingControllerTest.kt` 에 헬퍼와 `given("기기별 설정 조회 — 2.1")`·`given("기기별 토글 수정 — 2.1")` 블록을 추가한다(기존 블록은 손대지 않는다). 헬퍼: `getDevice(accessToken, installationId: String?, apiVersion = "2.1")`·`patchDevice(accessToken, installationId: String?, body, apiVersion = "2.1")`(헤더 null 이면 미전송), `seedDeviceSetting(memberId, installationId, activity, mealTime, news)`(INSERT 에 `installation_id`·`news` 포함), `settingRows(memberId): List<Pair<String?, Triple<Boolean,Boolean,Boolean>>>`(installation_id 순 SELECT). 시나리오: (1) 설정을 만진 적 없는 기기 GET → `activity=false`·`news.enabled=false`·`news.mealTime=false`·두 consent null, `notification_setting` 에 그 기기 행 없음(US1-2·FR-002·FR-011 조회 부분), (2) 기기 A·B 를 `seedDeviceSetting` 으로 activity=true 로 심고 A 에 `{activity:false}` PATCH → A GET false, B GET true(US1-1), (3) 헤더 없음·`" "`·37자 로 GET/PATCH → 400 `COMMON-002`, 행 생성 없음(US1-3), (4) 회원에 NULL 행(`seedSetting(memberId, activity=true, mealTime=false)`)과 기기 A 행(activity=false)을 심고 `X-API-Version: 1.1` GET → activity=true(구 계약은 NULL 행), 2.1 + A GET → false(US1-4·FR-005), (5) 2.1 PATCH `{activity:true}` 로 기기 행이 처음 생기면 NULL 행은 생기지 않고 1.1 GET 은 기본값 그대로다. **Red 확인**: `./gradlew :api:test` — 2.1 요청이 무버전 매핑으로 흘러 헤더 검증 없이 200 이 나오거나 회원 단위 값이 나와 실패한다.

### Implementation for User Story 1

- [x] T009 [P] [US1] 새 파일 `api/src/main/kotlin/com/kbap/api/notification/DeviceNotificationSettingsUpdateRequest.kt` — `data class DeviceNotificationSettingsUpdateRequest(val activity: Boolean? = null, @field:Valid val news: DeviceNewsUpdateRequest? = null)` 와 `data class DeviceNewsUpdateRequest(val enabled: Boolean? = null, val consent: Boolean? = null, val mealTime: Boolean? = null, val privacyConsentVersion: Int? = null, val receiveConsentVersion: Int? = null)`. 버전 두 필드는 기존 `NewsUpdateRequest` 와 같은 `@field:Positive`·`@field:Max(NewsUpdateRequest.MAX_CONSENT_VERSION)`, `@get:AssertTrue(message = "consent 가 true 면 privacyConsentVersion·receiveConsentVersion 이 모두 필요합니다") @get:Schema(hidden = true) val versionsPresentWhenConsenting get() = consent != true || (privacyConsentVersion != null && receiveConsentVersion != null)`. `@Schema` 서술: `enabled` = "이 기기 광고성 소식 수신 on/off — 동의 원장 불변", `consent` = "true = 회원 마케팅 동의(두 버전 필수), false = 회원 동의 전부 철회 — 기기값 불변", `mealTime` = "true 는 (이 요청 반영 후) 이 기기 소식이 켜져 있어야 한다 — 아니면 NOTIFICATION-001", 클래스 설명에 처리 순서 `activity → consent → enabled → mealTime`. 구 `NotificationSettingsUpdateRequest.kt` 는 손대지 않는다.
- [x] T010 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationService.kt` — 추가 메서드: `@Transactional(readOnly = true) fun getDeviceSettings(memberId: Long, installationId: String): NotificationSettingsResult` — `memberService.getMember(memberId)` 후 `assembleDevice(memberId, installationId)`. `@Transactional fun updateDeviceSettings(memberId: Long, installationId: String, request: DeviceNotificationSettingsUpdateRequest): NotificationSettingsResult` — `getMember` 후 `request.activity != null` 이면 `deviceSettingOf(memberId, installationId).updateActivity(...)`; `request.news` 분기는 US2(T014)에서 채운다(지금은 activity 만). private `deviceSettingOf(memberId, installationId) = settingRepository.findByMemberIdAndInstallationId(...) ?: settingRepository.save(NotificationSetting.defaultFor(memberId, installationId))`, private `assembleDevice(memberId, installationId)`: 행 없으면 `defaultFor(memberId, installationId)`, `enabled = setting.news`, `mealTime = setting.mealTime && setting.news`, consent 두 건은 기존 `assemble` 과 같은 방식(종류별 열린 최신) — 그 추출 로직을 private 함수로 빼서 두 조립이 공유한다(중복 금지). 새 주입 없음. 구 메서드 `getSettings`·`updateSettings` 는 그대로.
- [x] T011 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationController.kt` — 같은 경로에 버전 매핑 두 개 추가: `@GetMapping("/settings", version = "2.1+") override fun getDeviceSettings(@AuthMemberId memberId: Long, @RequestHeader(ApiHeaders.INSTALLATION_ID) installationId: String)` 와 `@PatchMapping("/settings", version = "2.1+") override fun updateDeviceSettings(@AuthMemberId memberId: Long, @RequestHeader(ApiHeaders.INSTALLATION_ID) installationId: String, @Valid @RequestBody request: DeviceNotificationSettingsUpdateRequest)`. 둘 다 `ApiHeaders.validInstallationId(installationId)` 를 거쳐 서비스 호출, 응답 `ResponseEntity.ok(BaseResponse.ok(NotificationSettingsResponse.from(result)))`. 헤더 누락은 Spring 의 `MissingRequestHeaderException` → 기존 예외 핸들러가 400 `COMMON-002` 로 매핑하는지 `NotificationController.getRecentNotifications` 선례로 확인(같은 방식이면 추가 코드 없음). 구 매핑 두 개는 그대로.
- [x] T012 [US1] `api/src/main/kotlin/com/kbap/api/notification/NotificationApi.kt` — 인터페이스 메서드 `getDeviceSettings(memberId: Long, installationId: String)`·`updateDeviceSettings(memberId: Long, installationId: String, request: DeviceNotificationSettingsUpdateRequest)` 추가. `@Operation(summary = "알림 설정 조회 — X-API-Version 2.1 이상(기기 단위)")` 등 contracts/notification-settings.md 의 "Swagger 서술" 절 문구를 옮긴다(헤더 필수·설정 없는 기기 전부 false·기록 생성 없음·`news.enabled` 는 기기 저장값·동의 상태는 두 동의 항목·2.0 이하 헤더는 종전 회원 단위). `@Parameter(name = ApiHeaders.INSTALLATION_ID, in = HEADER, required = true)` 는 인터페이스에만, Spring 애너테이션은 컨트롤러에만(규약). 구 메서드 두 개의 `description` 끝에 "2.1 이상 헤더는 기기 단위 계약(아래 메서드)으로 처리된다" 한 줄 추가. `@ApiResponses` 에 400 `COMMON-002`(헤더)·401 추가.
- [x] T013 [US1] Green 확인: `./gradlew :api:test` 전부 통과(T008 시나리오 + 기존 블록 무변경). 필요하면 리팩터(조립 공유 함수 정리)만 하고 커밋.

**Checkpoint**: 기기별 활동 토글이 동작하고 구 계약이 공존한다. MVP.

---

## Phase 4: User Story 2 — 소식 토글과 마케팅 수신 동의는 따로 켜고 끈다 (Priority: P1)

**Goal**: 소식 그룹에서 `consent`(회원 동의 켜기/끄기)와 `enabled`(이 기기 수신)를 분리한다. `consent` 만 원장을 바꾸고 `enabled` 만 기기값을 바꾼다. 식사시간 켜기는 이 기기 news on 일 때만(동의 무관).

**Independent Test**: 동의를 켠 뒤 소식을 껐다 켜도 동의 원장이 0건 변경이고, `consent:false` 는 원장을 닫되 기기값을 0건 변경한다. 소식 꺼진 기기에서 mealTime 켜기는 `NOTIFICATION-001`.

### Tests for User Story 2 (Red 먼저) ⚠️

- [x] T014 [US2] `api/src/test/kotlin/com/kbap/api/notification/NotificationSettingControllerTest.kt` 에 `given("기기별 소식 토글과 회원 동의 — 2.1")` 블록 추가(기존 `consents(memberId)` 헬퍼·`seedConsent`·T008 의 `seedDeviceSetting`·`settingRows` 재사용, 동의 버전은 2). 시나리오: (1) 동의 없는 회원이 기기 A 로 `{news:{consent:true, privacyConsentVersion:2, receiveConsentVersion:2}}` → 동의 두 건 열림(`installation_id = "dev-a"`)·응답 `privacyConsent`·`receiveConsent` 채워짐·`enabled=false`·`notification_setting` 에 A 행 없음(기기값 불변)(US2-1), (2) 동의 v2 열림(시드) + A `{news:{enabled:true}}` → A 행 news=true·응답 `enabled=true`·원장 행 수·`revoked_at` 불변·B GET `enabled=false`(US2-2), (3) A news=true 시드 + 동의 열림 + A `{news:{enabled:false}}` → A false·동의 `revoked_at` null 유지(기기 한 대여도)(US2-3), (4) 동의 열림 + A·B news=true 시드 + A `{news:{consent:false}}` → 두 동의 `revoked_at` 기록·A·B GET `enabled=true` 그대로(US2-4), (5) A news=false 에서 `{news:{mealTime:true}}` → 400 `NOTIFICATION-001`·행 불변; A news=true 시드·동의 없음에서 `{news:{mealTime:true}}` → 200 `mealTime=true`(US2-5), (6) 동의 v1 열림 + `{news:{consent:true, 2, 2}}` → v1 두 건 닫히고 v2 두 건 열림; 같은 버전 재요청은 원장 불변(US2-6), (7) `{news:{consent:true, privacyConsentVersion:2}}`(수신 버전 누락) → 400 `COMMON-002`·원장·기기값 불변(US2-7), (8) 동의 없는 회원 `{news:{consent:false}}` → 200 무변화(US2-8), (9) `{news:{consent:true, 2, 2, enabled:true, mealTime:true}}` 한 요청 → 동의 열림·news=true·mealTime=true(US2-9), (10) A news=true·mealTime=true 시드 + `{news:{enabled:false}}` → GET `mealTime=false`(표시값, 저장값은 true 유지 — `settingRows` 로 확인). **Red 확인**: `./gradlew :api:test` — 현재 `updateDeviceSettings` 가 `news` 를 무시해 실패.

### Implementation for User Story 2

- [x] T015 [US2] `api/src/main/kotlin/com/kbap/api/notification/NotificationService.kt` — `updateDeviceSettings` 에 `request.news` 분기 추가(처리 순서 activity → consent → enabled → mealTime): `consent == true` → `consentService.grantForMember(memberId, installationId, mapOf(MARKETING_PRIVACY to privacyConsentVersion!!, MARKETING_RECEIVE to receiveConsentVersion!!), now)`; `consent == false` → `consentService.revokeForMember(memberId, now)`; `enabled != null` → `deviceSettingOf(...).updateNews(enabled)`; `mealTime != null` → true 이면 `deviceSettingOf(...).news` 가 false 일 때 `BusinessException(ErrorCode.MARKETING_CONSENT_REQUIRED)`, 통과하면 `updateMealTime(mealTime)`. 동의 유효 판정·기기 목록 조회·마지막 기기 규칙은 **넣지 않는다**. 새 주입·새 클래스 없음.
- [x] T016 [US2] `api/src/main/kotlin/com/kbap/api/notification/NotificationApi.kt` — `updateDeviceSettings` 의 `description` 에 `consent`(회원 동의 켜기/끄기 — 기기값 불변)·`enabled`(이 기기 수신 — 원장 불변)·처리 순서·mealTime 선행 조건(contracts 변경 요약 표 그대로)과 400 `NOTIFICATION-001` 조건("이 기기 소식 꺼짐")·`COMMON-002` 조건("consent true 인데 두 버전 누락")을 반영한다.
- [x] T017 [US2] Green 확인: `./gradlew :api:test` 전부 통과. 커밋.

**Checkpoint**: 동의 원장(회원)과 기기 토글의 두 층이 서로 독립으로 동작한다.

---

## Phase 5: User Story 3 — 발송은 기기 단위로 대상을 고른다 (Priority: P1)

**Goal**: `PushTargetResolver` 가 (회원, 기기) 행으로 토글을 판정한다. 행 없는 기기는 제외. `SCAN_SUGGESTION`·`NEWS` 는 `news` 토글 + 동의 버전 ≥ 2.

**Independent Test**: 기기 A 만 활동 켜진 회원에게 `HELPFUL` 발송 → A 만 반환. 동의 v1 회원의 news 켜진 기기는 `NEWS` 대상에서 제외.

### Tests for User Story 3 (Red 먼저) ⚠️

- [x] T018 [P] [US3] `common/src/test/kotlin/com/kbap/common/domain/notification/PushTargetResolverTest.kt` — `setting(memberId, activity, mealTime)` 헬퍼를 `setting(installationId: String, memberId, activity = false, mealTime = false, news = false)` 로 바꾸고(`NotificationSetting(memberId, installationId, activity, mealTime, news)` 저장), `device()` 가 만든 `installationId` 를 돌려받아 쓰도록 기존 시나리오를 기기 단위로 옮긴다. 추가 시나리오: (1) 같은 회원의 기기 A(activity=true)·B(activity=false) 에 `HELPFUL` → A 만(US3-1), (2) 기기 A news=true + 동의 v1 두 건 → `NEWS`·`SCAN_SUGGESTION` 제외, v2 면 포함(US3-2·결정 3), (3) 행 없는 기기(토큰만 등록) → 모든 유형 제외(US3-3), (4) 회원 NULL 행(`NotificationSetting.defaultFor(memberId).apply { updateActivity(true) }`)만 있고 기기 행 없음 → 제외(새 계약은 NULL 행을 읽지 않음), (5) `MEAL_TIME` 은 기기 mealTime=true + 동의 v2 필요(기존 시나리오 유지), (6) news 토글 꺼진 기기는 동의가 있어도 `NEWS` 제외. **Red 확인**: `./gradlew :common:test`.

### Implementation for User Story 3

- [x] T019 [US3] `common/src/main/kotlin/com/kbap/common/domain/notification/PushTargetResolver.kt` — `settings` 를 `settingRepository.findByMemberIdIn(memberIds).filter { it.installationId != null }.associateBy { it.memberId to it.installationId!! }` 로 키잉하고, `toggledOn(device: NotificationDevice)` 가 `settings[device.memberId!! to device.installationId]` 를 본다: `HELPFUL`·`REVIEW_REMINDER` → `activity`, `MEAL_TIME` → `mealTime`, `SCAN_SUGGESTION`·`NEWS` → `news`(`== true`). `allowed(device)` = `toggledOn(device) && (!type.marketing || marketingEnabled(device.memberId!!))`. 동의 조회·요구 버전 상수는 그대로.
- [x] T020 [US3] Green 확인: `./gradlew :common:test` 통과, 이어 `./gradlew :api:test` 로 관리자 테스트 발송(`AdminNotificationTestService`) 관련 테스트가 있으면 회귀 없음을 확인(기기 행이 없는 시드는 대상 0건이 되므로 해당 테스트가 회원 단위 시드에 기대고 있으면 기기 행 시드로 고친다 — 프로덕션 코드가 아니라 테스트 시드만). 커밋.

**Checkpoint**: 발송이 기기별 토글을 따른다.

---

## Phase 6: User Story 4 — 로그아웃·탈퇴 시 기기 설정의 운명이 정해진다 (Priority: P2)

**Goal**: 로그아웃은 설정 보존, 탈퇴는 기기 행 소프트 삭제(+기존 기기 연결 해제·동의 닫기), 토큰 등록은 행을 만들지 않는다.

**Independent Test**: 설정 변경 → 로그아웃 → 재로그인 → 2.1 GET 값 유지. 탈퇴 → `notification_setting` 기기 행 `status = 'DELETED'`.

### Tests for User Story 4 (Red 먼저) ⚠️

- [x] T021 [P] [US4] `api/src/test/kotlin/com/kbap/api/auth/AuthNotificationLinkTest.kt` 에 시나리오 추가(기존 헬퍼 `login`·`logout`·`withdraw` 재사용, 2.1 설정 PATCH/GET 헬퍼 추가): `given("기기별 설정과 로그아웃")` — 기기 A 로 `{activity:true}` PATCH(2.1) → 로그아웃(헤더 A) → 같은 기기로 재로그인 → 2.1 GET activity=true, 행 status ACTIVE(US4-1). `given("회원 탈퇴")` 의 기존 `when` 에 기기 설정 행 시드(A·B)를 더해 탈퇴 후 두 행 `status='DELETED'`(SELECT 로 확인 — `@SQLRestriction` 때문에 리포지토리로는 안 보인다), 기기 `member_id` null, 동의 `revoked_at` 기록을 함께 검증(US4-2). 회원의 NULL 행(구 계약)은 탈퇴 후에도 ACTIVE 그대로임을 같은 시나리오에서 확인(범위 밖 무변경).
- [x] T022 [P] [US4] `api/src/test/kotlin/com/kbap/api/notification/NotificationTokenControllerTest.kt` 에 `when("새 기기가 토큰만 등록하면") then("설정 행이 생기지 않고 2.1 조회는 전부 꺼짐이다")` 추가 — `PUT /api/notifications/tokens`(1.1) 후 `notification_setting` 에 그 (회원, 기기) 행 없음 + 2.1 GET 전부 false(US4-3·FR-011). **Red 확인**: `./gradlew :api:test` — 탈퇴 시나리오만 실패해야 한다(로그아웃·토큰 시나리오는 현행 코드로도 통과할 수 있다 — 그 경우 회귀 고정 테스트로 유지하고 Red 는 탈퇴 케이스로 확인한다).

### Implementation for User Story 4

- [x] T023 [US4] `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenService.kt` — 생성자에 `NotificationSettingJpaRepository` 주입, `closeOnWithdraw(memberId)` 에 `settingRepository.findByMemberIdAndInstallationIdIsNotNull(memberId).forEach { it.delete() }` 추가(dirty checking, `save()` 호출 없음). `unlinkOnLogout`·`registerToken`·`linkOnLogin` 은 손대지 않는다.
- [x] T024 [US4] Green 확인: `./gradlew :api:test` 전부 통과. 커밋.

**Checkpoint**: 모든 스토리가 독립적으로 동작한다.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T025 전체 빌드 `./gradlew build`(ArchUnit 포함) 통과 확인. quickstart.md 의 "완료 조건 체크" 를 훑어 빠진 항목이 없는지 본다. 필요 시 로컬 bootRun 으로 Swagger 그룹 문서 `/v3/api-docs/2.1` 에 새 오퍼레이션 두 개가 실리는지 확인.
- [x] T026 [P] 지식 위키 갱신(`update-agenthub` 스킬): `../kbap-agenthub/wiki/push-notification-marketing-consent.md` 의 "KB-544 예고" 절을 확정 절로 바꾼다 — 스키마(같은 테이블 확장·고유키 교체·NULL 행은 구 계약)·버전 2.1·`news.enabled` 계산(기기 토글 AND 동의 두 종류 버전 ≥ 2)·마지막 연결 기기 규칙·발송 키잉·배포 직후 기기 행 부재로 발송 대상 0건인 과도기·후속(구 계약 폐기 시 NULL 행 삭제·NOT NULL 승격). `INDEX.md` 의 해당 줄 갱신 후 허브에서 커밋.
- [ ] T027 [P] FE 공유·후속 등록(사용자 수행 — 코드 없음): KB-497 에 "2.1 + `X-Installation-Id` 필수 + 값은 기기별 + `enabled` 계산 규칙" 코멘트, 구 계약 폐기 후속(NULL 행 삭제·`installation_id NOT NULL`·구 메서드 삭제) Jira 태스크 생성 후 KB-544 코멘트에 링크(`create-jira-task` 스킬).
- [ ] T028 `open-draft-pr-to-develop` 스킬로 develop 대상 draft PR 생성. PR 본문에 plan.md 의 "Spec 과 다른 점" 표(구 고유키 제거·2.1 가정)와 과도기 발송 영향을 명시한다.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 없음.
- **Foundational (Phase 2)**: T002(Red) → T003·T004(엔티티·리포지토리) → T005(구 경로 컴파일) → T006(마이그레이션) → T007(Green). **모든 스토리를 막는다.**
- **US1 (Phase 3)**: Phase 2 완료 후. T008 → T009·T010 → T011·T012 → T013.
- **US2 (Phase 4)**: US1 의 `updateDeviceSettings`·`assembleDevice`(T010) 위에 얹는다 → US1 뒤.
- **US3 (Phase 5)**: Phase 2 만 필요. US1·US2 와 파일이 겹치지 않아 **병행 가능**.
- **US4 (Phase 6)**: Phase 2 + US1 의 2.1 GET/PATCH(테스트가 씀). US2·US3 와 병행 가능.
- **Polish (Phase 7)**: 전 스토리 완료 후. T026·T027 병행.

### Within Each User Story

- 테스트 먼저 작성·Red 확인 → 구현 → Green → 리팩터 → 커밋.
- 서비스(`NotificationService`) → 컨트롤러 → Swagger 순.

### Parallel Opportunities

- Phase 2 안: T003·T004 는 다른 파일이지만 T002 테스트가 둘 다 필요하므로 연속 처리(한 사람이 순서대로).
- Phase 2 뒤: US3(T018~T020, `:common` 파일)와 US1(T008~T013, `:api` 파일)은 완전히 분리 — 병행.
- US4 테스트 T021·T022 은 서로 다른 파일 — 병행.
- Polish T026·T027 병행.

---

## Parallel Example: Phase 2 이후

```bash
# 세션 A — US1 (api)
Task: "T008 NotificationSettingControllerTest 2.1 조회·토글 블록 (Red)"
Task: "T009 DeviceNotificationSettingsUpdateRequest DTO"
Task: "T010 NotificationService.getDeviceSettings/updateDeviceSettings"
# 세션 B — US3 (common)
Task: "T018 PushTargetResolverTest 기기 단위 (Red)"
Task: "T019 PushTargetResolver (memberId, installationId) 키잉"
```

---

## Implementation Strategy

### MVP First (Phase 2 + US1)

1. Phase 2 로 스키마·리포지토리를 기기 단위로 바꾸고 구 계약 무변경을 증명한다(T007).
2. US1 로 2.1 GET/PATCH(활동 토글)를 연다 → FE 가 기기별 화면 개발을 시작할 수 있는 최소 계약.
3. **STOP and VALIDATE**: `:api:test` 전부 통과, 1.1 테스트 무변경.

### Incremental Delivery

1. + US2 → 소식/동의 두 층 분리 → 계약 완성(FE 공유 시점).
2. + US3 → 발송이 기기 단위. 이 시점부터 "기기 행 없는 회원은 발송 0건" 과도기가 시작되므로 스케줄 발송 도입 전 FE 릴리스 선행을 위키에 명시.
3. + US4 → 생명주기 마감.
4. Polish → 문서·PR.

---

## Notes

- 마이그레이션 버전은 **파일 생성 시각**의 `Vyyyy.MM.dd.HH.mm.ss__` — 정수 버전 금지, 기존 파일 수정 금지.
- 테스트 시드 SQL(`seedSetting`)은 `installation_id` 를 생략하면 NULL(구 계약 행)이고 `news` 는 DEFAULT FALSE — 기존 시드 문장은 그대로 동작한다.
- `@SQLRestriction("status = 'ACTIVE'")` 때문에 소프트 삭제 검증은 JDBC SELECT 로 한다.
- 컨트롤러에서 리포지토리를 부르지 않는다. 서비스는 기능 단위 하나(`NotificationService`) — 버전별·Query/Command 분리 클래스 금지.
- 헤더 값 검증은 기존 `ApiHeaders.validInstallationId` 하나만 쓴다(중복 검증 로직 금지).
