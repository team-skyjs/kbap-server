# Tasks: 주문 1시간 후 리뷰 리마인더 푸시 배치 (REVIEW_REMINDER)

**Input**: `specs/kb-469-review-reminder-batch/` — plan.md · spec.md · research.md · data-model.md · contracts/push-data-and-inbox.md · quickstart.md

**Tests**: 헌법 원칙 I(Test-First) — 각 스토리의 테스트를 먼저 쓰고 Red 를 확인한 뒤 구현한다. 전부 Kotest `BehaviorSpec`, 한국어 given/when/then.

## Format: `[ID] [P?] [Story] Description`

## Path Conventions

모듈러 모놀리스: `common/`(엔티티·리포지토리·문구) · `batch/`(잡 조합) · `api/`(알림함 응답). 소스 `src/main/kotlin/com/kbap/<module>/…`, 테스트 `src/test/kotlin/…` 미러.

---

## Phase 1: Setup

- [x] T001 `ScanSuggestionPushWriter.METRIC` 을 `batch/src/main/kotlin/com/kbap/batch/notification/PushDispatchMetric.kt` 의 `object PushDispatchMetric { const val NAME = "kbap.push.dispatch" }` 로 이관하고 `batch/src/main/kotlin/com/kbap/batch/notification/suggestion/ScanSuggestionPushWriter.kt` 와 `batch/src/test/kotlin/com/kbap/batch/notification/suggestion/ScanSuggestionPushJobTest.kt` 참조를 바꾼다

---

## Phase 2: Foundational — 대상 주문 조회 (US1·US2 공용)

- [x] T002 `common/src/test/kotlin/com/kbap/common/domain/order/OrderJpaRepositoryTest.kt` 에 `findReviewReminderTargets` 시나리오를 쓴다(기존 파일이 있으면 given 추가, 없으면 `@SpringBootTest` + `@Import(MySqlContainerConfig::class)` + `CommonTestApp`): 창 안 주문만(1h 미만·25h 초과 제외) · 주문 이후 `REVIEW_REMINDER` 알림 있으면 제외(주문 이전 알림은 무시) · 항목 전부 리뷰 있으면 제외, 일부만 있으면 포함 · 항목 0건 제외 · 소프트 삭제된 알림은 없는 것으로 봐 재대상 · `afterId` 커서 + `Limit` 페이징 오름차순. `created_at` 은 `JdbcTemplate` UPDATE 로 조정. 실행해 컴파일 실패(Red) 확인
- [x] T003 `common/src/main/kotlin/com/kbap/common/domain/order/OrderJpaRepository.kt` 에 research §1 JPQL `findReviewReminderTargets(from: LocalDateTime, to: LocalDateTime, type: NotificationType, afterId: Long, limit: Limit): List<Order>` 를 추가하고 T002 Green

---

## Phase 3: User Story 1 — 주문 1시간 뒤 리뷰 리마인더를 받는다 (P1) 🎯 MVP

**Goal**: 5분 잡이 창 안 주문마다 `REVIEW_REMINDER` 를 회원 기기로 보내고 알림함 행을 남긴다. data `{type, orderId(숫자), notificationId}`, 채널 `activity`, ttl 설정값, 메트릭 집계, HTTP 트리거.

**Independent Test**: 70분 전 주문 + 유효 기기·활동 토글 on 회원으로 잡 실행 → `FakePushClient.sent` 1건, data 에 `orderId` 숫자, 알림함 행 1건.

### Tests for User Story 1 ⚠️

- [x] T004 [P] [US1] `batch/src/test/kotlin/com/kbap/batch/notification/reminder/ReviewReminderOrderReaderTest.kt`(`@BatchIntegrationTest`, `MutableClock`): 250건 주문을 100씩 커서로 끝까지 읽는다 · 다시 open 하면 처음부터 · 창 밖(30분·26시간) 제외. Red 확인
- [x] T005 [P] [US1] `batch/src/test/kotlin/com/kbap/batch/notification/reminder/ReviewReminderPushJobTest.kt`(`@BatchIntegrationTest`): ① 70분 전 주문(항목 2, 리뷰 없음)·ko/en 기기 2대·활동 토글 on → COMPLETED, 알림 2행(type REVIEW_REMINDER), `data["orderId"]` 숫자 = 주문 id, `data["type"]`·`notificationId` 존재, `foodId` 키 없음, 봉투 `channelId == "activity"`, 제목에 `(광고)` 없음, `ttlSeconds == 21600`, 본문에 `{` 없음 ② 30분 전·26시간 전 주문은 0건 ③ `POST /internal/batch/jobs?jobName=reviewReminderPushJob` 202 → COMPLETED, `kbap.push.dispatch{type=REVIEW_REMINDER,result=sent}` 카운터 +1. `FakePushClient`·`clock`·`jdbcTemplate` 로 `orders.created_at` 조정. Red 확인

### Implementation for User Story 1

- [x] T006 [P] [US1] `batch/src/main/kotlin/com/kbap/batch/notification/reminder/ReviewReminderWindow.kt` — `object ReviewReminderWindow { MIN_AGE = Duration.ofHours(1); MAX_AGE = Duration.ofHours(25); fun of(clock: Clock): ClosedRange<LocalDateTime> }`(`clock.nowInJvmZone()` 기준)
- [x] T007 [US1] `batch/src/main/kotlin/com/kbap/batch/notification/reminder/ReviewReminderOrderReader.kt` — `ItemStreamReader<Order>`: open 에서 창 고정·커서 0·페이지 clear, read 에서 비면 `orderRepository.findReviewReminderTargets(from, to, REVIEW_REMINDER, cursor, Limit.of(pageSize))` 로 채우고 커서 = 마지막 id. T004 Green
- [x] T008 [US1] `batch/src/main/kotlin/com/kbap/batch/notification/reminder/ReviewReminderPushWriter.kt` — `ItemWriter<Order>`: 주문마다 `handler.send(PushRequest(REVIEW_REMINDER, listOf(memberId), data = mapOf("orderId" to order.id), ttlSeconds = ttl))`, sent/failed 를 `PushDispatchMetric.NAME` 에 `type=REVIEW_REMINDER` 로 누적, 로그 1줄
- [x] T009 [US1] `batch/src/main/kotlin/com/kbap/batch/notification/reminder/ReviewReminderPushBatchConfig.kt` — `@Configuration`, `@Value kbap.batch.review-reminder.chunk-size:100`·`ttl:6h`, `reviewReminderPushJob` 빈(`RunIdIncrementer`·`jobNameMdcListener`·`reviewReminderSendStep` chunk<Order,Order>), `companion { JOB_NAME; EVERY_5_MINUTES = "0 */5 * * * *" }`
- [x] T010 [US1] `batch/src/main/kotlin/com/kbap/batch/trigger/scheduler/BatchJobScheduler.kt` 에 `@Scheduled(cron = ReviewReminderPushBatchConfig.EVERY_5_MINUTES, zone = TIME_ZONE) fun pushReviewReminders() = launch(ReviewReminderPushBatchConfig.JOB_NAME)` 추가
- [x] T011 [US1] `batch/src/main/resources/application.yml` 의 `kbap.batch` 에 `review-reminder: { chunk-size: 100, ttl: 6h }` 와 설명 주석(yml 주석은 허용) 추가. T005 ①②③ Green

---

## Phase 4: User Story 2 — 같은 회원에게 리마인더가 두 번 가지 않는다 (P1)

**Goal**: 재실행·같은 회원 다중 주문·리뷰 완료 주문에 중복 발송이 없고, 실패 건은 다음 주기에 재시도된다.

**Independent Test**: 같은 주문으로 잡 두 번 실행 → 발송 1건.

### Tests for User Story 2 ⚠️

- [x] T012 [US2] `ReviewReminderPushJobTest.kt` 에 given 추가: ① 발송 후 알림 `created_at` 을 clock 으로 맞추고 5분 뒤 재실행 → 추가 발송 0 ② 같은 회원 70분·65분 전 주문 2건(같은 청크) → 발송 1건, data.orderId 는 먼저 만든 주문 ③ 항목 A·B 모두 리뷰 있음 → 0건, A 만 리뷰 → 1건 ④ 첫 실행에서 `FakePushClient.errorFor` 로 실패 → 알림 행 소프트 삭제 → 재실행 시 다시 발송. Red 확인(②만 실패해야 정상 — 나머지는 T003 쿼리로 이미 통과할 수 있음, 통과하면 그대로 둔다)

### Implementation for User Story 2

- [x] T013 [US2] `ReviewReminderPushWriter.write` 에서 `chunk.items.distinctBy { it.memberId }` 후 발송. T012 Green

---

## Phase 5: User Story 3 — 알림함에서 주문으로 간다 (P2)

**Goal**: 알림함 목록·읽음 응답에 `orderId`(REVIEW_REMINDER 만 non-null).

**Independent Test**: data `{orderId: 12}` 인 REVIEW_REMINDER 행 시드 → 목록·읽음 응답 `orderId == 12`, NEWS 행은 null.

### Tests for User Story 3 ⚠️

- [x] T014 [P] [US3] `common/src/test/kotlin/com/kbap/common/domain/notification/model/NotificationTest.kt` 에 `orderIdOrNull` given: Int → Long 값 · Long 그대로 · 정수 문자열 → 값 · 비정수 문자열/없음 → null · 타 유형 → null. Red 확인
- [x] T015 [P] [US3] `api/src/test/kotlin/com/kbap/api/notification/NotificationInboxTest.kt` 에 given 추가: 5종 유형 중 REVIEW_REMINDER(data orderId=12) 만 `orderId` 12 이고 나머지 null · 읽음 처리 응답에도 `orderId` 12 · orderId 없음/`"abc"` → null · 기존 foodId 케이스 유지. Red 확인

### Implementation for User Story 3

- [x] T016 [US3] `common/src/main/kotlin/com/kbap/common/domain/notification/model/Notification.kt` — `DATA_ORDER_ID = "orderId"`, `private fun longFromData(key: String): Long?` 로 변환 공유, `foodIdOrNull` 리팩터, `orderIdOrNull()` 추가. T014 Green
- [x] T017 [US3] `api/src/main/kotlin/com/kbap/api/notification/NotificationResponse.kt` — `orderId: Long?` 필드(`@field:Schema` — "주문 id — REVIEW_REMINDER 이면 발송 data.orderId, 그 외 null") 추가, `foodId` 설명을 "현재 어떤 발송처도 채우지 않아 항상 null(호환 유지)" 로 정정, `from()` 매핑. T015 Green
- [x] T018 [US3] `api/src/main/kotlin/com/kbap/api/notification/NotificationApi.kt` 목록·읽음 `@Operation` 설명·예시에 `orderId` 추가, `foodId` 문장을 미사용으로 정정

---

## Phase 6: User Story 4 — 주문 맥락의 문구를 본다 (P2)

**Goal**: 10개 언어 `push.review_reminder.{1,2,3}.body` 가 주문 맥락·`{food}` 없음. 제목 유지.

**Independent Test**: 모든 로케일에서 REVIEW_REMINDER 를 인자 없이 렌더해도 `{`·`}` 가 없고 본문이 비지 않는다.

### Tests for User Story 4 ⚠️

- [x] T019 [US4] `common/src/test/kotlin/com/kbap/common/domain/notification/PushMessageRendererTest.kt` 에 given: `LanguageCode.entries` × 변형 1~3 을 `pickVariant` 고정으로 렌더 → 제목·본문에 `{` 없음, 본문 공백 아님, ko 본문에 "스캔" 없음. Red 확인

### Implementation for User Story 4

- [x] T020 [US4] `common/src/main/resources/messages/push_{ko,en,es,ru,id,ja,th,vi,zh_Hans,zh_Hant}.properties` 의 `push.review_reminder.{1,2,3}.body` 를 contracts §4 한국어 초안과 그 번역으로 교체(`{food}` 제거, 제목 불변). T019 Green

---

## Phase 7: Polish & Cross-Cutting

- [x] T021 [P] `specs/kb-468-push-send-pipeline/contracts/push-data-contract.md` — `orderId`(number, REVIEW_REMINDER 필수, 배치 라이터) 행 추가, `foodId` 행을 "미사용 — 채우는 발송처 없음" 으로 정정, 예시 JSON 갱신
- [x] T022 [P] Jira KB-469 코멘트: 푸시 data `orderId` 추가·`foodId` 미사용 정정·게스트 주문 대상 아님·KB-500 전 앱은 리마인더 탭 착지 없음(배치 prod 활성은 KB-500 릴리스에 맞춤)
- [x] T023 `./gradlew :common:test :batch:test :api:test` 전체 통과 확인(arch 포함), 커밋

---

## Dependencies & Execution Order

- Phase 1 → Phase 2 → US1(Phase 3) → US2(Phase 4). US3·US4 는 Phase 1 뒤 언제든 독립 진행 가능(US1 과 파일이 겹치지 않음).
- US2 는 US1 의 잡 테스트 파일·라이터를 확장하므로 US1 뒤.
- Polish 는 전부 끝난 뒤.

### Parallel Opportunities

- T004 ‖ T005(테스트 파일 둘) · T006 ‖ T007 은 순차(리더가 창 사용)
- T014 ‖ T015 · T019 는 US3 과 병행 가능
- T021 ‖ T022

## Implementation Strategy

MVP = Phase 1~3(US1). US2 는 라이터 한 줄 + 테스트라 바로 이어 붙인다. US3·US4 는 각각 독립 커밋.
