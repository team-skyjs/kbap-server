# Tasks: 푸시 토큰 등록 API 와 로그인·로그아웃·탈퇴 시 기기-회원 연결

**Input**: Design documents from `specs/kb-465-push-token-api/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/notification-tokens.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 각 스토리는 `:api` 통합 테스트(`@IntegrationTest` + MockMvc, BehaviorSpec, given/when/then 한국어)를 먼저 써서 Red(404·단정 실패)를 확인한 뒤 구현으로 Green 을 만든다. 실행은 `./gradlew :api:test`(Kotest 는 `--tests` 필터 무시 — 모듈 전체). 상태 검증은 `DataSource` JDBC 직접 SELECT, 회원 로그인은 `FakeSocialTokenVerifier`(`AuthControllerTest` 방식), 전체 정리는 `beforeSpec` 의 `TestTables.clearAll(dataSource)`.

**멱등성 (PUT 계약 — 사용자 지시 2026-09-07)**: `PUT /api/notifications/tokens` 는 같은 요청을 몇 번 보내도 결과 상태가 같아야 한다. 구현 규칙 — 기기 행은 `findByInstallationId` 로 찾아 **갱신**(신규일 때만 `save`), 동의 원장은 **같은 버전 열린 행이 있으면 삽입하지 않고**, off 는 열린 행이 없으면 무처리, 회원 `linkMember` 는 같은 회원이면 값 불변. 각 스토리 테스트에 "같은 요청을 2회 보내면 행 수·컬럼 값이 1회와 동일" 단정을 반드시 포함한다. 로그인·로그아웃·탈퇴의 기기 처리도 재실행에 안전해야 한다(이미 연결/해제/철회된 상태에서 다시 실행해도 예외·변화 없음).

**버전 게이트 (사용자 지시 2026-09-07, research R11)**: 이 기능의 계약은 전부 `X-API-Version 1.1` 이상이다. 토큰 API 는 `version = "1.1+"` 매핑만 두고, `AuthController` 는 **기존 1.0 매핑(`login`·`logout`·`withdraw`)을 한 글자도 바꾸지 않은 채** 같은 경로에 `version = "1.1+"` 매핑 3개를 추가한다. `AuthService` 는 기본 인자(`installationId = null`·`releaseDevices = false`)로 확장해 1.0 호출부가 그대로 컴파일·동작하게 한다. MockMvc 기본 헤더가 1.0 이므로 새 테스트는 `X-API-Version: 1.1` 을 명시하고, 기존 `AuthControllerTest` 는 **무수정 Green** 이어야 한다(1.0 무영향의 회귀 증거).

**이름 (사용자 지시 2026-09-07)**: 패키지 `com.kbap.api.notification`, 클래스 접두 `NotificationToken*`, URL `/api/notifications/tokens`. `push` 는 어디에도 쓰지 않는다(Jira 초안 `/api/push/tokens` 대체 — T018 에서 Jira 본문 갱신).

**Organization**: US1(토큰 upsert + 회원 연결) → US2(게스트 광고성 동의) → US3(인증 흐름 연동). US1·US2 는 같은 엔드포인트·같은 테스트 클래스에 쌓이고, US3 는 별도 테스트 클래스·`AuthService` 확장이라 US1 완료 후 US2 와 병렬 가능하다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 다른 파일·미완료 태스크 의존 없음 → 병렬 가능
- **[Story]**: US1 토큰 등록·갱신 / US2 게스트 광고성 동의 / US3 로그인·로그아웃·탈퇴 연동

## Path Conventions

- 기능 패키지: `api/src/main/kotlin/com/kbap/api/notification/`
- 공통 상수: `api/src/main/kotlin/com/kbap/api/core/`
- 인증: `api/src/main/kotlin/com/kbap/api/auth/`
- 테스트: `api/src/test/kotlin/com/kbap/api/{notification,auth}/`
- 도메인(변경 없음, 소비만): `common/src/main/kotlin/com/kbap/common/domain/notification/`

---

## Phase 1: Setup (공용 상수)

**Purpose**: notification·auth 두 기능 패키지가 공유하는 헤더명 단일 출처(research R3).

- [x] T001 `ApiHeaders` object 작성 — `api/src/main/kotlin/com/kbap/api/core/ApiHeaders.kt`. `object ApiHeaders { const val INSTALLATION_ID = "X-Installation-Id" }`. 주석 없음

---

## Phase 2: Foundational

없음 — 엔티티·리포지토리·마이그레이션은 KB-464 가 전부 제공했고(`NotificationDevice`·`NotificationConsent`·두 리포지토리), 새 ErrorCode·seam·config 도 필요 없다. 서비스 클래스는 US1 에서 만든다.

**Checkpoint**: `./gradlew :api:compileKotlin` 통과.

---

## Phase 3: User Story 1 - 기기가 자기 푸시 토큰을 서버에 등록·갱신한다 (Priority: P1) 🎯 MVP

**Goal**: `PUT /api/notifications/tokens` — `X-Installation-Id` 기준 upsert(신규 `register`/기존 `renew`, 무효 스탬프 해제), 회원 요청이면 `linkMember`, 검증 실패·헤더 누락 400. 게스트 `PUT` 은 기존 회원 연결을 건드리지 않는다(data-model 상태 전이).

**Independent Test**: 게스트 등록 → 같은 기기로 다른 토큰 등록 → 행 1건·토큰 교체 → 회원 인증 붙여 등록 → 같은 행에 member_id. 같은 요청 2회 → 상태 동일.

### Tests for User Story 1 (Test-First — 먼저 작성, 실패 확인) ⚠️

- [x] T002 [US1] `NotificationTokenControllerTest`(BehaviorSpec, `@IntegrationTest`, `SpringExtension`) 작성 — `api/src/test/kotlin/com/kbap/api/notification/NotificationTokenControllerTest.kt`. 헬퍼: `put(installationId: String?, memberId: Long?, body: Map<String, Any?>)` — `mockMvc.put("/api/notifications/tokens")` 에 `X-API-Version: 1.1`(기본 파라미터 `apiVersion = "1.1"`), 헤더가 null 아니면 `X-Installation-Id`, memberId 가 null 아니면 `Authorization: Bearer ${tokenIssuer.issueAccessToken(memberId, MemberRole.USER)}`, JSON 본문; `device(installationId)` — JDBC 로 `notification_device` 의 `member_id`·`expo_token`·`platform`·`lang`·`token_invalid_at` 조회; `countDevices()`. 회원 행은 `INSERT INTO member` 시드(다른 테스트 시드 참고 — FK 때문에 실존 회원 필요). `beforeSpec { TestTables.clearAll(dataSource) }`. given("기기 토큰 등록"): ① 모르는 기기·게스트 → 200, 행 1건, member_id null, 토큰·플랫폼·언어 저장 (AS1) ② 같은 기기로 다른 토큰·`android`·`ko` → 행 수 그대로, 세 컬럼 교체 (AS2) ③ `UPDATE notification_device SET token_invalid_at = NOW(6)` 심은 뒤 재등록 → `token_invalid_at` null (AS3) ④ 게스트로 등록된 기기에 회원 m 인증 붙여 등록 → 행 수 그대로, member_id = m (AS4) ⑤ 회원 m 연결된 기기에 게스트로 재등록 → member_id 유지(m) ⑥ **멱등** — 동일 요청(게스트·회원 각각) 2회 연속 → `countDevices()`·모든 컬럼이 1회 후와 동일. given("잘못된 등록 요청"): ⑦ 헤더 없음 → 400·`code == "COMMON-002"`·행 0 (AS5) ⑧ 헤더 빈 문자열 → 400 ⑨ `token` 공백 → 400 ⑩ `platform: "web"` → 400 ⑪ `lang` 공백 → 400 ⑫ 위조 Bearer(`Authorization: Bearer garbage`) → 401 ⑬ 대소문자 — `platform: "IOS"` 도 200 ⑭ **`apiVersion = "1.0"` 으로 유효한 요청 → 400·행 0**(FR-016 — 토큰 API 는 1.1 이상 전용) ⑮ `X-API-Version` 누락 → 400. 실행 `./gradlew :api:test` → 404 로 Red 확인

### Implementation for User Story 1

- [x] T003 [P] [US1] `NotificationTokenRegisterRequest` 작성 — `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenRegisterRequest.kt`. `@Schema` 붙은 data class: `token: String?`(`@field:NotBlank`·`@field:Size(max = 255)`), `platform: String?`(`@field:NotBlank`·`@field:Pattern(regexp = "(?i)ios|android")`, example `ios`), `lang: String?`(`@field:NotBlank`·`@field:Size(max = 10)`). `settings` 는 US2(T008)에서 추가. `fun platform(): DevicePlatform = DevicePlatform.valueOf(platform!!.uppercase())` 는 두지 말고 컨트롤러에서 변환(research R8)
- [x] T004 [P] [US1] `NotificationTokenService` 작성 — `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenService.kt`. `@Service`, 생성자 주입 `NotificationDeviceJpaRepository`·`NotificationConsentJpaRepository`. `@Transactional fun registerToken(installationId: String, memberId: Long?, token: String, platform: DevicePlatform, lang: String)`: `findByInstallationId` 가 null 이면 `save(NotificationDevice.register(installationId, token, platform, lang, memberId))`, 있으면 `renew(token, platform, lang)` 후 `memberId != null` 이면 `linkMember(memberId)`(dirty checking — `save` 호출 금지). 게스트 요청은 기존 memberId 를 건드리지 않는다. 동의 처리 파라미터는 US2 에서 추가
- [x] T005 [P] [US1] `NotificationTokenApi` 인터페이스 작성 — `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenApi.kt`. `@Tag(name = "푸시", description = "기기 푸시 토큰 등록 API")`, `@Operation(summary = "푸시 토큰 등록·갱신 — X-API-Version 1.1 이상", description = ...)` 에 upsert·멱등·게스트/회원 동작·헤더 필수·1.1 이상 전용을 서술(contracts/notification-tokens.md 의 조합 표 내용), `@Parameter(name = "X-Installation-Id", in = ParameterIn.HEADER, required = true, description = "앱 설치 UUID(36자 이하) — 기기 기록의 유일 키")`, `@ApiResponses` 200/400/401. `@SecurityRequirement` 는 달지 않는다(선택 인증). 파라미터는 애너테이션 없이 타입만: `fun register(installationId: String, memberId: Long?, request: NotificationTokenRegisterRequest): ResponseEntity<BaseResponse<Unit>>`
- [x] T006 [US1] `NotificationTokenController` 작성 — `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenController.kt` (T001·T003·T004·T005 뒤). `@RestController @RequestMapping(ApiPaths.API + "/notifications")`, `@PutMapping("/tokens", version = "1.1+") override fun register(@RequestHeader(ApiHeaders.INSTALLATION_ID) @NotBlank @Size(max = 36) installationId: String, @AuthMemberIdOrNull memberId: Long?, @Valid @RequestBody request: NotificationTokenRegisterRequest)`. `DevicePlatform.valueOf(request.platform!!.uppercase())` 로 확정 후 `notificationTokenService.registerToken(...)`, `ResponseEntity.ok(BaseResponse.ok(Unit))`. `WebConfig` JWT 보호 경로에는 **등록하지 않는다**(research R2). `./gradlew :api:test` Green 확인 — 헤더 누락 400 이 `handleUnexpected` 의 `ErrorResponse` 분기로 나오는지, 빈 헤더가 `HandlerMethodValidationException` 400 인지, **1.0 요청이 400 인지**(404 가 나오면 Spring 버전 불일치 예외 처리 경로를 확인해 research R11 에 기록) 로그로 확인

**Checkpoint**: US1 시나리오 전부 Green. 앱 팀이 Swagger 로 토큰 전송 연동을 시작할 수 있는 상태(SC-001 일부).

---

## Phase 4: User Story 2 - 게스트가 토큰 등록과 함께 광고성 수신 동의를 켜고 끈다 (Priority: P2)

**Goal**: 게스트 요청의 `settings.marketing`(+`marketingConsentVersion`)을 `notification_consent` 원장에 반영 — on 은 같은 버전 열린 행 없을 때만 삽입(다른 버전 열린 행은 철회), off 는 열린 게스트 행 전부 철회, 회원 요청은 무시. on 인데 버전 없음·양의 정수 아님 → 400.

**Independent Test**: 게스트 on(v1) → 열린 행 1 → 같은 요청 2회 → 변화 없음(멱등) → on(v2) → v1 닫히고 v2 열림 → off → 열린 행 0, 행 수 2 유지 → 회원 인증으로 on → 원장 무변화.

### Tests for User Story 2 (Test-First — 먼저 작성, 실패 확인) ⚠️

- [ ] T007 [US2] `NotificationTokenControllerTest` 에 given("게스트 광고성 동의") 추가 — `api/src/test/kotlin/com/kbap/api/notification/NotificationTokenControllerTest.kt`. 헬퍼 `consents(installationId)` — JDBC 로 `notification_consent` 의 `member_id`·`consent_version`·`granted_at`·`revoked_at` 목록. ① 동의 기록 없는 기기, on + version 1 → 열린 행 1(버전 1, granted_at 있음, revoked_at null, member_id null) (AS1) ② 같은 요청(on, v1) 다시 → 행 수·granted_at 동일 (AS2, 멱등) ③ on, v2 → v1 행 revoked_at 찍힘, v2 열린 행 신규 (AS3) ④ off → 열린 행 0, 총 행 수 불변, 두 행 모두 revoked_at 있음 (AS4) ⑤ 열린 행 없는 상태에서 off → 200, 변화 없음 (AS5) ⑥ off 2회 → 동일(멱등) ⑦ 회원 m 인증 + on v1 → 200, 원장 행 수 불변 (AS6) ⑧ on 인데 version 없음 → 400 (AS7) ⑨ version 0·-1 → 400 ⑩ version 문자열 `"v1"` → 400 ⑪ `settings` 없음 → 200, 원장 무변화 ⑫ `settings` 있으나 `marketing` 없음 → 400. `./gradlew :api:test` → ①·③·④ 단정 실패로 Red 확인(settings 가 무시되므로)

### Implementation for User Story 2

- [ ] T008 [US2] `NotificationTokenRegisterRequest` 에 `settings` 추가 — `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenRegisterRequest.kt`. 같은 파일에 `data class MarketingSettingsRequest(val marketing: Boolean? /* @field:NotNull */, val marketingConsentVersion: Int? /* @field:Positive */)` + `@get:AssertTrue(message = "marketing 이 true 면 marketingConsentVersion 이 필요합니다") val versionPresentWhenOptedIn: Boolean get() = marketing != true || marketingConsentVersion != null`(`OrderCreateRequest` 선례, research R9). 루트에 `@field:Valid val settings: MarketingSettingsRequest? = null`. (위 `/* */` 는 설명이며 코드에 주석을 쓰지 않는다)
- [ ] T009 [US2] `NotificationTokenService.registerToken` 에 동의 처리 추가 — `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenService.kt`. 시그니처에 `settings: MarketingSettingsRequest?` 추가. `memberId == null && settings != null` 일 때만: `val now = LocalDateTime.now()`, `open = findOpenGuestByInstallationId(installationId)`; `settings.marketing == true` 면 `val v = settings.marketingConsentVersion!!`, `open.filter { it.consentVersion != v }.forEach { it.revoke(now) }`, `if (open.none { it.consentVersion == v }) save(NotificationConsent.grantForInstallation(installationId, v, now))`; `false` 면 `open.forEach { it.revoke(now) }`. 회원 요청(`memberId != null`)은 settings 무시(FR-007). 멱등: 같은 버전 재요청은 삽입·철회 모두 없음, off 재요청은 open 이 비어 무처리
- [ ] T010 [US2] `NotificationTokenController.register` 가 `request.settings` 를 서비스에 전달, `NotificationTokenApi` description 에 게스트 동의 규칙(on/off/버전 교체/회원 무시)과 400 조건 추가 — `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenController.kt`, `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenApi.kt`. `./gradlew :api:test` Green 확인

**Checkpoint**: US1 + US2 Green. 게스트 동의 원장이 API 로 채워진다.

---

## Phase 5: User Story 3 - 로그인·로그아웃·탈퇴가 기기 연결과 동의를 이어받거나 정리한다 (Priority: P2)

**Goal**: 로그인 — 헤더 기기가 등록돼 있으면 `linkMember` + 게스트 동의 인수(`claim`, 회원 열린 동의 없을 때)/철회(있을 때); 로그아웃 — `unlinkMember` 만; 탈퇴 — 회원의 모든 기기 `unlinkMember` + `closeOpenByMemberId`. 헤더 없음·미등록 기기여도 인증은 기존과 동일하게 성공.

**Independent Test**: 게스트 기기에 토큰·동의 심기(US1·US2 의 PUT 사용) → 헤더 실어 로그인 → member_id 연결, 동의 행의 member_id 채워지고 granted_at 유지 → 로그아웃 → member_id null, 원장 불변 → 재로그인 → 탈퇴 → 모든 기기 null + 열린 동의 0, 행 보존. 기기 행은 시종 1건(SC-002).

### Tests for User Story 3 (Test-First — 먼저 작성, 실패 확인) ⚠️

- [ ] T011 [US3] `AuthNotificationLinkTest`(BehaviorSpec, `@IntegrationTest`) 작성 — `api/src/test/kotlin/com/kbap/api/auth/AuthNotificationLinkTest.kt`. 헬퍼: `login(sub: String, installationId: String?, apiVersion: String = "1.1")` — `POST /api/auth/login` (`X-API-Version: $apiVersion`, 헤더 선택, 본문 `idToken = sub`; `FakeSocialTokenVerifier` 가 sub 별 회원을 만든다 — 기존 `AuthControllerTest` 참고), 응답에서 `accessToken`·`refreshToken`·`memberId` 추출; `logout(refreshToken, installationId?, apiVersion = "1.1")`; `withdraw(accessToken, apiVersion = "1.1")`; `registerGuest(installationId, marketing: Boolean?, version: Int?)`·`registerMember(installationId, accessToken)` — `PUT /api/notifications/tokens`; JDBC `deviceMemberId(installationId)`, `countDevices()`, `consents(installationId)`, `openConsentsOfMember(memberId)`, `insertOpenMemberConsent(memberId, version)`(회원 동의 시드 — API 가 없으므로 INSERT). `beforeSpec { TestTables.clearAll(dataSource); verifier.reset() }`. given("게스트 기기에서 로그인"): ① 게스트 등록 + on v1 → 로그인(헤더) → device member_id = m, 동의 행 member_id = m, granted_at 이전과 동일, revoked_at null (AS1) ② 게스트 동의 행 2건(같은 기기, INSERT 로 심기) → 로그인 → 둘 다 member_id = m (Edge) ③ 회원 m 에 열린 동의 시드 + 게스트 on v1 → 로그인 → 게스트 행 revoked_at 찍힘·member_id null 유지, 회원 행 그대로 (AS2) ④ 미등록 기기 헤더로 로그인 → 200, `countDevices()` 0, 원장 0 (AS3) ⑤ 헤더 없이 로그인 → 200, 등록된 기기 member_id 변화 없음 (AS6) ⑥ 같은 헤더로 로그인 2회 → 상태 동일(멱등). given("다른 회원이 연결된 기기에서 로그인"): ⑦ A 로그인(연결) 후 B 로그인 → member_id = B (Edge). given("회원 기기에서 로그아웃"): ⑧ 로그인(연결) + 회원 동의 시드 → 로그아웃(헤더) → member_id null, 원장 행·revoked_at 전부 불변 (AS4) ⑨ 헤더 없이 로그아웃 → 200, 연결 유지 ⑩ 로그아웃 2회 → 동일. given("회원 탈퇴"): ⑪ 기기 2대 회원 등록 + 열린 회원 동의 2건 시드 → 탈퇴 → 두 기기 member_id null, 열린 동의 0, 원장 행 수 불변 (AS5). given("설치→등록→로그인→로그아웃→재로그인"): ⑫ 연속 수행 후 `countDevices()` == 1, 단계별 member_id (null → m → null → m) (SC-002). given("1.0 인증 API 무영향"): ⑬ 게스트 등록 + on v1 후 `apiVersion = "1.0"` + 헤더로 로그인 → 200, member_id null 유지, 동의 행 member_id null 유지 ⑭ 1.1 로그인으로 연결된 기기에서 1.0 로그아웃(헤더) → 200, member_id 유지(m) ⑮ 1.1 로그인 연결 + 회원 동의 시드 후 1.0 탈퇴 → 200·회원 탈퇴됨, member_id 유지, 열린 동의 유지 (FR-016). `./gradlew :api:test` → ①·⑧·⑪ 단정 실패로 Red 확인(⑬~⑮ 는 처음부터 Green — 회귀 방지용)

### Implementation for User Story 3

- [ ] T012 [US3] `NotificationTokenService` 에 인증 연동 메서드 3개 추가 — `api/src/main/kotlin/com/kbap/api/notification/NotificationTokenService.kt`. `@Transactional fun linkOnLogin(installationId: String, memberId: Long)`: `findByInstallationId(installationId)?.linkMember(memberId)`; `val guest = findOpenGuestByInstallationId(installationId)`; 비어 있지 않으면 `if (findOpenByMemberId(memberId).isEmpty()) guest.forEach { it.claim(memberId) } else { val now = LocalDateTime.now(); guest.forEach { it.revoke(now) } }` (research R6 — 기기 연결과 동의 처리는 독립). `@Transactional fun unlinkOnLogout(installationId: String)`: `findByInstallationId(installationId)?.unlinkMember()`. `@Transactional fun closeOnWithdraw(memberId: Long)`: `findByMemberId(memberId).forEach { it.unlinkMember() }`; `closeOpenByMemberId(memberId, LocalDateTime.now())`. 세 메서드 모두 재실행 안전(이미 처리된 상태에서 예외·변화 없음)
- [ ] T013 [US3] `AuthService` 기본 인자 확장 — `api/src/main/kotlin/com/kbap/api/auth/AuthService.kt`. 생성자에 `notificationTokenService: NotificationTokenService` 주입. `login(idToken: String, installationId: String? = null)`: refresh 저장 뒤 `installationId?.let { notificationTokenService.linkOnLogin(it, memberId) }`. `logout(refreshToken: String?, installationId: String? = null)`: refresh 폐기 조기 return 과 **무관하게** `installationId?.let { notificationTokenService.unlinkOnLogout(it) }` 실행(refresh 가 없어도 기기 해제는 해야 함 — 메서드 첫 줄로 이동). `withdraw(memberId: Long, releaseDevices: Boolean = false)`: `deleteSocialAccount` → `if (releaseDevices) notificationTokenService.closeOnWithdraw(memberId)` → `memberService.withdraw(memberId)` 순서(research R5). **기본 인자 경로 = 종전 1.0 동작** — 기존 호출부 `login(idToken)`·`logout(refreshToken)`·`withdraw(memberId)` 는 수정하지 않는다. 기존 주석 한 줄은 만나는 김에 제거(주석 금지 규약)
- [ ] T014 [US3] `AuthController`·`AuthApi` 에 `1.1+` 매핑 3개 추가(1.0 매핑·문서 불변) — `api/src/main/kotlin/com/kbap/api/auth/AuthController.kt`, `api/src/main/kotlin/com/kbap/api/auth/AuthApi.kt`. 기존 `login`·`logout`·`withdraw` 메서드는 **손대지 않고**, 그 아래에 `MemberController` 온보딩 `1.0`/`1.1+` 선례대로 추가: `@PostMapping("/login", version = "1.1+") override fun loginWithDevice(@Valid @RequestBody request: LoginRequest, @RequestHeader(ApiHeaders.INSTALLATION_ID, required = false) installationId: String?)` → `authService.login(request.idToken, installationId)`; `@PostMapping("/logout", version = "1.1+") override fun logoutWithDevice(@RequestBody(required = false) request: LogoutRequest?, @RequestHeader(ApiHeaders.INSTALLATION_ID, required = false) installationId: String?)` → `authService.logout(request?.refreshToken, installationId)`; `@PatchMapping("/withdraw", version = "1.1+") override fun withdrawWithDevices(@AuthMemberId memberId: Long)` → `authService.withdraw(memberId, releaseDevices = true)`. 응답 타입·본문은 1.0 과 동일. `AuthApi` 에 세 메서드 문서 추가(타입만, Spring 애너테이션 없음): `@Operation(summary = "소셜 로그인 — X-API-Version 1.1 이상(기기 연결)")` 등, login·logout 에 `@Parameter(name = "X-Installation-Id", in = ParameterIn.HEADER, required = false, description = "앱 설치 UUID — 있으면 이 기기를 로그인 회원에 연결(로그인)/해제(로그아웃)한다. 없어도 인증은 동일")`, 로그인 description 에 게스트 동의 인수 규칙, 탈퇴 description 에 "모든 기기 연결 해제 + 광고성 동의 전부 철회(기록 보존)". 기존 1.0 메서드 문서는 무수정. **기존 `AuthControllerTest`(1.0)는 무수정으로 Green 이어야 한다.** `./gradlew :api:test` Green 확인

**Checkpoint**: 세 스토리 전부 Green. 로그아웃 기기·탈퇴 회원이 발송 대상에서 빠진다(SC-004 는 KB-464 의 대상 조회 질의가 `member_id` 연결과 열린 동의를 보므로 자동 충족).

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T015 전체 빌드 `./gradlew build` — `ModuleBoundaryTest`(arch)가 `api.notification` 의존 방향·구 패키지 금지를 통과하는지, `ErrorCodeStatusTest` 등 무관 테스트 무회귀 확인
- [ ] T016 [P] quickstart.md 3절 실기동 — `SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun` 후 curl 2건(게스트 등록+동의, 헤더 누락 400)과 Swagger UI 그룹 `X-API-Version 1.1` 의 "푸시" 태그에 `PUT /api/notifications/tokens` 가 헤더·본문 스키마·`X-Installation-Id` 와 함께 노출되는지, 인증 태그의 login·logout 에 헤더가 보이는지 확인(SC-001). 그룹 `1.0` 에는 토큰 API 가 없고 인증 문서가 종전과 같은지 확인. curl 로 1.0 토큰 요청 400 확인
- [ ] T017 [P] 지식 위키 기록 — `../kbap-agenthub/wiki/notification-token-registration.md` 신설(또는 KB-464 가 만든 광고성 동의 페이지가 있으면 그 페이지에 절 추가): PUT 멱등 계약, 1.1 버전 게이트(1.0 인증 API 무영향·토큰 API 1.1 전용), 게스트/회원 조합별 동작, 로그인 인수/철회 분기, 탈퇴 순서(기기 정리 → 회원 마킹) 근거, JWT 필터 미등록 이유. `INDEX.md` 한 줄 추가 후 허브에서 커밋
- [ ] T018 커밋(작업 단위별 — 최소 US1/US2/US3/polish 4개) 후 `open-draft-pr-to-develop` 스킬로 draft PR, Jira KB-465 본문의 경로를 `/api/notifications/tokens` 로 정정하고 DoD 체크(PUT upsert·게스트 settings·로그인/로그아웃/탈퇴·헤더 누락 400 / dev 배포·FE 공유는 머지 후 — FE 에 경로 변경 통보 포함)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 즉시 시작
- **Foundational (Phase 2)**: 태스크 없음
- **US1 (Phase 3)**: T001 뒤. T002(테스트) → T003·T004·T005 병렬 → T006
- **US2 (Phase 4)**: US1 완료 뒤(같은 파일·같은 엔드포인트 확장). T007 → T008 → T009 → T010
- **US3 (Phase 5)**: US1 완료 뒤(테스트가 PUT 으로 기기를 심는다). **US2 와 병렬 가능** — 파일이 겹치지 않는다(`NotificationTokenService` 는 US2 가 `registerToken` 본문, US3 가 새 메서드 3개를 추가하므로 같은 파일이지만 다른 메서드 — 순차 권장, 병렬 시 머지 주의). T011 → T012 → T013 → T014
- **Polish (Phase 6)**: 전 스토리 완료 뒤. T016·T017 병렬

### Within Each User Story

- 테스트 태스크가 먼저이고 Red 확인이 완료 조건
- DTO·서비스·Api 인터페이스(다른 파일) 병렬 → 컨트롤러가 조합
- 스토리 완료 시 커밋

### Parallel Opportunities

- US1: T003·T004·T005 (파일 3개 독립)
- US2 ∥ US3: US1 뒤 동시 착수 가능(테스트 클래스·수정 대상이 다름)
- Polish: T016 ∥ T017

---

## Parallel Example: User Story 1

```bash
# T002 를 먼저 쓰고 ./gradlew :api:test 로 404 Red 확인 후:
Task: "NotificationTokenRegisterRequest 작성 — api/src/main/kotlin/com/kbap/api/notification/NotificationTokenRegisterRequest.kt"
Task: "NotificationTokenService.registerToken 작성 — api/src/main/kotlin/com/kbap/api/notification/NotificationTokenService.kt"
Task: "NotificationTokenApi 인터페이스 작성 — api/src/main/kotlin/com/kbap/api/notification/NotificationTokenApi.kt"
# 그 뒤 T006 컨트롤러로 조합 → Green
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001 → T002(Red) → T003~T006(Green)
2. **STOP and VALIDATE**: `NotificationTokenControllerTest` 의 "기기 토큰 등록"·"잘못된 등록 요청" Green, Swagger 노출
3. 이 상태로 dev 배포하면 앱 팀이 토큰 전송 연동을 시작할 수 있다(에픽 1순위 목표)

### Incremental Delivery

1. US1 → 토큰 저장 통로(MVP)
2. US2 → 게스트 광고성 동의 원장 채움
3. US3 → 인증 흐름 연동(로그아웃 기기·탈퇴 회원이 발송 대상에서 빠짐)
4. Polish → 전체 빌드·실기동·위키·PR

---

## Notes

- 기기 행은 어떤 태스크에서도 `delete()` 하지 않는다. 동의 원장 행은 어떤 태스크에서도 삭제하지 않는다.
- `save` 는 신규 삽입에만. 갱신은 dirty checking.
- `WebConfig` 보호 경로에 `/api/notifications/*` 를 넣지 않는다(넣으면 게스트가 401).
- 1.0 매핑·`AuthService` 기존 호출부·`AuthControllerTest` 는 수정하지 않는다. 새 매핑은 전부 `version = "1.1+"`, 새 테스트는 전부 `X-API-Version: 1.1`.
- 클래스 접두 `NotificationToken*`, 패키지 `api.notification`, URL `/api/notifications/tokens`. `push` 는 쓰지 않는다.
- Kotlin 소스 주석 금지. `AuthService` 의 기존 주석 한 줄은 T013 에서 제거.
- 테스트 `given`/`when`/`then` 설명은 한국어, BehaviorSpec 만.
