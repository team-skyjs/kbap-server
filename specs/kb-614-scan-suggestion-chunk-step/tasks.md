# Tasks: 스캔 제안 배치 단일 청크 스텝 재구성 + Expo 영수증 확인·재전송

**Input**: Design documents from `/specs/kb-614-scan-suggestion-chunk-step/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/expo-push-receipts.md, quickstart.md

**Tests**: Test-First 는 **NON-NEGOTIABLE**(헌법 원칙 I). 모든 스토리는 실패하는 테스트를 먼저 쓰고 Red 를 확인한 뒤 구현한다. 스타일은 Kotest `BehaviorSpec`(given/when/then 한국어). 통합 헤더는 `@BatchIntegrationTest`(batch)·`@SpringBootTest + @Import(MySqlContainerConfig::class)`(common)·`@IntegrationTest`(api) 고정 — 테스트 클래스에 `@SpringBootTest(...)`·`properties` 를 직접 쓰지 않는다. Kotlin 소스 주석 금지. 테스트를 위해 운영 코드의 가시성을 넓히지 않는다. Kotest 는 Gradle `--tests` 필터를 무시하므로 검증은 모듈 단위(`./gradlew :common:test` 등)로 돌린다.

**Organization**: 스토리별 phase(spec.md 우선순위 순). 실제 작업 순서와 PR 분할은 맨 아래 "Implementation Strategy" 를 따른다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: US1~US8 (spec.md)
- 경로는 저장소 루트 기준

## Path Conventions

- common: `common/src/main/kotlin/com/kbap/common/{port,infra,domain}/...`, 테스트 `common/src/test/kotlin/com/kbap/common/...`
- batch: `batch/src/main/kotlin/com/kbap/batch/{notification,config,schedule}/...`, 테스트 `batch/src/test/kotlin/com/kbap/batch/...`
- api: `api/src/main/kotlin/com/kbap/api/core/config/...`, 마이그레이션 `api/src/main/resources/db/migration/`

---

## Phase 1: Setup (영수증 포트와 배치 테스트 페이크 — 컨텍스트 1개 유지)

**Purpose**: 영수증 잡 통합 테스트가 Expo 를 부르지 않게 하는 픽스처. `@BatchIntegrationTest` 의 `@Import` 에 묶어 Spring 컨텍스트가 늘지 않게 한다(KB-392).

- [ ] T001 Create port `data class PushReceipt(val ok: Boolean, val errorCode: String? = null, val message: String? = null)` + `fun interface PushReceiptFetcher { fun fetch(ticketIds: List<String>): Map<String, PushReceipt> }` in `common/src/main/kotlin/com/kbap/common/port/push/PushReceiptFetcher.kt` (Spring·JPA 의존 금지 — ArchUnit port 규칙)
- [ ] T002 Create `FakePushReceiptFetcher`(`PushReceiptFetcher` 구현 — `receipts: MutableMap<String, PushReceipt>` 에 든 것만 돌려줌, `failWith: Throwable?` 가 있으면 던짐, 받은 id 기록 `requested`, `reset()`) + `@TestConfiguration FakePushReceiptFetcherConfig`(`@Bean @Primary`) in `batch/src/test/kotlin/com/kbap/batch/notification/FakePushReceiptFetcherConfig.kt` — 같은 폴더 `FakePushSenderConfig.kt` 와 같은 모양 (depends on T001)
- [ ] T003 Add `FakePushReceiptFetcherConfig::class` to the `@Import` of `batch/src/test/kotlin/com/kbap/batch/BatchIntegrationTest.kt` (depends on T002)

---

## Phase 2: Foundational — 발송 이력에 알림 유형 기록 (FR-024)

**Purpose**: 영수증 잡 두 개가 자기 몫을 고르는 기준. US3·US4·US5 가 전부 이 컬럼에 의존한다. **US1·US2·US6·US8 은 이 phase 와 무관하다.**

- [ ] T004 Write failing test in `common/src/test/kotlin/com/kbap/common/domain/notification/PushDispatchServiceTest.kt`: `prepare(PushRequest(NEWS, …))` 로 만들어진 발송 이력의 `notificationType` 이 `NEWS` 다. Red 확인(컴파일 실패)
- [ ] T005 Create migration `api/src/main/resources/db/migration/V<파일 생성 시각 yyyy.MM.dd.HH.mm.ss>__notification_dispatch_type.sql` — data-model.md 의 SQL 그대로(`ADD COLUMN notification_type VARCHAR(30) NULL AFTER notification_device_id` + `UPDATE … JOIN notification … WHERE d.notification_type IS NULL`). 정수 버전 금지, 다른 마이그레이션에 순서 의존 금지
- [ ] T006 Add `@Enumerated(EnumType.STRING) @Column(name = "notification_type", length = 30) var notificationType: NotificationType? = null` and extend `pending(notificationId, notificationDeviceId, expoToken, notificationType)` in `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationDispatch.kt`; pass `request.type` in `PushDispatchService.prepare` (`common/src/main/kotlin/com/kbap/common/domain/notification/PushDispatchService.kt`); fix every other `NotificationDispatch.pending(` caller (`grep -rn "NotificationDispatch.pending(" --include="*.kt" .`)
- [ ] T007 Run `./gradlew :common:test :api:test` — T004 Green, api 컨텍스트가 마이그레이션 + `ddl-auto=validate` 를 통과

**Checkpoint**: 새 발송은 유형을 남기고 기존 행은 채워진다.

---

## Phase 3: User Story 1 - 회원이 늘어도 발송 배치 메모리가 커지지 않는다 (P1)

**Goal**: 대상 회원을 `member_id` 커서로 100건씩 읽는 리더.

**Independent Test**: `ScanSuggestionMemberIdReaderTest` — 250명을 오름차순으로 다 읽고 null. (잡 수준 100·100·50 검증은 스텝 조립이 끝나는 T016 뒤 T014 가 한다.)

- [x] T008 [US1] Write failing cases in `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepositoryTest.kt` for `findMemberIdsByNewsTrueAfter(afterMemberId, Limit.of(n))`: 커서 초과만·오름차순·limit 준수·기기 2대 회원은 1회(distinct)·`news=false` 제외·소프트 삭제 설정 제외. Red 확인
- [x] T009 [US1] Add `@Query("select distinct s.memberId from NotificationSetting s where s.news = true and s.memberId > :afterMemberId order by s.memberId") fun findMemberIdsByNewsTrueAfter(@Param("afterMemberId") afterMemberId: Long, limit: Limit): List<Long>` to `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationSettingJpaRepository.kt` — `Limit` 이 JPQL `@Query` 와 동작하지 않으면 `Pageable`(`PageRequest.of(0, n)`)로 바꾸고 T008 도 맞춘다
- [x] T010 [US1] Write failing `ScanSuggestionMemberIdReaderTest`(`@BatchIntegrationTest`) in `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionMemberIdReaderTest.kt`: (a) news 켠 회원 250명 → `open()` 후 `read()` 250회가 오름차순, 251번째 null (b) 0명 → 즉시 null (c) 다 읽은 뒤 `open()` 재호출 → 처음부터 다시. 시드·정리는 `ScanSuggestionPushJobTest` 의 헬퍼 방식. Red 확인
- [x] T011 [US1] Create `ScanSuggestionMemberIdReader(settingRepository, pageSize) : ItemStreamReader<Long>` in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionMemberIdReader.kt` — `batch/src/main/kotlin/com/kbap/batch/vector/FoodVectorOutboxItemReader.kt` 와 같은 구조(`cursor`·`ArrayDeque`·`exhausted`, `open()` 초기화, `require(pageSize > 0)`), 커서는 마지막으로 읽은 `memberId`

**Checkpoint**: 리더가 한 페이지(≤100)만 메모리에 든다.

---

## Phase 4: User Story 2 - 같은 슬롯에 두 번 받지 않는다 (P1)

**Goal**: 프로세서로 슬롯 중복을 거르고, 발송 잡을 청크 스텝 하나로 조립한 뒤 구 구조를 지운다. **US1 의 리더에 의존한다**(스텝 조립).

**Independent Test**: `ScanSuggestionPushJobTest` — 같은 슬롯 재실행 발송 0건, 250명 100·100·50, 묶음 일부만 기수신이면 나머지만.

- [x] T012 [US2] Write failing cases in `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationJpaRepositoryTest.kt` for `existsByMemberIdAndTypeAndCreatedAtAfter`: 슬롯 이후 활성 행 true / 슬롯 이전 행·다른 유형·다른 회원·소프트 삭제 행 false. Red 확인
- [x] T013 [US2] Add derived query `fun existsByMemberIdAndTypeAndCreatedAtAfter(memberId: Long, type: NotificationType, since: LocalDateTime): Boolean` to `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationJpaRepository.kt`
- [x] T014 [US2] Update `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushJobTest.kt` (Red 확인 — 지금은 500 묶음·2스텝): (a) "회원 1,200명" 시나리오를 250명으로 줄이고 `FakePushSender` 가 받은 호출 묶음이 100·100·50(회원당 기기 1대)임을 검증 — 호출 단위 기록이 없으면 `FakePushSender` 에 `batches: List<Int>` 추가 (b) 한 묶음 100명 중 40명이 이번 슬롯 기수신이면 60건만 발송 (c) 실행 기록의 스텝이 `scanSuggestionLunchSendStep` 하나이고 `readCount`·`filterCount` 가 대상 수·제외 수와 일치. 기존 시나리오(대상 판정·슬롯 상한·HTTP 트리거·저녁 문구)는 그대로 둔다
- [x] T015 [US2] Create `ScanSuggestionSlotFilter(notificationRepository, clock) : ItemProcessor<Long, Long>` in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionSlotFilter.kt` — `memberId.takeUnless { exists(it, SCAN_SUGGESTION, ScanSuggestionSendWindow.startOfCurrentSlot(clock)) }`
- [x] T016 [US2] Rewire `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionPushBatchConfig.kt`: `@Value("\${kbap.batch.scan-suggestion.chunk-size:100}")`, `job(slot, …)` 가 슬롯마다 새 리더·프로세서·라이터로 `chunk<Long, Long>(chunkSize)` + 주입받은 `PlatformTransactionManager` 스텝 하나를 만들고 `start(sendStep)` 만 한다. `scanSuggestionTargetTasklet`·`scanSuggestionTargetStep`·`scanSuggestionCandidateReader` 빈과 `ResourcelessTransactionManager` 제거. 잡·스텝 이름·`RunIdIncrementer`·`jobNameMdcListener`·`clock` 빈 불변. `batch/src/main/resources/application.yml` 의 `member-chunk-size: 500` → `chunk-size: 100`, 주석을 새 의미로
- [x] T017 [US2] Delete `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionCandidateDto.kt`, `ScanSuggestionTargetTasklet.kt`; remove `findMemberIdsByNewsTrue` (`NotificationSettingJpaRepository.kt`), `findMemberIdsByTypeAndCreatedAtAfter` (`NotificationJpaRepository.kt`) and their test cases in the two repository tests
- [x] T018 [US2] Run `./gradlew :common:test :batch:test` — T008·T010·T012·T014 Green, `ScanSuggestionPushBatchConfigTest` 무수정 통과

**Checkpoint**: 발송 잡 = 스텝 1개·버퍼 0개. 재실행이 멱등.

---

## Phase 5: User Story 3 - 실제로 전달됐는지를 영수증으로 확정한다 (P1)

**Goal**: 영수증 조회 어댑터, 결과 확정 도메인 서비스, 영수증 잡(재전송 제외). **Phase 1·2 에 의존한다.**

**Independent Test**: `PushReceiptSyncJobTest` — `SENT` 이력을 시드하고 페이크 영수증을 꾸며 잡을 실행하면 상태·사유·토큰 무효화가 spec "영수증 결과별 처리" 표대로.

- [ ] T019 [P] [US3] Write failing `ExpoPushReceiptFetcherTest` in `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushReceiptFetcherTest.kt`(`ExpoPushSenderTest` 의 로컬 HTTP 서버 방식): `contracts/expo-push-receipts.md` 의 포트 매핑 표 전 행 — ok / `details.error` 있는 오류 / 코드 없는 오류 / 응답에 없는 id 는 키 없음 / 1,500 id 는 1000·500 두 요청 / 5xx 는 예외 전파 / 요청 본문 `{"ids":[…]}`·경로 `/--/api/v2/push/getReceipts`·토큰 있을 때만 Bearer. Red 확인
- [ ] T020 [US3] Extract `internal fun expoRestClient(baseUrl: String, accessToken: String, builder: RestClient.Builder): RestClient`(JSON 컨버터·baseUrl·Bearer) from `ExpoPushSender.create` into `common/src/main/kotlin/com/kbap/common/infra/push/ExpoRestClient.kt` and make `ExpoPushSender` use it; create `ExpoPushReceiptFetcher(restClient) : PushReceiptFetcher` with `create(baseUrl, accessToken)`·`internal create(…, builder)` in `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushReceiptFetcher.kt` — `chunked(1000)`, 재시도 없음, 연결 2초·읽기 10초 타임아웃은 발송기와 동일 (US8 의 T043 과 같은 파일을 건드린다 — 순서는 Implementation Strategy)
- [ ] T021 [P] [US3] Write failing cases in `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationDispatchJpaRepositoryTest.kt` for `findReceiptTargets(status, types, from, to, afterId, limit)`: `SENT` 만·유형 일치만·`from`/`to` 경계 포함·id 커서 초과만·오름차순·limit·유형 NULL 행 제외; `findByNotificationIdIn` 은 알림별 이력 전부. 기존 `findByDispatchStatusAndCreatedAtBefore` 케이스는 삭제. Red 확인
- [ ] T022 [US3] Add the two queries (plan.md D4 의 JPQL) and delete unused `findByDispatchStatusAndCreatedAtBefore` in `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationDispatchJpaRepository.kt`
- [ ] T023 [US3] Write failing `PushReceiptServiceTest`(`@SpringBootTest + @Import(MySqlContainerConfig::class)`, 시드 헬퍼는 `PushDispatchServiceTest` 방식) in `common/src/test/kotlin/com/kbap/common/domain/notification/PushReceiptServiceTest.kt` — spec US3 수용 시나리오 1~4: ok→`DELIVERED`·알림함 유지 / `DeviceNotRegistered`→`FAILED`(사유)·기기 `tokenInvalidAt` 세팅·기기 행 유지·알림함 소프트 삭제·재전송 0 / `MessageTooBig`·`MismatchSenderId`·`InvalidCredentials`·모르는 코드→`FAILED`·알림함 소프트 삭제·재전송 0 / `outcomes` 에 없는 이력은 `SENT` 그대로. `tally` 가 유형별 `DELIVERED`·`FAILED` 건수와 일치. `CommonTestApp` 이 새 서비스를 스캔하는지 확인. Red 확인
- [ ] T024 [US3] Create `PushReceiptService` + `ReceiptOutcome`·`ResendPolicy`·`ReceiptResult`·`ReceiptApplyResult` in `common/src/main/kotlin/com/kbap/common/domain/notification/PushReceiptService.kt` — plan.md D3 의 1·2·3·5 단계(`@Transactional fun apply(outcomes, policy, now)`). 이 phase 에서는 모든 오류를 최종 실패로 닫고 `resend` 는 빈 `PreparedPush` — 재전송 분기는 US4(T031). 포트 타입(`PushReceipt`)을 import 하지 않는다(ArchUnit: 도메인→포트 금지). 오류 코드 상수는 companion
- [ ] T025 [US3] Write failing `PushReceiptSyncJobTest`(`@BatchIntegrationTest`, `MutableClock`·`FakePushReceiptFetcher`·`FakePushSender`, 발송 이력 `created_at` 은 `ScanSuggestionPushJobTest.stampCreatedAtToClock` 처럼 JDBC 로 조정) in `batch/src/test/kotlin/com/kbap/batch/notification/PushReceiptSyncJobTest.kt` — spec US3 시나리오 1·2·4·5·6·7: 15분 지난 `SENT`+ok→`DELIVERED` / `DeviceNotRegistered` 전 과정 / 영수증 없음→`SENT` 유지 후 다음 실행에서 처리 / 14분 된 이력 미조회(`requested` 에 없음) / 25시간 된 이력 미조회·상태 불변 / 대상 250건 전부 처리 + 페이크가 예외를 던지는 실행은 전 이력 `SENT` 유지·잡 `COMPLETED`. Red 확인
- [ ] T026 [US3] Create `PushReceiptTargetReader(dispatchRepository, types, clock, minAge, maxAge, pageSize) : ItemStreamReader<NotificationDispatch>` in `batch/src/main/kotlin/com/kbap/batch/notification/PushReceiptTargetReader.kt` — `open()` 에서 시간 창(`now - maxAge` ~ `now - minAge`) 1회 계산·커서 초기화, id 커서 100건씩(`ScanSuggestionMemberIdReader` 와 같은 구조)
- [ ] T027 [US3] Create `PushReceiptSyncWriter(fetcher, receiptService, dispatchService, sender, policy, clock, meterRegistry) : ItemWriter<NotificationDispatch>` in `batch/src/main/kotlin/com/kbap/batch/notification/PushReceiptSyncWriter.kt` — `fetch(ticketIds)` 를 try/catch(경고 로그 후 return), 티켓 id→이력 id 로 바꿔 `ReceiptOutcome` 맵을 만들어 `receiptService.apply`. `resend` 가 비어 있지 않으면 `sender.send` → `dispatchService.record`(US4 에서 실제로 쓰인다). 메트릭은 US7(T040)
- [ ] T028 [US3] Create `PushReceiptSyncBatchConfig` in `batch/src/main/kotlin/com/kbap/batch/notification/PushReceiptSyncBatchConfig.kt` — `@Value` 5개(`kbap.batch.push-receipt.{chunk-size:100,min-age:15m,max-age:24h,resend-window:2h,max-resends:2}`), `job(marketing: Boolean, …)` 팩토리로 `marketingPushReceiptSyncJob`·`activityPushReceiptSyncJob`(스텝 `…PushReceiptSyncStep`, `chunk(chunkSize)`, `ResourcelessTransactionManager`, 대상 유형 = `NotificationType.entries.filter { it.marketing == marketing }`, `RunIdIncrementer`, `jobNameMdcListener`), companion `jobNameOf(marketing)`. `batch/src/main/kotlin/com/kbap/batch/config/PushConfig.kt` 에 `@Import` 로 `PushReceiptService` 추가 + `@Bean @ConditionalOnMissingBean(PushReceiptFetcher::class) fun pushReceiptFetcher(base-url, access-token)`. `batch/src/main/resources/application.yml` 에 `push-receipt` 블록
- [ ] T029 [US3] Run `./gradlew :common:test :batch:test :api:test` — T019·T021·T023·T025 Green, `ModuleBoundaryTest`(arch) 통과

**Checkpoint**: `SENT` 가 `DELIVERED`/`FAILED` 로 확정되고 죽은 토큰이 다음 발송에서 빠진다.

---

## Phase 6: User Story 4 - 다시 보내면 도달할 알림은 그 기기에만 재전송한다 (P1)

**Goal**: 재전송 판정과 기기 단위 재전송. **US3 에 의존한다.**

**Independent Test**: `MessageRateExceeded` 3연속 → 발송 이력 3건 `FAILED`·네 번째 발송 없음·알림함 소프트 삭제. 두 번째 ok → `FAILED`·`DELIVERED` 2건·알림함 유지.

- [ ] T030 [US4] Add failing cases to `common/src/test/kotlin/com/kbap/common/domain/notification/PushReceiptServiceTest.kt` — spec US4 시나리오 1·2·3·4·5·7: `MessageRateExceeded` 와 코드 없는 오류 각각 → 기존 이력 `FAILED`(사유)·같은 `notificationId` 의 새 `PENDING` 이력 1건(유형 기록됨)·알림함 활성·`resend.messages` 1건(제목·본문·데이터가 알림함 행과 같고 `channelId = type.channelId`) / 기기 2대 중 1대만 대상이면 봉투는 그 기기 토큰 1건 / 이력이 이미 3건이면 재전송 없이 알림함 소프트 삭제 / 알림함 `createdAt` 이 2시간 1분 전이면 재전송 없음 / 재전송 시점에 토큰 무효·`news` 토글 꺼짐·동의 철회 각각 → 재전송 없음 / 광고성 봉투 `ttlSeconds` = `createdAt + window - now` 초, `HELPFUL` 은 null / 그 사이 기기 토큰이 바뀌었으면 봉투 `to` 는 새 토큰. `tally` 의 `RESENT`. Red 확인
- [ ] T031 [US4] Implement resend branch(plan.md D3 의 4단계) in `common/src/main/kotlin/com/kbap/common/domain/notification/PushReceiptService.kt` — 알림별 이력 개수는 `findByNotificationIdIn`, 자격은 유형별로 묶어 `PushTargetResolver.resolve(memberIds, type)` 후 `notificationDeviceId` 로 대조, 새 이력은 `NotificationDispatch.pending(…, type)`. 별도 카운터 컬럼·새 상태 금지
- [ ] T032 [US4] Add failing cases to `batch/src/test/kotlin/com/kbap/batch/notification/PushReceiptSyncJobTest.kt`: (a) 영수증을 매번 `MessageRateExceeded` 로 꾸미고 시계를 20분씩 밀며 잡 3회 → `FakePushSender` 재전송 2건·발송 이력 3건 전부 `FAILED`·알림함 소프트 삭제·4회째 실행은 아무것도 안 함 (b) 두 번째 영수증 ok → 이력 `FAILED`·`DELIVERED`·알림함 활성 (c) 재전송 메시지의 `to` 는 그 기기 토큰뿐 (d) `FakePushSender.errorFor` 로 재전송 접수 실패 → 새 이력 `FAILED`·알림함 소프트 삭제(US4-6). Red 확인 후 T027 의 재전송 경로가 통과시키는지 확인, 부족하면 `PushReceiptSyncWriter.kt` 보완
- [ ] T033 [US4] Add case to `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionPushJobTest.kt` (spec US2-4): 재전송 진행 중(직전 이력 `FAILED`, 새 이력 `SENT`, 알림함 활성)인 회원은 같은 슬롯 발송 잡 재실행에서 걸러진다 — 운영 코드 변경 없이 통과해야 한다(회귀 그물)
- [ ] T034 [US4] Run `./gradlew :common:test :batch:test`

**Checkpoint**: 한 알림이 한 기기로 나가는 횟수 ≤ 3.

---

## Phase 7: User Story 5 - 광고성과 활동 알림을 서로 다른 리듬으로 확인한다 (P2)

**Goal**: 두 잡의 몫 분리 검증과 스케줄. **US3 에 의존한다.**

**Independent Test**: 유형을 섞어 시드하고 한 잡만 돌리면 그 잡의 유형만 바뀐다.

- [ ] T035 [US5] Add cases to `batch/src/test/kotlin/com/kbap/batch/notification/PushReceiptSyncJobTest.kt` (spec US5-1·4): `SCAN_SUGGESTION`·`HELPFUL` `SENT` 이력을 함께 두고 `marketingPushReceiptSyncJob` 만 실행 → 스캔 제안만 `DELIVERED`, `activityPushReceiptSyncJob` 은 그 반대 / JDBC 로 `notification_type = NULL` 로 만든 이력은 두 잡 모두 건드리지 않고 `COMPLETED`. T028 구현으로 통과해야 한다 — 실패하면 `PushReceiptSyncBatchConfig.kt` 수정
- [ ] T036 [US5] Add to `batch/src/main/kotlin/com/kbap/batch/schedule/BatchJobScheduler.kt`: `fun syncMarketingPushReceipts()` 에 `@Scheduled(cron = "0 0/10 11-12,17-18 * * *", zone)` + `@Scheduled(cron = "0 0 13,19 * * *", zone)`, `fun syncActivityPushReceipts()` 에 `@Scheduled(cron = "0 0/15 * * * *", zone)` — cron 문자열은 `PushReceiptSyncBatchConfig` companion 상수. 스케줄 표현식은 테스트하지 않는다(프레임워크 설정 — quickstart 의 기동 로그 확인). `ScanSuggestionPushBatchConfigTest` 와 같은 방식으로 두 잡 빈 이름을 확인하는 케이스를 `batch/src/test/kotlin/com/kbap/batch/notification/PushReceiptSyncBatchConfigTest.kt` 에 추가

**Checkpoint**: 광고성은 발송 후 2시간 창에만, 활동은 상시.

---

## Phase 8: User Story 6 - 식사 제안이 11시·17시에 온다 (P2)

**Goal**: 발송 시각과 슬롯 경계 이동. **다른 스토리와 독립.**

**Independent Test**: `ScanSuggestionSendWindowTest` 가 11:00·17:00 경계로 통과.

- [x] T037 [US6] Update `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionSendWindowTest.kt` to 11:00·17:00 경계(10:59→전날 17:00, 11:00→오늘 11:00, 16:59→오늘 11:00, 17:00→오늘 17:00, JVM 시간대 변환 케이스 유지), `given` 설명의 시각 문구 갱신. Red 확인
- [x] T038 [US6] Change `LUNCH(LocalTime.of(11, 0))`·`DINNER(LocalTime.of(17, 0))` in `common/src/main/kotlin/com/kbap/common/domain/notification/model/MealSlot.kt`; `LUNCH_CRON = "0 0 11 * * *"`·`DINNER_CRON = "0 0 17 * * *"` in `batch/src/main/kotlin/com/kbap/batch/notification/ScanSuggestionSendWindow.kt`; update 시각 리터럴·설명 in `ScanSuggestionPushJobTest.kt`(12:00/18:00 → 11:00/17:00) and the scan-suggestion 주석 in `batch/src/main/resources/application.yml`. `grep -rn "12:00\|18:00" batch/src common/src/main/kotlin/com/kbap/common/domain/notification` 로 잔여 확인

---

## Phase 9: User Story 7 - 점심·저녁 잡이 같은 구성을 쓰고 기록 기준이 유지된다 (P2)

**Goal**: 영수증 잡의 메트릭·로그, 발송 기록 기준 불변 확인. **US3·US4 에 의존한다.**

**Independent Test**: 잡 실행 뒤 `kbap.push.receipt` 카운터가 결과와 일치, `kbap.push.dispatch` 는 이름·태그 불변.

- [ ] T039 [US7] Add failing case to `batch/src/test/kotlin/com/kbap/batch/notification/PushReceiptSyncJobTest.kt`: 전달 2·최종 실패 1·재전송 1·영수증 없음 1 을 꾸며 실행 → `kbap.push.receipt{type=SCAN_SUGGESTION, result=delivered|failed|resent|pending}` 증가분이 2·1·1·1. Red 확인
- [ ] T040 [US7] Implement metric + 청크 로그(유형별 delivered·failed·resent·pending) in `batch/src/main/kotlin/com/kbap/batch/notification/PushReceiptSyncWriter.kt` — `METRIC = "kbap.push.receipt"` companion 상수, `pending` = 청크 크기 − 영수증 수
- [ ] T041 [US7] Verify(수정 없음) `ScanSuggestionPushJobTest` 의 저녁 문구·`kbap.push.dispatch` 카운터·HTTP 트리거 시나리오가 그대로 통과 — spec US7-1·2

---

## Phase 10: User Story 8 - 발송은 100건 한 요청씩 차례로 나간다 (P2)

**Goal**: 발송기에서 스레드 풀·요청 간격 슬롯 제거. **다른 스토리와 독립(단 `ExpoPushSender.kt` 를 T020 과 공유).**

**Independent Test**: `ExpoPushSenderTest` — 250건 발송 중 동시에 진행 중인 요청 최대 1.

- [x] T042 [US8] Update `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushSenderTest.kt`: "청크 12개를 동시성 6·간격 0 으로" · "청크 12개를 간격 100ms 로" 두 `when` 삭제; "250건을 보내면" 에 로컬 서버가 센 동시 진행 요청 최대값 = 1 검증 추가(응답에 짧은 지연); 모든 `ExpoPushSender.create(…)` 호출에서 `concurrency`·`minRequestInterval` 인자와 `.use { }` 제거. Red 확인(컴파일 실패)
- [x] T043 [US8] Simplify `common/src/main/kotlin/com/kbap/common/infra/push/ExpoPushSender.kt` — plan.md A 의 제거 목록 전부, `send = messages.chunked(CHUNK_SIZE).flatMap(::sendChunk)`, `post` 에서 `awaitSlot()` 호출 제거
- [x] T044 [P] [US8] Remove `concurrency`·`min-request-interval` `@Value` 인자와 `destroyMethod = "close"` from `batch/src/main/kotlin/com/kbap/batch/config/PushConfig.kt`; remove the two keys + 주석 from `batch/src/main/resources/application.yml`
- [x] T045 [P] [US8] Same cleanup in `api/src/main/kotlin/com/kbap/api/core/config/PushConfig.kt` and `api/src/main/resources/application.yml`
- [ ] T046 [US8] Run `./gradlew :common:test :batch:test :api:test` — api 의 도움돼요·관리자 테스트 발송 시나리오가 수정 없이 통과(FR-011)

---

## Phase 11: Polish & Cross-Cutting

- [ ] T047 Run quickstart.md 의 삭제 확인 grep(결과 없음) + `./gradlew build`
- [ ] T048 [P] Review — `kbap-code-review` 스킬(전체 diff)과 `kbap-db-review` 스킬(마이그레이션·새 쿼리 3개·인덱스 사용) 호출, 지적 반영
- [ ] T049 [P] Update 지식 위키 `../kbap-agenthub/wiki/push-send-pipeline.md`(+ `INDEX.md` 한 줄 요약): 발송 이력 상태 정의(24시간 경과 `SENT` = 미확인 종결), 영수증 잡 2개와 시간표, 재전송 규칙(알림당 2회·2시간·기기 단위·자격 재확인), 발송 시각 11:00·17:00, 병렬 발송·페이싱 폐기, 발송 잡의 트랜잭션 안 Expo 호출 예외와 감수 위험, Jira 전제 불일치(시간대 가드·ShedLock 없음), 후속(`notification_type` NOT NULL). 허브에서 커밋
- [ ] T050 PR 본문에 배포 주의 기재(quickstart.md "배포 시 주의" — api 먼저·발송 시각 밖 배포·NULL 유형 행) 후, 사용자 확인을 받아 Jira KB-473 에 "KB-614 로 흡수" 코멘트와 종료 처리

---

## Dependencies & Execution Order

```text
Phase 1 Setup ──┐
Phase 2 Found. ─┴─▶ US3 ─▶ US4 ─▶ US7
                      └──▶ US5
US1 ─▶ US2            (Phase 1·2 와 무관)
US6                   (독립)
US8                   (독립 — 단 T043 과 T020 은 같은 파일)
```

- US2 의 스텝 조립(T016)은 US1 의 리더(T011)가 있어야 한다.
- US4·US5·US7 은 US3 의 잡(T028)이 있어야 한다.
- T033(US2-4 회귀)은 US2 와 US4 가 모두 끝나야 돈다.
- 같은 파일 충돌: `ExpoPushSender.kt`(T020·T043), `PushReceiptSyncJobTest.kt`(T025·T032·T035·T039 — 순차), `batch/application.yml`(T016·T028·T038·T044 — 순차), `batch PushConfig.kt`(T028·T044).

### Parallel Opportunities

- Phase 5 시작 시 T019(어댑터 테스트)와 T021(리포지토리 테스트)은 다른 파일이라 병렬.
- T044(batch 설정)와 T045(api 설정)는 병렬.
- US6(T037·T038)과 US8(T042~T046)은 서로·다른 스토리와 독립이라 아무 때나 끼워 넣을 수 있다.
- 구현은 메인 세션이 직접 한다(멀티에이전트 구현 금지) — 병렬 표시는 순서 자유도를 뜻한다.

## Implementation Strategy

**권장 순서와 PR 분할** — 리뷰 단위를 작게, 배포 위험을 분리한다.

1. **PR 1 — 재구성(스키마 변경 없음)**: US8 → US1 → US2 → US6. 발송기 단순화를 먼저 해 두면 T020 의 `RestClient` 추출이 깨끗한 파일 위에서 된다. 이 PR 만으로 Jira KB-614 원래 DoD 가 충족된다(MVP).
2. **PR 2 — 영수증 확인·재전송**: Phase 1 → Phase 2 → US3 → US4 → US5 → US7 → Polish. 마이그레이션이 들어가므로 배포는 api 먼저.

US6(발송 시각)을 PR 1 에 넣으면 영수증 잡이 없는 기간에도 11:00·17:00 에 나간다. 시각 변경을 영수증 잡과 같은 날 내보내고 싶으면 US6 을 PR 2 로 옮긴다 — 코드 의존은 없다.

**MVP**: PR 1(US1·US2·US8). **P1 전체**: + Phase 1·2·US3·US4.

각 태스크는 Red 확인 → 최소 구현 → 모듈 테스트 Green → 논리 단위 커밋 순으로 진행한다.
