# Tasks: SCAN_SUGGESTION 배치 — 점심 스케줄 스캔 제안 발송(광고성 동의자만)

**Input**: Design documents from `/specs/kb-471-scan-suggestion-batch/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/scan-suggestion-job.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패하는 테스트를 먼저 쓰고(Red 확인) 구현한다. 테스트 스타일은 Kotest `BehaviorSpec`(given/when/then 한국어), 통합 헤더는 `@BatchIntegrationTest`(batch)·`@SpringBootTest + @Import(MySqlContainerConfig::class)`(common) 고정. Kotlin 소스 주석 금지.

**Organization**: 스토리별 phase. 공용 발송 부품(port `PushNotifier` + infra `ExpoPushNotifier`)은 모든 스토리가 쓰므로 Foundational.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: US1~US7 (spec.md)
- 경로는 저장소 루트 기준

## Path Conventions

- common: `common/src/main/kotlin/com/kbap/common/{port,infra,domain}/...`, 테스트 `common/src/test/kotlin/...`
- batch: `batch/src/main/kotlin/com/kbap/batch/{notification,config,schedule}/...`, 테스트 `batch/src/test/kotlin/com/kbap/batch/...`
- api: `api/src/main/kotlin/com/kbap/api/{core/config,admin,notification}/...`

---

## Phase 1: Setup (배치 테스트 픽스처 — 컨텍스트 1개 유지)

**Purpose**: 잡 통합 테스트가 Expo 를 부르지 않고 시각을 바꿀 수 있게 하는 픽스처. `@BatchIntegrationTest` 하나에 `@Import` 로 묶어 Spring 컨텍스트가 늘지 않게 한다(KB-392).

- [x] T001 [P] Create `FakePushSender`(port `PushSender` 구현 — 받은 `PushMessage` 기록 `sent`, `errorFor: (PushMessage) -> String?` 로 건별 error 티켓, `reset()`) + `FakePushSenderConfig`(`@TestConfiguration`, `@Bean @Primary`) in `batch/src/test/kotlin/com/kbap/batch/notification/FakePushSenderConfig.kt` — api `api/src/test/kotlin/com/kbap/api/notification/FakePushSender.kt` 와 같은 모양
- [x] T002 [P] Create `MutableClock`(`java.time.Clock` 서브클래스, `set(Instant)`, zone `Asia/Seoul`) + `MutableClockConfig`(`@TestConfiguration`, `@Bean @Primary fun clock(): Clock`) in `batch/src/test/kotlin/com/kbap/batch/notification/MutableClockConfig.kt`
- [x] T003 Add `FakePushSenderConfig::class, MutableClockConfig::class` to the `@Import` of `batch/src/test/kotlin/com/kbap/batch/BatchIntegrationTest.kt` (depends on T001, T002)

---

## Phase 2: Foundational — 공용 발송 부품 `PushNotifier` (FR-018, SC-010)

**Purpose**: 관리자 발송·스캔 제안 배치·향후 식사시간/리뷰/활동 알림이 공유하는 발송 진입점. 호출자는 `PushRequest(type, memberIds, args, data, ttlSeconds)` 한 줄만 만든다. **이 phase 가 끝나야 배치 writer 를 쓸 수 있다.**

- [x] T004 Write failing test `ExpoPushNotifierTest`(`@SpringBootTest + @Import(MySqlContainerConfig::class)`, `CommonTestApp` 컨텍스트; `PushSender` 는 테스트 안 페이크 — 받은 메시지 기록·`errorFor`) in `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushNotifierTest.kt`: (a) 회원 1·기기 2(news on·동의 v2) → `send(PushRequest(NEWS, [m]))` = `PushDispatchResult(2,0)`·dispatch SENT 2·페이크 메시지 2 / (b) 페이크가 두 번째 메시지에 error → `(1,1)`·FAILED 1 / (c) 대상 0 → `(0,0)`·sender 미호출. 시드 헬퍼는 `PushDispatchServiceTest` 의 `device/newsOn/newsConsent` 를 그대로 옮긴다. Red 확인
- [x] T005 Create port `PushNotifier { fun send(request: PushRequest): PushDispatchResult }` in `common/src/main/kotlin/com/kbap/common/port/push/PushNotifier.kt` (domain 값 타입 참조 허용 — ArchUnit port 규칙은 spring/jpa/infra/api/batch 만 금지)
- [x] T006 Create `ExpoPushNotifier(dispatchService: PushDispatchService, sender: PushSender) : PushNotifier` in `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushNotifier.kt` — 몸체는 `api/src/main/kotlin/com/kbap/api/notification/PushNotificationService.kt` 의 3줄(prepare → `PushEnvelope→PushMessage` 매핑 → `sender.send` → `record`), `prepared.isEmpty()` 면 `(0,0)`. T004 Green
- [x] T007 Register `@Bean fun pushNotifier(dispatchService, pushSender): PushNotifier = ExpoPushNotifier(...)` in `api/src/main/kotlin/com/kbap/api/core/config/PushConfig.kt` and in `batch/src/main/kotlin/com/kbap/batch/config/PushConfig.kt`
- [x] T008 Replace `PushNotificationService` injection with `PushNotifier` in `api/src/main/kotlin/com/kbap/api/admin/AdminNotificationTestService.kt`, then delete `api/src/main/kotlin/com/kbap/api/notification/PushNotificationService.kt`; run `./gradlew :api:test` — `AdminNotificationTestControllerTest`·`ModuleBoundaryTest` Green (api `FakePushSender` 는 `PushSender` 수준이라 notifier 실체가 돈다)

**Checkpoint**: 호출자가 port 만 보고 발송한다. 어댑터 직접 참조는 두 `PushConfig` 뿐.

---

## Phase 3: User Story 1 — 점심 시간대에 동의한 기기로 스캔 제안이 온다 (Priority: P1) 🎯 MVP

**Goal**: `scanSuggestionPushJob`(대상 확정 tasklet → 회원 묶음 chunk step) 이 소식 토글 on + 동의 v2 + 유효 토큰 기기에만 `SCAN_SUGGESTION` 을 보내고, 푸시 data 에 `type`·`notificationId`, ttl 3h 를 싣는다.

**Independent Test**: 조건 충족/미충족 기기를 섞어 시드하고 잡을 한 번 돌리면 충족 기기에만 알림함 행·SENT 이력·페이크 메시지가 생긴다.

### Tests for User Story 1 (Test-First) ⚠️

- [x] T009 [P] [US1] Add scenario to `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepositoryTest.kt`: `findMemberIdsByNewsTrue()` — news=true 행의 회원 id 만 distinct(같은 회원 기기 2대 → 1건), news=false 제외, 소프트 삭제 행 제외. Red 확인
- [x] T010 [P] [US1] Add scenario to `common/src/test/kotlin/com/kbap/common/domain/notification/PushDispatchServiceTest.kt`: `prepare(PushRequest(SCAN_SUGGESTION, ids, ttlSeconds = 10800))` 의 봉투 `ttlSeconds == 10800`, 미지정이면 null. Red 확인
- [x] T011 [P] [US1] Add scenario to `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushSenderTest.kt`: `PushMessage(ttlSeconds = 10800)` → 본문 `$[0].ttl == 10800`, `ttlSeconds = null` → `$[0].ttl` doesNotExist. Red 확인
- [x] T012 [US1] Write failing `ScanSuggestionPushJobTest`(`@BatchIntegrationTest`; `@Autowired BatchJobLauncher`·리포지토리 5종·`FakePushSender`·`MutableClock`; `beforeSpec` 에서 알림 5테이블 `deleteAll`·페이크 reset·시계 12:00 KST; 실행은 `launcher.launch("scanSuggestionPushJob")` 후 `BatchJobTriggerControllerTest` 의 `awaitStatus` 선례로 폴링) in `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushJobTest.kt`: 시나리오 (a) 회원 A 기기 2대(lang `ko`·`en`, news on, 동의 v2)·회원 B news off·회원 C 설정 없음·회원 D 동의 v1·회원 E 무효 토큰·회원 미연결 기기 → notification 2(type SCAN_SUGGESTION, data.type/notificationId)·dispatch SENT 2·페이크 메시지 2(ttl 10800, 제목이 `(광고) ` 로 시작, ko/en 본문이 서로 다르고 각 언어 수신거부 안내로 끝남)·잡 COMPLETED. Red 확인(잡 미존재)

### Implementation for User Story 1

- [x] T013 [P] [US1] Add `@Query("select distinct s.memberId from NotificationSetting s where s.news = true") fun findMemberIdsByNewsTrue(): List<Long>` to `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepository.kt` (T009 Green)
- [x] T014 [P] [US1] Add `ttlSeconds: Int? = null` to `PushRequest` and `ttlSeconds: Int?` to `PushEnvelope` in `common/src/main/kotlin/com/kbap/common/domain/notification/PushRequest.kt`; fill it in `prepare` in `common/src/main/kotlin/com/kbap/common/domain/notification/PushDispatchService.kt` (T010 Green)
- [x] T015 [US1] Add `ttlSeconds: Int? = null` to `PushMessage` in `common/src/main/kotlin/com/kbap/common/port/push/PushMessage.kt`; add `@JsonInclude(NON_NULL) val ttl: Int?` to `ExpoMessage` and map it in `sendChunk` in `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushSender.kt`; pass `ttlSeconds` in the envelope→message mapping in `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushNotifier.kt` (T011 Green; depends on T014)
- [x] T016 [P] [US1] Create `@Component @JobScope class ScanSuggestionCandidateBuffer`(`ArrayDeque<Long>`, `load(Collection<Long>)`, `poll(): Long?`) in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionCandidateBuffer.kt`
- [x] T017 [P] [US1] Create `ScanSuggestionPushWriter(notifier: PushNotifier, ttlSeconds: Int, meterRegistry: MeterRegistry) : ItemWriter<Long>` in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionPushWriter.kt` — `write(chunk)` = `notifier.send(PushRequest(NotificationType.SCAN_SUGGESTION, chunk.items, ttlSeconds = ttlSeconds))` → 로그 `스캔 제안 발송 members={} sent={} failed={}` + 카운터 `kbap.push.dispatch{type=SCAN_SUGGESTION,result=sent|failed}`
- [x] T018 [US1] Create `ScanSuggestionTargetTasklet(settingRepository, buffer) : Tasklet` in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionTargetTasklet.kt` — `findMemberIdsByNewsTrue()` → `buffer.load`, 로그 `스캔 제안 대상 확정 candidates={} excludedToday={} targets={}`(excludedToday 는 US3 전까지 0), `RepeatStatus.FINISHED` (depends on T013, T016)
- [x] T019 [US1] Create `ScanSuggestionPushBatchConfig` in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionPushBatchConfig.kt`: `@Bean fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))`, `scanSuggestionTargetStep`(tasklet, `ResourcelessTransactionManager()`), `scanSuggestionCandidateReader: ItemReader<Long> = ItemReader { buffer.poll() }`, `scanSuggestionSendStep`(`chunk<Long, Long>(memberChunkSize)`, `ResourcelessTransactionManager()`, reader·writer), `scanSuggestionPushJob`(`RunIdIncrementer`, `jobNameMdcListener`, target → send). 프로퍼티 `@Value("\${kbap.batch.scan-suggestion.member-chunk-size:500}")`, `@Value("\${kbap.batch.scan-suggestion.ttl:3h}") ttl: Duration` (depends on T017, T018)
- [x] T020 [US1] Add `kbap.batch.scan-suggestion.{cron: ${SCAN_SUGGESTION_CRON:0 0 12 * * *}, member-chunk-size: 500, ttl: 3h}` with comments to `batch/src/main/resources/application.yml` (테스트 yml 은 변경 없음 — 기본값 사용). T012 시나리오 (a) Green

**Checkpoint**: 수동 트리거로 스캔 제안이 나간다. 시간대 가드·하루 1회·채널·스케줄은 아직 없음.

---

## Phase 4: User Story 2 — 허용 시간대 밖에서는 절대 보내지 않는다 (Priority: P1)

**Goal**: 실행 시작 시각이 08:00~20:59:59 KST 밖이면 후보가 있어도 저장·발송 0건, 잡 exit code `NOOP`.

**Independent Test**: 시계 21:30 KST + 조건 충족 기기 → 행 0·exitCode NOOP. 07:59 도 같고 08:00·20:59 는 발송.

### Tests for User Story 2 (Test-First) ⚠️

- [x] T021 [P] [US2] Write failing `ScanSuggestionSendWindowTest`(순수 BehaviorSpec, `Clock.fixed`) in `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionSendWindowTest.kt`: `isOpen` — 07:59:59 false·08:00:00 true·20:59:59 true·21:00:00 false (KST). Red 확인
- [x] T022 [US2] Add scenario (c) to `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushJobTest.kt`: 시계 21:30 KST + 시나리오 (a) 시드 → `launcher.getExecution(id).exitStatus.exitCode == "NOOP"`, notification 0·dispatch 0·페이크 미호출; 시계 08:00:00 → 발송됨. Red 확인

### Implementation for User Story 2

- [x] T023 [US2] Create `object ScanSuggestionSendWindow { fun isOpen(clock: Clock): Boolean }`(`LocalTime.now(clock.withZone(KST))` 가 `[08:00, 21:00)`) in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionSendWindow.kt` (T021 Green)
- [x] T024 [US2] Inject `Clock` into `ScanSuggestionTargetTasklet` in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionTargetTasklet.kt`: 닫힘이면 로그 `스캔 제안 발송 시간대 밖이라 건너뜁니다 now={}`, `contribution.exitStatus = ExitStatus.NOOP`, 버퍼 미적재 후 `FINISHED` (T022 Green — 발송 step 은 read 0 으로 종료, 잡 exitCode NOOP)

**Checkpoint**: 하드 가드는 cron 설정과 무관하게 코드에 고정.

---

## Phase 5: User Story 3 — 같은 기기에는 하루 한 번만 온다 (Priority: P1)

**Goal**: 같은 KST 달력일에 잡을 여러 번 돌려도 오늘 이미 `SCAN_SUGGESTION` 알림이 만들어진 회원(그 기기들)은 제외. 발송 성공 여부는 보지 않는다.

**Independent Test**: 같은 날 두 번 실행 → 두 번째 0건. 다음 날 → 다시 1건. 오늘 FAILED 였던 기기도 같은 날 재발송 없음.

### Tests for User Story 3 (Test-First) ⚠️

- [x] T025 [P] [US3] Add scenario to `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationJpaRepositoryTest.kt`: `findMemberIdsByTypeAndCreatedAtAfter(SCAN_SUGGESTION, since)` — `createdAt >= since` 포함·이전 제외·다른 type 제외·distinct·`memberId null` 제외(`jdbcTemplate` 로 `created_at` 을 직접 세팅). Red 확인
- [x] T026 [P] [US3] Add scenario to `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionSendWindowTest.kt`: `startOfToday(clock)` — 12:00 KST 시계 → KST 자정을 `ZoneId.systemDefault()` 로 옮긴 `LocalDateTime`(UTC JVM 이면 전날 15:00)과 같다; KST 00:30 시계도 같은 날 자정. Red 확인
- [x] T027 [US3] Add scenarios (b) to `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushJobTest.kt`: 같은 시계로 재실행 → notification·dispatch 증가 0·페이크 추가 0 / 첫 실행 기기 하나를 `errorFor` 로 FAILED 로 만든 뒤 재실행 → 그 기기도 재발송 없음 / 시계를 다음 날 12:00 으로 → 다시 발송. Red 확인

### Implementation for User Story 3

- [x] T028 [P] [US3] Add `@Query("select distinct n.memberId from Notification n where n.type = :type and n.createdAt >= :since and n.memberId is not null") fun findMemberIdsByTypeAndCreatedAtAfter(@Param("type") type: NotificationType, @Param("since") since: LocalDateTime): List<Long>` to `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationJpaRepository.kt` (T025 Green)
- [x] T029 [P] [US3] Add `fun startOfToday(clock: Clock): LocalDateTime`(`LocalDate.now(clock KST).atStartOfDay(KST).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()`) to `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionSendWindow.kt` (T026 Green)
- [x] T030 [US3] Inject `NotificationJpaRepository` into `ScanSuggestionTargetTasklet` in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionTargetTasklet.kt`: `targets = candidates - findMemberIdsByTypeAndCreatedAtAfter(SCAN_SUGGESTION, startOfToday(clock))`, 로그의 `excludedToday` 채움 (T027 Green; depends on T028, T029)

**Checkpoint**: 잡 재실행이 안전하다. P1 세 스토리 완료 = 운영 투입 가능한 최소 기능.

---

## Phase 6: User Story 4 — Android 에서 광고성 알림이 소식 채널로 헤드업된다 (Priority: P2)

**Goal**: 광고성 유형(SCAN_SUGGESTION·NEWS·MEAL_TIME) 의 Expo 메시지 `channelId = "news"`, 그 외 `"default"`. 매핑은 `NotificationType` 한 곳.

**Independent Test**: 스캔 제안 발송 시 페이크/Expo 본문의 `channelId` 가 `news`, HELPFUL 은 `default`.

### Tests for User Story 4 (Test-First) ⚠️

- [x] T031 [P] [US4] Add scenario to `common/src/test/kotlin/com/kbap/common/domain/notification/PushDispatchServiceTest.kt`: SCAN_SUGGESTION·NEWS·MEAL_TIME 봉투 `channelId == "news"`, HELPFUL·REVIEW_REMINDER `"default"`. Red 확인
- [x] T032 [P] [US4] Replace the "sound·priority·channelId 기본값" scenario in `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushSenderTest.kt` with "메시지의 channelId 를 그대로 직렬화"(`PushMessage(channelId = "news")` → `$[0].channelId == "news"`, sound·priority 는 그대로). Red 확인
- [x] T033 [US4] Add assertion to scenario (a) in `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushJobTest.kt`: 페이크 메시지 `channelId == "news"`. Red 확인

### Implementation for User Story 4

- [x] T034 [US4] Add `val channelId: String get() = if (marketing) "news" else "default"` to `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationType.kt`; add `channelId: String` to `PushEnvelope` in `common/src/main/kotlin/com/kbap/common/domain/notification/PushRequest.kt` and fill `request.type.channelId` in `prepare` in `common/src/main/kotlin/com/kbap/common/domain/notification/PushDispatchService.kt` (T031 Green)
- [x] T035 [US4] Add `channelId: String` to `PushMessage` in `common/src/main/kotlin/com/kbap/common/port/push/PushMessage.kt`; remove the `"default"` default from `ExpoMessage.channelId` and map from the message in `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushSender.kt`; pass `envelope.channelId` in `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushNotifier.kt`; fix compile in `api/src/test/kotlin/com/kbap/api/notification/FakePushSender.kt`·`batch/src/test/kotlin/com/kbap/batch/notification/FakePushSenderConfig.kt` if they construct `PushMessage` (T032, T033 Green)

**Checkpoint**: 유형→채널 매핑 단일 출처. 비광고성은 `default` 유지(FE 채널명 미확정).

---

## Phase 7: User Story 5 — 운영이 스케줄을 조정하고 실행 결과를 본다 (Priority: P2)

**Goal**: cron 외부화된 스케줄(기본 12:00 KST), 부팅 자동 실행 없음, 기존 HTTP 트리거로 실행 가능, 후보·성공·실패·NOOP 가 로그·메트릭에 남는다. 배치 1대라 ShedLock 없음.

**Independent Test**: 컨텍스트에 `scanSuggestionPushJob` 빈이 있고, 트리거 API 로 이름을 불러 202 → COMPLETED/NOOP 를 조회할 수 있으며, 로그·카운터가 남는다.

### Tests for User Story 5 (Test-First) ⚠️

- [x] T036 [P] [US5] Write `ScanSuggestionPushBatchConfigTest`(`@BatchIntegrationTest`) in `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushBatchConfigTest.kt`: `@Qualifier("scanSuggestionPushJob") job.name == "scanSuggestionPushJob"`. Red 확인(US1 이후면 즉시 Green — 그 경우 T037 과 함께 검증)
- [x] T037 [US5] Add scenario (e) to `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushJobTest.kt`: MockMvc `POST /internal/batch/jobs?jobName=scanSuggestionLunchPushJob` → 202, `GET /internal/batch/executions/{id}` → `status COMPLETED`; `MeterRegistry` 카운터 `kbap.push.dispatch{type=SCAN_SUGGESTION,result=sent}` 가 발송 수만큼 증가. Red 확인(카운터)

### Implementation for User Story 5

- [x] T038 [US5] (2026-09-16 재개정: 점심/저녁 잡 2개 + 스케줄 메서드 2개, 문구는 `PushTemplates.bySlot`) (2026-09-15 개정: cron 외부화 대신 12:00·18:00 KST 하드코딩 — `ScanSuggestionSendWindow.LUNCH_CRON/DINNER_CRON`, 상한은 슬롯당 1회로 `startOfCurrentSlot`) Add `@Scheduled(cron = LUNCH_CRON) @Scheduled(cron = DINNER_CRON) fun pushScanSuggestions() = launch("scanSuggestionPushJob")` to `batch/src/main/kotlin/com/kbap/batch/schedule/BatchJobScheduler.kt` (ShedLock 없음 — 배치 1대, research §7). yml 주석에 "스케줄 시각은 SCAN_SUGGESTION_CRON 으로 외부화" 갱신 in `batch/src/main/resources/application.yml`
- [x] T039 [US5] Verify writer counters and tasklet/writer log lines match `contracts/scan-suggestion-job.md` §5 in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionPushWriter.kt`·`ScanSuggestionTargetTasklet.kt` (T037 Green)

**Checkpoint**: 스케줄·수동 트리거·관측 완료.

---

## Phase 8: User Story 6 — 대상이 많아져도 Expo 제약 안에서 전부 보낸다 (Priority: P2)

**Goal**: 어댑터가 100건 청크를 고정 스레드 풀(기본 6)로 동시에 보내되 요청 시작 간격(기본 170ms)으로 초당 600건을 넘기지 않는다. 티켓 순서 = 입력 순서. 청크 실패는 그 청크만.

**Independent Test**: 청크 12개·동시성 6 → 총 소요가 순차보다 짧고 시작 시각이 겹친다; 간격 100ms → i번째 시작 ≥ 첫 시작 + i×100ms; 응답에 청크 번호를 실어 티켓 순서 확인.

### Tests for User Story 6 (Test-First) ⚠️

- [x] T040 [US6] Add concurrency/pacing scenarios to `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushSenderTest.kt` using `MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true)` with a response creator that records `Instant.now()` and sleeps 200ms: (a) 1,200건·concurrency 6·interval 0 → 총 소요 < 12×200ms·시작 시각 최소 2개 겹침 / (b) 1,200건·concurrency 6·interval 100ms → i번째 시작 ≥ 첫 시작 + i×100ms(정렬 후) / (c) 응답 티켓 id 에 청크 번호를 실어 `tickets` 순서 = 입력 순서 / (d) 기존 "두 번째 청크 500" 시나리오는 `ignoreExpectOrder` 로 갱신. `MockRestServiceServer` 가 동시성에서 불안정하면 이 시나리오만 JDK `com.sun.net.httpserver.HttpServer` 로 대체. Red 확인
- [x] T041 [P] [US6] Add scenario (d) to `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushJobTest.kt`: 회원 1,200명(기기 1대씩) + `member-chunk-size` 500 → step `writeCount 1200`·페이크 메시지 1200·SENT 1200; `errorFor` 로 특정 토큰 3개 실패 → FAILED 3·SENT 1197·잡 COMPLETED. Red 확인(현재도 통과할 수 있음 — 그 경우 회귀 고정용)

### Implementation for User Story 6

- [x] T042 [US6] Add fixed thread pool + pacer to `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushSender.kt`: 생성자 `(restClient, executor: ExecutorService, minRequestInterval: Duration)`, `send` = 청크마다 `awaitSlot()`(synchronized `nextSlotAt = max(now, nextSlotAt) + interval` 후 sleep) → `executor.submit { sendChunk(chunk) }` → 제출 순서대로 `get()` → flatten; `AutoCloseable.close()` 로 풀 종료; `create(baseUrl, accessToken, concurrency: Int, minRequestInterval: Duration)` 가 `Executors.newFixedThreadPool(concurrency) { Thread(it, "expo-push-…") }` 생성; 기존 `internal create(..., builder)` 도 같은 인자 추가 (T040 Green)
- [x] T043 [US6] Pass `@Value("\${kbap.push.expo.concurrency:6}")`·`@Value("\${kbap.push.expo.min-request-interval:170ms}") Duration` to `ExpoPushSender.create` and mark the bean `@Bean(destroyMethod = "close")` in `api/src/main/kotlin/com/kbap/api/core/config/PushConfig.kt` and `batch/src/main/kotlin/com/kbap/batch/config/PushConfig.kt`; add `kbap.push.expo.{concurrency: 6, min-request-interval: 170ms}` with comments to `api/src/main/resources/application.yml` and `batch/src/main/resources/application.yml` (T041 Green)

**Checkpoint**: 처리량 = min(600/s, 동시성×100÷지연). 상한은 Expo 가 정한다(spec Assumptions).

---

## Phase 9: User Story 7 — Expo 가 잠깐 흔들려도 발송이 유실되지 않는다 (Priority: P2)

**Goal**: 청크 요청의 일시 실패(네트워크·429·5xx)는 지수 백오프(1s→2s→4s, 최대 3회) 재시도 후에만 실패 확정. 400 등 영구 실패는 즉시 실패. 어댑터는 예외를 던지지 않는다.

**Independent Test**: 5xx·5xx·200 → 요청 3회·전부 ok. 400 → 요청 1회·청크 전부 error. 5xx 4회 → 요청 4회·청크 전부 error.

### Tests for User Story 7 (Test-First) ⚠️

- [x] T044 [US7] Add retry scenarios to `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushSenderTest.kt` with a test policy `RetryPolicy.builder().maxRetries(3).delay(Duration.ofMillis(1)).multiplier(2.0).predicate(...)` injected via `create(baseUrl, accessToken, builder, ..., retryPolicy)`: (a) 5xx·5xx·200 → 요청 3회·티켓 100 ok / (b) 429·429·200 → 같음 / (c) `withException(IOException)`·200 → 2회 / (d) 5xx ×4 → 요청 4회·청크 전부 error(마지막 오류 문자열)·예외 없음 / (e) 400 → 요청 1회·청크 전부 error / (f) 응답 파싱 실패 → 1회·error. Red 확인

### Implementation for User Story 7

- [x] T045 [US7] Wrap the HTTP call in `sendChunk` with `RetryTemplate(retryPolicy).execute { ... }` in `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushSender.kt`; `RetryException` → cause 로 `failAll`; predicate 밖 예외(`HttpClientErrorException` 4xx 중 429 제외·`HttpMessageConversionException`) 는 기존대로 즉시 `failAll`; 재시도 시 `warn` 로그. `companion` 에 `defaultRetryPolicy(maxRetries, initialDelay, multiplier): RetryPolicy`(predicate = `ResourceAccessException` ∪ `HttpServerErrorException` ∪ `HttpClientErrorException.TooManyRequests`) (T044 Green)
- [x] T046 [US7] Read `kbap.push.expo.retry.{max-retries:3, initial-delay:1s, multiplier:2.0}` in `api/src/main/kotlin/com/kbap/api/core/config/PushConfig.kt` and `batch/src/main/kotlin/com/kbap/batch/config/PushConfig.kt`, build the policy with `ExpoPushSender.defaultRetryPolicy` and pass it to `create`; add the three keys with comments to `api/src/main/resources/application.yml` and `batch/src/main/resources/application.yml`

**Checkpoint**: 7초 이내 회복하는 Expo 장애에서 유실 0.

---

## Phase 10: Polish & Cross-Cutting

- [x] T047 Run `./gradlew build` (arch 포함) and fix any `ModuleBoundaryTest`·`RepositoryLikeEscapeTest` regressions; confirm api·batch·common test context counts unchanged (1·1·1) via `-i` 로그
- [x] T048 [P] Update `specs/kb-471-scan-suggestion-batch/contracts/scan-suggestion-job.md` if any name (job/step/log/metric/property) drifted during implementation
- [ ] T049 [P] (보류 — 로컬 MySQL/Redis 미기동, api 마이그레이션 선행 필요) Local run per `specs/kb-471-scan-suggestion-batch/quickstart.md` §2: `:batch:bootRun` on 8081 with main `.env`, trigger `scanSuggestionPushJob`, confirm exitCode COMPLETED and `notification_dispatch` rows; (스케줄 발화는 12:00/18:00 상수라 로컬에서 기다리지 않는다 — 수동 트리거로 대체); note results in the PR body
- [x] T050 [P] Record decisions in `../kbap-agenthub/wiki/push-send-pipeline.md`(공용 `PushNotifier`·동시성 6·페이서 170ms·재시도 정책·채널 매핑·ttl) and add a line to `../kbap-agenthub/INDEX.md`; commit in the hub
- [x] T051 [P] Comment on Jira KB-471: ShedLock 제외(배치 1대), 대상 조건은 #260·#261 기준(게스트 쿼리 폐기·기기 `news` 토글), 기본 12:00 KST, 하루 1회 상한은 회원 단위 판정, 재시도·동시성 수치

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: 의존 없음. T001·T002 병렬 → T003
- **Foundational (Phase 2)**: T004 → T005 → T006 → T007 → T008. **모든 스토리를 막는다** (batch writer 가 `PushNotifier` 를 쓴다)
- **US1 (Phase 3)**: Phase 2 완료 후. 테스트 T009~T012 병렬 → 구현 T013·T014·T016·T017 병렬 → T015(T014 후) → T018(T013·T016 후) → T019(T017·T018 후) → T020
- **US2 (Phase 4)**: US1 후(tasklet 확장). T021·T022 병렬 → T023 → T024
- **US3 (Phase 5)**: US2 후(같은 tasklet). T025·T026·T027 병렬 → T028·T029 병렬 → T030
- **US4 (Phase 6)**: Phase 2 후면 가능(common 만) — US1 과 파일이 겹치므로(`PushRequest`·`PushMessage`·`ExpoPushSender`) US1 뒤에 순차 권장. T031·T032·T033 병렬 → T034 → T035
- **US5 (Phase 7)**: US1 후. T036·T037 → T038·T039
- **US6 (Phase 8)**: Phase 2 후 가능(common 어댑터) — US4 의 `ExpoPushSender` 변경 뒤 권장. T040·T041 병렬 → T042 → T043
- **US7 (Phase 9)**: US6 후(같은 `sendChunk`). T044 → T045 → T046
- **Polish (Phase 10)**: 전부 후. T048~T051 병렬

### User Story Dependencies

- US1 → US2 → US3: 같은 tasklet 을 순서대로 확장(각 단계가 독립 테스트 가능한 증분)
- US4·US6·US7: `ExpoPushSender` 를 순서대로 확장(channelId → 풀/페이서 → 재시도). 배치 잡과는 페이크 경계로 분리돼 US1~3 과 병렬 개발 가능(파일 충돌만 주의)
- US5: US1 의 잡 이름에만 의존

### Parallel Opportunities

- Phase 1: T001 ‖ T002
- US1 테스트: T009 ‖ T010 ‖ T011 ‖ T012; 구현: T013 ‖ T014 ‖ T016 ‖ T017
- US3: T025 ‖ T026 ‖ T027, T028 ‖ T029
- US4: T031 ‖ T032 ‖ T033
- 두 트랙 병행: [US1→US2→US3→US5](batch) ‖ [US4→US6→US7](common 어댑터) — 두 번째 트랙은 `PushMessage`·`ExpoPushNotifier` 를 US1 의 T015 뒤에 건드린다

---

## Implementation Strategy

### MVP First (US1 + US2 + US3 — P1 전부)

1. Phase 1·2 → Phase 3 (US1) → 수동 트리거로 발송 확인
2. Phase 4 (US2) → 시간대 가드 → Phase 5 (US3) → 재실행 안전
3. 여기까지가 운영 투입 최소 조건(법적 가드 + 과다 발송 방지)

### Incremental Delivery

4. US4(채널) → Android 헤드업 확인(FE KB-498 빌드 필요)
5. US5(스케줄·관측) → prod 스케줄 on 은 FE 기기 단위 설정 릴리스 이후
6. US6(동시성·페이싱) → US7(재시도) → Polish

### Notes

- 모든 배치 통합 테스트는 `ScanSuggestionPushJobTest` 한 클래스에 시나리오로 누적한다(컨텍스트 1개).
- `MockRestServiceServer` 동시성 시나리오(T040)가 불안정하면 그 시나리오만 JDK `HttpServer` 로 바꾼다 — 나머지 어댑터 시나리오는 그대로.
- Kotlin 주석 금지. 설계 근거는 research.md·커밋 메시지·위키(T050) 에 남긴다.
- 커밋은 phase 단위(`commit-after-task` 스킬). PR 은 `open-draft-pr-to-develop`.
