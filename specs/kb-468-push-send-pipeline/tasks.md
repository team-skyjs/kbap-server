# Tasks: Expo Push 발송 공용 파이프라인 — 100건 배치 전송·티켓 저장·언어별 렌더

**Input**: Design documents from `specs/kb-468-push-send-pipeline/`

**Prerequisites**: plan.md, research.md, data-model.md, contracts/push-data-contract.md, quickstart.md. spec.md 없음 — 스토리는 Jira KB-468 DoD 항목을 그대로 쓴다.

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 스토리마다 테스트 클래스를 먼저 쓰고 Red 를 확인한 뒤 구현한다. 새 Spring 컨텍스트 금지 — common 은 `CommonTestApp`(`@SpringBootTest + @Import(MySqlContainerConfig::class)`), api 는 `@IntegrationTest` 만.

**Organization**: 파이프라인은 prepare(도메인) → send(port) → record(도메인) 3단이라 스토리가 **독립 파일**로 갈린다. US1(어댑터)·US2(렌더러)는 서로 무관해 병렬 가능. US3(필터)→US4(dispatch)→US5(조립)→US6(관리자 발송) 순차.

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

- common: `common/src/main/kotlin/com/kbap/common/{port/push,infra/push,domain/notification}/`, 테스트 `common/src/test/kotlin/com/kbap/common/...` 미러
- api: `api/src/main/kotlin/com/kbap/api/{core/config,notification,admin}/`, 테스트 `api/src/test/kotlin/com/kbap/api/...`
- batch: `batch/src/main/kotlin/com/kbap/batch/config/`

---

## Phase 1: Setup

- [x] T001 `common/build.gradle.kts` `dependencies` 에 `"implementation"(libs.spring.web)` 추가(RestClient — spring-ai 전이 의존에 기대지 않는다). `./gradlew :common:compileKotlin` 통과.

---

## Phase 2: Foundational (여러 스토리가 공유하는 도메인 값)

- [x] T002 [P] `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationType.kt` — `MEAL_TIME` 추가(NOTICE 뒤). 각 항목에 `val marketingByDefault: Boolean` 생성자 프로퍼티: `SCAN_SUGGESTION` 만 true. (컬럼 VARCHAR(30) — 마이그레이션 없음.)
- [x] T003 [P] `common/src/main/kotlin/com/kbap/common/port/push/PushMessage.kt` — `data class PushMessage(val to: String, val title: String, val body: String, val data: Map<String, Any>)`. `PushTicket.kt` — `data class PushTicket(val ok: Boolean, val id: String? = null, val error: String? = null)` + `companion { fun ok(id: String); fun error(message: String) = PushTicket(false, error = message.take(255)) }`. `PushSender.kt` — `fun interface PushSender { fun send(messages: List<PushMessage>): List<PushTicket> }`. Spring·JPA import 금지(ArchUnit port 규칙).
- [x] T004 [P] `common/src/main/kotlin/com/kbap/common/domain/notification/PushRequest.kt` — 도메인 값 타입 5개(도메인은 port 타입을 참조할 수 없으므로 봉투·결과는 자체 타입): `data class PushRequest(val type: NotificationType, val memberIds: Collection<Long>, val args: Map<String, String> = emptyMap(), val data: Map<String, Any> = emptyMap(), val marketing: Boolean = type.marketingByDefault)`, `data class PushContent(val title: String, val body: String)`, `data class PushEnvelope(val to: String, val title: String, val body: String, val data: Map<String, Any>)`, `data class PreparedPush(val messages: List<PushEnvelope>, val dispatchIds: List<Long>) { fun isEmpty() = messages.isEmpty() }`(같은 인덱스 = 같은 기기), `data class PushOutcome(val ok: Boolean, val ticketId: String?, val error: String?)`, `data class PushDispatchResult(val sent: Int, val failed: Int)`. 글루(api/batch)가 `PushEnvelope → PushMessage`, `PushTicket → PushOutcome` 를 각각 한 줄로 매핑한다.

**Checkpoint**: `./gradlew :common:compileKotlin`. `./gradlew :api:test` 리포트에서 `ModuleBoundaryTest` Green(port 가 도메인만 참조, 도메인이 port 미참조).

---

## Phase 3: User Story 1 - Expo 어댑터가 100건 청크로 보내고 티켓을 순서대로 돌려준다 (Priority: P1) 🎯 MVP

**Goal**: `ExpoPushSender.send(250건)` → HTTP 3회(100/100/50), 티켓 250개 입력 순서, ok/error 매핑, 청크 실패는 그 청크만 error, Bearer 는 토큰 있을 때만.

**Independent Test**: `MockRestServiceServer` 만으로 검증 — DB·Spring 컨텍스트 불필요.

### Tests for User Story 1 ⚠️

- [x] T005 [US1] `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushSenderTest.kt` 생성 — BehaviorSpec. 픽스처 `fixture(accessToken: String = ""): Pair<ExpoPushSender, MockRestServiceServer>`: `val builder = RestClient.builder(); val server = MockRestServiceServer.bindTo(builder).build(); ExpoPushSender.create("https://expo.test", accessToken, builder) to server`(`FrankfurterExchangeRateClientTest` 선례). 시나리오: (1) 250건 → `server.expect(ExpectedCount.times(3), requestTo("https://expo.test/--/api/v2/push/send"))` + 각 응답 `data` 100/100/50 ok 티켓 JSON → 결과 크기 250, `[0].id=="t0"`, `[249].id=="t249"`, 요청 본문 `content().jsonPath("$.length()").value(100)` 첫 요청; (2) 응답 `[{"status":"ok","id":"a"},{"status":"error","message":"m","details":{"error":"DeviceNotRegistered"}}]` → `[1].ok==false`, `[1].error=="DeviceNotRegistered"`; `details` 없고 `message` 만 → `error=="m"`; (3) 150건, 두 번째 청크 `withServerError()` → 0..99 ok, 100..149 `ok==false` 이고 `error` 가 비어 있지 않음, 예외 미전파; (4) 본문 항목에 `sound=="default"`, `priority=="high"`, `channelId=="default"`, `data.type` 전달; (5) `accessToken="tok"` → `header("Authorization","Bearer tok")` 매칭, 빈 문자열 → `headerDoesNotExist("Authorization")`; (6) 빈 목록 → HTTP 0회, 빈 결과.
- [x] T006 [US1] `./gradlew :common:test` → 컴파일 실패(Red — `ExpoPushSender` 부재) 확인.

### Implementation for User Story 1

- [x] T007 [US1] `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushSender.kt` 생성 — `class ExpoPushSender internal constructor(private val restClient: RestClient) : PushSender`. `send`: `messages.chunked(100).flatMap { chunk -> sendChunk(chunk) }`. `sendChunk`: `restClient.post().uri("/--/api/v2/push/send").contentType(APPLICATION_JSON).accept(APPLICATION_JSON).body(chunk.map { ExpoMessage(it.to, it.title, it.body, it.data) }).retrieve().body(ExpoSendResponse::class.java)` → `response.data.map { it.toTicket() }`; 크기 불일치면 나머지를 `PushTicket.error("ticket count mismatch")` 로 채움. `catch (e: RestClientException)`·`catch (e: HttpMessageConversionException)` → `List(chunk.size) { PushTicket.error("${e::class.simpleName}: ${e.message}") }` + `log.warn`. 내부 DTO `internal data class ExpoMessage(to, title, body, data, val sound: String = "default", val priority: String = "high", val channelId: String = "default")`, `internal data class ExpoSendResponse(val data: List<ExpoTicket> = emptyList())`, `internal data class ExpoTicket(val status: String = "", val id: String? = null, val message: String? = null, val details: Map<String, Any>? = null) { fun toTicket() = if (status == "ok" && id != null) PushTicket.ok(id) else PushTicket.error((details?.get("error") as? String) ?: message ?: "unknown") }`. `companion`: `fun create(baseUrl: String, accessToken: String): ExpoPushSender` — `HttpClient.newBuilder().connectTimeout(2s)` + `JdkClientHttpRequestFactory.setReadTimeout(10s)` → `create(baseUrl, accessToken, RestClient.builder().requestFactory(rf))`; `internal fun create(baseUrl, accessToken, builder: RestClient.Builder)` — `builder.baseUrl(baseUrl).configureMessageConverters { it.disableDefaults().withJsonConverter(JacksonJsonHttpMessageConverter(JsonMapper.builder().addModule(kotlinModule()).build())) }` + `if (accessToken.isNotBlank()) builder.defaultHeaders { it.setBearerAuth(accessToken) }`. Kotlin 주석 금지.
- [x] T008 [US1] `./gradlew :common:test` → `ExpoPushSenderTest` 6개 Green.

**Checkpoint**: 커밋 `feat(push): PushSender 포트 + Expo 어댑터(100건 청크·순서 보존 티켓)`.

---

## Phase 4: User Story 2 - 알림 유형×언어 템플릿 렌더러, 광고성 자동 부착 (Priority: P1)

**Goal**: 5 유형 × 10 로케일 파리티, `{key}` 치환, 광고성이면 `(광고) ` 접두 + 로케일별 수신거부 안내.

**Independent Test**: 순수 단위 — Spring 없음.

### Tests for User Story 2 ⚠️

- [x] T009 [P] [US2] `common/src/test/kotlin/com/kbap/common/domain/notification/PushMessageRendererTest.kt` 생성 — BehaviorSpec, `val renderer = PushMessageRenderer()`. 시나리오: (1) 파리티 — `NotificationType.entries × LanguageCode.entries` 전부 `render(type, lang, args = mapOf("food" to "김치찌개","title" to "t","body" to "b"), marketing = false)` 의 title·body 가 `isNotBlank()`, title ≤ 200, body ≤ 1000; (2) 치환 — HELPFUL/ko `args["food"]="김치찌개"` → body 에 "김치찌개" 포함, `{food}` 미포함; 미제공 키 → `{` 문자 없음; (3) 광고성 — SCAN_SUGGESTION/en `marketing = true` → `title.startsWith("(광고) ")`, body 가 `PushTemplates.optOutNotice[EN]` 으로 끝남; 10 로케일 모두 `optOutNotice` 비어 있지 않음; (4) 정보성 — HELPFUL/ko `marketing=false` → `(광고)` 없음·안내 없음; (5) NOTICE 는 `args["title"]`/`args["body"]` 가 그대로 title/body(`"K-Bap"`,`"테스트 알림입니다."`), 1200자 body 는 1000자로 절단; (6) `NotificationType.SCAN_SUGGESTION.marketingByDefault == true`, 나머지 4종 false.
- [x] T010 [US2] `./gradlew :common:test` → 컴파일 Red 확인.

### Implementation for User Story 2

- [x] T011 [P] [US2] `common/src/main/kotlin/com/kbap/common/domain/notification/PushTemplates.kt` 생성 — `object PushTemplates { val byType: Map<NotificationType, Map<LanguageCode, PushContent>>; val optOutNotice: Map<LanguageCode, String> }`. 문구(10 로케일 각각 자연스러운 번역, ko 기준): HELPFUL `"리뷰에 도움돼요를 받았어요"` / `"{food} 리뷰가 다른 여행자에게 도움이 됐어요."`; SCAN_SUGGESTION `"메뉴판을 찍어보세요"` / `"오늘 먹을 메뉴, 스캔 한 번이면 알레르기까지 확인돼요."`; REVIEW_REMINDER `"{food} 어땠나요?"` / `"방금 드신 {food} 리뷰를 남겨주세요."`; NOTICE `"{title}"` / `"{body}"`; MEAL_TIME `"식사 시간이에요"` / `"근처 한식, 지금 스캔으로 골라보세요."`. `optOutNotice` ko `"수신거부: 설정 > 알림"`, en `"Unsubscribe: Settings > Notifications"`, 나머지 8개 언어 동일 의미.
- [x] T012 [US2] `common/src/main/kotlin/com/kbap/common/domain/notification/PushMessageRenderer.kt` 생성 — `@Service class PushMessageRenderer { fun render(type, lang, args, marketing): PushContent }`: `PushTemplates.byType.getValue(type).getValue(lang)` → `fill(template, args)`(정규식 `\{(\w+)}` → `args[key] ?: ""`) → `if (marketing) PushContent("(광고) $title", "$body\n${optOutNotice.getValue(lang)}")` → `title.take(200)`, `body.take(1000)`.
- [x] T013 [US2] `./gradlew :common:test` → `PushMessageRendererTest` Green.

**Checkpoint**: 커밋 `feat(push): 유형×로케일 템플릿 렌더러 + 광고성 표기 자동 부착`.

---

## Phase 5: User Story 3 - 대상 필터: 선호 토글·동의 원장·무효 토큰 (Priority: P1)

**Goal**: `PushTargetResolver.resolve(memberIds, type)` 가 research §4 표대로 유효 기기만 돌려준다.

**Independent Test**: `CommonTestApp` 컨텍스트 + Testcontainers. 회원 FK 가 있으므로 `member` 행은 `MemberJpaRepository` 로 만든다(기존 `NotificationDeviceJpaRepositoryTest` 시드 방식 따름).

### Tests for User Story 3 ⚠️

- [x] T014 [US3] `common/src/test/kotlin/com/kbap/common/domain/notification/PushTargetResolverTest.kt` 생성 — `@SpringBootTest @Import(MySqlContainerConfig::class)`, BehaviorSpec 클래스 본문 + `SpringExtension`, `@Autowired` resolver·device/setting/consent/member 리포지토리. 헬퍼 `member(): Long`, `device(memberId, lang = "ko", invalid = false)`, `setting(memberId, activity, mealTime)`, `consent(memberId, type, version)`. `beforeContainer` 에서 4테이블 `deleteAll`(dispatch→notification→device/setting/consent→member 순). 시나리오: (1) HELPFUL — activity true 회원 A(기기 2)·false 회원 B·설정 없음 회원 C → A 기기 2대만; (2) REVIEW_REMINDER 동일 규칙; (3) MEAL_TIME — mealTime true + 동의 두 종 v2 → 포함 / mealTime true + 동의 없음 → 제외 / mealTime true + `MARKETING_RECEIVE` 만 v2 → 제외 / 두 종 v1 → 제외 / mealTime false + 동의 v2 → 제외; (4) SCAN_SUGGESTION — 토글 무관, 동의 두 종 v2 만 포함(activity false 여도); (5) NOTICE — 토글·동의 없어도 포함; (6) `token_invalid_at` 스탬프 기기 제외; (7) `memberIds` 비면 빈 목록·쿼리 없음; (8) `NotificationConsents.isMarketingEnabled(open, 2)` 순수 — 두 종 v2 true / 한 종 false / 두 종 중 하나 v1 false / `requiredVersion=0` 이면 v1 도 true.
- [x] T015 [US3] `./gradlew :common:test` → 컴파일 Red 확인.

### Implementation for User Story 3

- [x] T016 [P] [US3] `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationConsents.kt` 생성 — `object NotificationConsents { fun isMarketingEnabled(open: List<NotificationConsent>, requiredVersion: Int): Boolean = NotificationConsentType.entries.all { type -> open.any { it.consentType == type && it.allows(requiredVersion) } } }`.
- [x] T017 [P] [US3] 리포지토리 3개 — `NotificationDeviceJpaRepository` `fun findByMemberIdInAndTokenInvalidAtIsNull(memberIds: Collection<Long>): List<NotificationDevice>`; `NotificationSettingJpaRepository` `fun findByMemberIdIn(memberIds: Collection<Long>): List<NotificationSetting>`; `NotificationConsentJpaRepository` `@Query("select c from NotificationConsent c where c.memberId in :memberIds and c.revokedAt is null") fun findOpenByMemberIdIn(@Param("memberIds") memberIds: Collection<Long>): List<NotificationConsent>`.
- [x] T018 [US3] `common/src/main/kotlin/com/kbap/common/domain/notification/PushTargetResolver.kt` 생성 — `@Service class PushTargetResolver(device/setting/consent 리포지토리)`. `@Transactional(readOnly = true) fun resolve(memberIds: Collection<Long>, type: NotificationType): List<NotificationDevice>`: 비면 `emptyList()`; `devices = deviceRepo.findByMemberIdInAndTokenInvalidAtIsNull(ids)`; `settings = settingRepo.findByMemberIdIn(ids).associateBy { it.memberId }`; `consents = consentRepo.findOpenByMemberIdIn(ids).groupBy { it.memberId!! }`; `fun allowed(memberId) = when(type) { HELPFUL, REVIEW_REMINDER -> settings[memberId]?.activity == true; MEAL_TIME -> settings[memberId]?.mealTime == true && marketing(memberId); SCAN_SUGGESTION -> marketing(memberId); NOTICE -> true }` with `marketing(id) = NotificationConsents.isMarketingEnabled(consents[id].orEmpty(), MARKETING_CONSENT_REQUIRED_VERSION)`; `companion { const val MARKETING_CONSENT_REQUIRED_VERSION = 2 }`. 반환 `devices.filter { allowed(it.memberId!!) }`.
- [x] T019 [US3] `api/src/main/kotlin/com/kbap/api/notification/NotificationConsentService.kt` — `isMarketingEnabled(open)` 본문을 `NotificationConsents.isMarketingEnabled(open, 0)` 위임으로 교체(설정 화면 동작 불변).
- [x] T020 [US3] `./gradlew :common:test :api:test` → `PushTargetResolverTest` Green, `NotificationSettingControllerTest` 회귀 없음.

**Checkpoint**: 커밋 `feat(push): 대상 필터 — 선호 토글·광고성 동의(v≥2)·무효 토큰 제외`.

---

## Phase 6: User Story 4 - dispatch 서비스: prepare(저장·렌더) / record(티켓 반영) (Priority: P1)

**Goal**: `prepare` 가 회원당 `notification` 1행(최신 기기 언어) + 기기당 `notification_dispatch` PENDING 을 만들고 봉투 목록을 돌려준다. `record` 가 티켓을 SENT/FAILED 로 반영하고 `DeviceNotRegistered` 기기를 무효화한다.

**Independent Test**: 같은 `CommonTestApp` 컨텍스트. `PushSender` 는 관여하지 않는다(티켓을 테스트가 직접 만든다).

### Tests for User Story 4 ⚠️

- [x] T021 [US4] `common/src/test/kotlin/com/kbap/common/domain/notification/PushDispatchServiceTest.kt` 생성 — T014 와 같은 헤더·헬퍼(+ notification/dispatch 리포지토리). prepare 시나리오: (1) 회원 A 기기 2(ko `updatedAt` 과거, ja 최신 — 저장 후 JDBC `UPDATE notification_device SET updated_at` 로 조정) + activity true, `PushRequest(HELPFUL, [A], args={food:"김치찌개"}, data={foodId:"7"})` → `notification` 1행: `type=HELPFUL`, title 이 ja 템플릿, `data == {type:"HELPFUL", foodId:"7", notificationId:<id>}`; dispatch 2행 PENDING, `expoToken` 스냅샷·`notificationDeviceId` 일치; `messages.size==2`, ko 기기 봉투 title 은 ko 템플릿, `data.notificationId` 포함, `dispatchIds` 순서 = `messages` 순서; (2) 기기 0대(activity false) → notification·dispatch 0행, `prepared.isEmpty()`; (3) 회원 2명 → notification 2행. record 시나리오: (4) 결과 `[PushOutcome(true,"t1",null), PushOutcome(false,null,"Boom")]` → dispatch[0] SENT+`ticketId=="t1"`, dispatch[1] FAILED+`error=="Boom"`, 결과 `PushDispatchResult(1,1)`; (5) `PushOutcome(false,null,"DeviceNotRegistered")` → 그 기기 `tokenInvalidAt != null`, 다른 기기 null; (6) 크기 불일치 결과 → `IllegalArgumentException`.
- [x] T022 [US4] `./gradlew :common:test` → 컴파일 Red 확인.

### Implementation for User Story 4

- [x] T023 [US4] `common/src/main/kotlin/com/kbap/common/domain/notification/PushDispatchService.kt` 생성 — `@Service class PushDispatchService(resolver, renderer, notificationRepo, dispatchRepo, deviceRepo)`. `@Transactional fun prepare(request: PushRequest): PreparedPush`: `devices = resolver.resolve(request.memberIds, request.type)`; `byMember = devices.groupBy { it.memberId!! }`; 회원마다 `inboxLang = LanguageCode.from(group.maxBy { it.updatedAt }.lang)`, `inbox = renderer.render(type, inboxLang, args, marketing)`, `notification = notificationRepo.save(Notification.forMember(memberId, type, inbox.title, inbox.body, null))`, `data = request.data + ("type" to type.name) + ("notificationId" to notification.id)`, `notification.data = data`; 기기마다 `content = renderer.render(type, LanguageCode.from(device.lang), args, marketing)`, `dispatch = dispatchRepo.save(NotificationDispatch.pending(notification.id, device.id, device.expoToken))`, 봉투 `PushEnvelope(device.expoToken, content.title, content.body, data)` 와 `dispatch.id` 를 같은 인덱스로 누적. `@Transactional fun record(prepared: PreparedPush, results: List<PushOutcome>): PushDispatchResult`: `require(results.size == prepared.dispatchIds.size)`; `dispatches = dispatchRepo.findAllById(prepared.dispatchIds).associateBy { it.id }`; 순서대로 `ok → markSent(ticketId!!)` / `else → markFailed(error ?: "unknown")`, `error == "DeviceNotRegistered"` 면 `deviceRepo.findById(dispatch.notificationDeviceId!!).ifPresent { it.markTokenInvalid(now) }`; `PushDispatchResult(sent, failed)`. dirty checking — `save()` 호출 없음.
- [x] T024 [US4] T021 의 record 시나리오가 `PushOutcome(true, "t1", null)`·`PushOutcome(false, null, "Boom")` 으로 호출하는지 확인(port 타입 `PushTicket` 을 common.domain 테스트에서 쓰지 않는다).
- [x] T025 [US4] `./gradlew :common:test` → `PushDispatchServiceTest` Green. `./gradlew :api:test` 리포트에서 `ModuleBoundaryTest` Green(도메인 → port 참조 없음).

**Checkpoint**: 커밋 `feat(push): PushDispatchService — prepare(알림함 1행·기기별 dispatch)/record(티켓 반영·토큰 무효화)`.

---

## Phase 7: User Story 5 - api·batch 조립 + 통합 컨텍스트 페이크 (Priority: P2)

**Goal**: 양쪽 앱이 `PushSender` 빈과 도메인 서비스 3종을 올린다. ArchUnit 어댑터 참조 규칙 통과. api 통합 컨텍스트는 페이크 `PushSender` 를 기본 포함.

**Independent Test**: `KbapApiApplicationTests`·`KbapBatchApplicationTests`(기존 컨텍스트 기동)에서 `PushSender`·`PushDispatchService` 빈 존재. `ModuleBoundaryTest` Green.

### Tests for User Story 5 ⚠️

- [ ] T026 [P] [US5] `api/src/test/kotlin/com/kbap/api/notification/FakePushSender.kt` 생성 — `class FakePushSender : PushSender { val sent = mutableListOf<PushMessage>(); var errorFor: (PushMessage) -> String? = { null }; override fun send(messages) { sent += messages; return messages.map { m -> errorFor(m)?.let { PushTicket.error(it) } ?: PushTicket.ok("ticket-${sent.size}") } }; fun reset() }` + `@Configuration class FakePushSenderConfig { @Bean @Primary fun fakePushSender() = FakePushSender() }`(`FakePlaceSearchClient` 선례). `api/src/test/kotlin/com/kbap/api/IntegrationTest.kt` `@Import` 에 `FakePushSenderConfig::class` 추가.
- [ ] T027 [P] [US5] `batch/src/test/kotlin/com/kbap/batch/KbapBatchApplicationTests.kt` 에 `then("푸시 파이프라인 빈이 조립된다")` 추가 — `@Autowired ApplicationContext` 로 `getBean(PushSender::class.java)`·`getBean(PushDispatchService::class.java)` non-null. `api/src/test/kotlin/com/kbap/api/KbapApiApplicationTests.kt` 에도 동일(`PushSender` 는 `FakePushSender` 인스턴스여야 함).
- [ ] T028 [US5] `./gradlew :api:test :batch:test` → 두 assertion Red(빈 없음).

### Implementation for User Story 5

- [ ] T029 [P] [US5] `api/src/main/kotlin/com/kbap/api/core/config/PushConfig.kt` 생성 — `@Configuration class PushConfig { @Bean @ConditionalOnMissingBean(PushSender::class) fun pushSender(@Value("\${kbap.push.expo.base-url}") baseUrl: String, @Value("\${kbap.push.expo.access-token:}") accessToken: String): PushSender = ExpoPushSender.create(baseUrl, accessToken) }`(`ExchangeConfig` 선례).
- [ ] T030 [P] [US5] `batch/src/main/kotlin/com/kbap/batch/config/PushConfig.kt` 생성 — 위와 같은 `@Bean` + `@Import(PushDispatchService::class, PushTargetResolver::class, PushMessageRenderer::class)`. `scanBasePackages` 는 손대지 않는다.
- [ ] T031 [P] [US5] `api/src/main/resources/application.yml` `kbap:` 아래 `exchange:` 다음에 `push:\n    expo:\n      base-url: https://exp.host\n      access-token: ${EXPO_ACCESS_TOKEN:}` 추가. `batch/src/main/resources/application.yml` `kbap:` 아래 `vector:` 다음에 동일 블록. (프로필별 yml 변경 없음.)
- [ ] T032 [US5] `./gradlew :api:test :batch:test` → T027 Green, `ModuleBoundaryTest` Green(어댑터 참조는 `api.core.config`·`batch.config` 만), 기존 스펙 회귀 없음. 컨텍스트 수 api 1·batch 1 유지(`-i` 로그의 Testcontainers 기동 횟수).

**Checkpoint**: 커밋 `feat(push): api·batch PushConfig 조립 + 통합 테스트 페이크 PushSender`.

---

## Phase 8: User Story 6 - 관리자 테스트 발송 API + dev 실기기 검증 (Priority: P2)

**Goal**: `POST /api/admin/notifications/test-push {memberId}` 가 NOTICE 를 prepare→send→record 로 보내고 `{sent, failed}` 를 돌려준다. dev 실기기 1대 수신.

**Independent Test**: `@IntegrationTest` + `FakePushSender`.

### Tests for User Story 6 ⚠️

- [ ] T033 [US6] `api/src/test/kotlin/com/kbap/api/admin/AdminNotificationTestControllerTest.kt` 생성 — `@IntegrationTest` BehaviorSpec; `@Autowired` MockMvc·TokenIssuer·DataSource·FakePushSender·FakeSocialTokenVerifier·`NotificationDeviceJpaRepository`·`NotificationDispatchJpaRepository`. `tokenOf(role)` = `tokenIssuer.issueAccessToken(0, role)`(`AdminContentOutboxControllerTest` 선례), `login(sub)` = `NotificationSettingControllerTest` 의 헬퍼 복제(회원 생성용), `beforeContainer { TestTables.clearAll(dataSource); fakePushSender.reset(); fakeSocialTokenVerifier.reset() }`. `post(body, token)` = `mockMvc.post("/api/admin/notifications/test-push") { header("X-API-Version","1.0"); token?.let { header("Authorization","Bearer $it") }; contentType = APPLICATION_JSON; content = body }`. 시나리오: (1) 회원 + 기기(`NotificationDevice.register("inst-1","ExponentPushToken[x]",IOS,"ja",memberId)` save) → 200 `payload.sent==1, failed==0`, `fakePushSender.sent.single().to=="ExponentPushToken[x]"`, title 이 `"K-Bap"`, `data.type=="NOTICE"`, dispatch 1행 SENT; (2) 기기 없는 회원 → 200 `{0,0}`, `sent` 비어 있음; (3) `errorFor = { "DeviceNotRegistered" }` → `{0,1}`, dispatch FAILED, 기기 `tokenInvalidAt != null`; (4) 없는 memberId → 400 `MEMBER-003`; (5) USER 토큰 → 403 `AUTH-008`; (6) 토큰 없음 → 401.
- [ ] T034 [US6] `./gradlew :api:test` → 404/컴파일 Red 확인.

### Implementation for User Story 6

- [ ] T035 [US6] `api/src/main/kotlin/com/kbap/api/notification/PushNotificationService.kt` 생성 — `@Service class PushNotificationService(dispatchService: PushDispatchService, pushSender: PushSender)`. `fun send(request: PushRequest): PushDispatchResult { val prepared = dispatchService.prepare(request); if (prepared.isEmpty()) return PushDispatchResult(0, 0); val tickets = pushSender.send(prepared.messages.map { PushMessage(it.to, it.title, it.body, it.data) }); return dispatchService.record(prepared, tickets.map { PushOutcome(it.ok, it.id, it.error) }) }`. **`@Transactional` 없음**(외부 호출은 트랜잭션 밖 — prepare/record 가 각자 트랜잭션).
- [ ] T036 [P] [US6] `api/src/main/kotlin/com/kbap/api/admin/AdminNotificationTestRequest.kt` — `data class AdminNotificationTestRequest(@field:NotNull val memberId: Long?)`; `AdminNotificationTestResponse.kt` — `data class AdminNotificationTestResponse(val sent: Int, val failed: Int) { companion fun from(r: PushDispatchResult) }`.
- [ ] T037 [P] [US6] `api/src/main/kotlin/com/kbap/api/admin/AdminNotificationTestService.kt` — `@Service class AdminNotificationTestService(memberService, pushNotificationService)`; `fun sendTestPush(memberId: Long): PushDispatchResult { memberService.getMember(memberId); return pushNotificationService.send(PushRequest(NotificationType.NOTICE, listOf(memberId), args = mapOf("title" to "K-Bap", "body" to "테스트 알림입니다."))) }`(`@Transactional` 없음 — 외부 호출 포함).
- [ ] T038 [US6] `api/src/main/kotlin/com/kbap/api/admin/AdminNotificationTestApi.kt` — `@Tag(name = "관리자 알림 테스트 발송")`, `@SecurityRequirement(name = "bearerAuth")`, `@Operation`(NOTICE·기기 언어 렌더·토글/동의 무관·dev 실기기 검증용) + `@ApiResponses`(200·400 MEMBER-003·401·403 AUTH-008), `fun sendTestPush(request: AdminNotificationTestRequest): ResponseEntity<BaseResponse<AdminNotificationTestResponse>>`. `AdminNotificationTestController.kt` — `@RestController @RequestMapping(ApiPaths.ADMIN + "/notifications", version = "1.0+")`, `@PostMapping("/test-push") override fun sendTestPush(@RequestBody @Valid request)` → `ResponseEntity.ok(BaseResponse.ok(AdminNotificationTestResponse.from(service.sendTestPush(request.memberId!!))))`. `ADMIN/**` 은 `WebConfig` 가 이미 보호 — 등록 불필요(확인만).
- [ ] T039 [US6] `./gradlew :api:test` → T033 6개 Green, 전체 회귀 없음.
- [ ] T040 [US6] dev 배포 후 quickstart §2 절차로 실기기 1대에 NOTICE 수신 확인 — `notification_dispatch` SENT + `ticket_id` 기록. 결과(회원 id·ticket id·수신 스크린샷 유무)를 KB-468 코멘트에 남긴다.

**Checkpoint**: 커밋 `feat(admin): 관리자 테스트 푸시 발송 API — prepare/send/record 글루`.

---

## Phase 9: Polish & Cross-Cutting

- [ ] T041 `./gradlew build` 전체 Green(arch 포함). 컨텍스트 수 api 1·common 1·batch 1.
- [ ] T042 [P] `docs/architecture/meogo-conventions.md` 에 "공유 도메인 서비스와 port — prepare/send/record 3단" 항목 추가(도메인이 port 를 못 보므로 외부 호출은 글루가 수행, 헌법 "pending 저장 → 외부 호출 → 결과 저장" 과 동일). 간단히 5줄.
- [ ] T043 [P] `../kbap-agenthub/wiki/push-send-pipeline.md` 생성 + `INDEX.md` 한 줄 — 3단 구조·토글 매핑 표·알림함 언어 기준·FE data 계약·`EXPO_ACCESS_TOKEN` 공급 방식. 허브에서 커밋.
- [ ] T044 KB-468 Jira 코멘트(quickstart §3 문구) + FE 에 `contracts/push-data-contract.md` 의 data 계약(MEAL_TIME 추가) 공유. **FE 요구사항 함께 전달**: 푸시 언어는 `notification_device.lang` 에만 의존하므로 앱 실행(포그라운드 진입)마다 + 기기 언어 변경 감지 시 `PUT /api/notifications/tokens` 를 현재 `lang` 으로 재호출해야 한다(재호출 전까지 이전 언어로 발송, 과거 알림함 행은 소급 안 됨). DoD 체크박스 갱신.
- [ ] T045 `open-draft-pr-to-develop` 로 draft PR.

---

## Dependencies & Execution Order

```
T001 → T002·T003·T004 (병렬)
  ├─ US1 (T005–T008)  ── 독립
  ├─ US2 (T009–T013)  ── 독립 (US1 과 병렬 가능)
  └─ US3 (T014–T020) → US4 (T021–T025) → US5 (T026–T032) → US6 (T033–T040) → Polish
```

- US4 는 US2(렌더러)·US3(필터)을 주입한다. US5 는 US1 어댑터를 조립한다. US6 은 전부 필요.
- 한 세션 순차 실행 권장 순서: T001–T004 → US1 → US2 → US3 → US4 → US5 → US6 → Polish.

## Parallel Opportunities

- Phase 2: T002·T003·T004 서로 다른 파일.
- US1 ↔ US2 완전 독립(common 안 다른 패키지).
- US3: T016·T017 병렬 후 T018.
- US5: T026·T027 / T029·T030·T031 병렬.
- US6: T036·T037 병렬(둘 다 T035 이후).

## Implementation Strategy

- **MVP = US1 + US2 + US3 + US4** (common 만으로 파이프라인 완성, DB·HTTP 검증 끝). US5 는 앱 조립, US6 은 실기기 DoD.
- 트리거 3종(좋아요 리스너·식사시간 잡·리뷰요청 폴링)·영수증(KB-473)·재시도·레이트리밋은 범위 밖.
