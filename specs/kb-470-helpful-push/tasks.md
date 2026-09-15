# Tasks: HELPFUL 발송 — 리뷰 좋아요 시 작성자에게 알림

**Input**: Design documents from `/specs/kb-470-helpful-push/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/helpful-push.md, quickstart.md

**Tests**: Test-First is **NON-NEGOTIABLE** (Constitution Principle I). 모든 스토리는 실패하는 테스트를 먼저 쓰고(Red 확인) 구현한다. 테스트 스타일은 Kotest `BehaviorSpec`(given/when/then 한국어), 통합 헤더는 api `@IntegrationTest`·common `@SpringBootTest + @Import(MySqlContainerConfig::class)`(`CommonTestApp`) 고정 — 새 컨텍스트를 만들지 않는다. 비동기 검증은 Kotest `eventually(5.seconds)`(긍정)·`continually(1.seconds)`(부정). Kotlin 소스 주석 금지.

**Organization**: 스토리별 phase. 공용 파이프라인 변경(채널 매핑·언어별 인자)은 모든 스토리가 쓰므로 Foundational. 이벤트·리스너는 US1 에서 만들고 US2·US3 는 조건을 더한다.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: 병렬 가능(다른 파일, 미완 태스크 의존 없음)
- **[Story]**: US1~US3 (spec.md)
- 경로는 저장소 루트 기준

## Path Conventions

- common: `common/src/main/kotlin/com/kbap/common/domain/notification/...`, 테스트 `common/src/test/kotlin/com/kbap/common/domain/notification/...`
- api: `api/src/main/kotlin/com/kbap/api/{review,notification}/...`, 테스트 `api/src/test/kotlin/com/kbap/api/review/...`

---

## Phase 1: Setup

**Purpose**: 없음 — 신규 픽스처·의존·마이그레이션·프로퍼티가 없다. api `@IntegrationTest` 에 `FakePushSender` 가 이미 포함돼 있고 `BackgroundConfig` 가 `@EnableAsync` 다.

---

## Phase 2: Foundational — 공용 파이프라인 2건 (FR-006, FR-008)

**Purpose**: HELPFUL 메시지가 `activity` 채널로 나가고, 기기 언어별 음식 이름을 채울 수 있게 한다. 두 변경 모두 기본값·매핑 수정이라 기존 호출자(관리자 발송·스캔 제안 배치)는 무변경.

- [x] T001 [P] Change the "유형별 채널" assertions in `common/src/test/kotlin/com/kbap/common/domain/notification/PushDispatchServiceTest.kt`: `HELPFUL`·`REVIEW_REMINDER` → `"activity"` (then 설명도 "광고성은 news, 활동은 activity 채널이다"). Red 확인
- [x] T002 [P] Add given("언어별 인자") to `common/src/test/kotlin/com/kbap/common/domain/notification/PushDispatchServiceTest.kt`: 회원 1·기기 2(`ko`·`en`) + `activityOn`, `prepare(PushRequest(HELPFUL, [m], argsByLang = mapOf(KO to mapOf("food" to "김치찌개"), EN to mapOf("food" to "Kimchi stew"))))` → ko 봉투 body 에 "김치찌개", en 봉투 body 에 "Kimchi stew" / `argsByLang` 비고 `args = mapOf("food" to "X")` 면 두 봉투 모두 "X". Red 확인(컴파일 실패)
- [x] T003 Rewrite `channelId` in `common/src/main/kotlin/com/kbap/common/domain/notification/model/NotificationType.kt` as explicit mapping: `HELPFUL`·`REVIEW_REMINDER` → `"activity"`, `SCAN_SUGGESTION`·`NEWS`·`MEAL_TIME` → `"news"` (`"default"` 제거, `when(this)` 망라). T001 Green
- [x] T004 Add `val argsByLang: Map<LanguageCode, Map<String, String>> = emptyMap()` to `PushRequest` in `common/src/main/kotlin/com/kbap/common/domain/notification/PushRequest.kt`, and in `common/src/main/kotlin/com/kbap/common/domain/notification/PushDispatchService.kt` `prepare` render with `request.argsByLang[lang] ?: request.args` (lang = `LanguageCode.from(device.lang)` 을 지역 변수로). T002 Green
- [x] T005 Run `./gradlew :common:test :batch:test` — `ExpoPushSenderTest`·`ScanSuggestionPushJobTest`(news 채널 그대로) Green 확인

**Checkpoint**: 파이프라인이 활동 채널과 언어별 인자를 지원한다. 아직 아무도 HELPFUL 을 보내지 않는다.

---

## Phase 3: User Story 1 — 내 리뷰에 좋아요가 달리면 알림을 받는다 (Priority: P1) 🎯 MVP

**Goal**: 다른 회원의 새 좋아요가 커밋되면 작성자의 activity on 기기마다 HELPFUL 알림함 행·발송 이력·Expo 메시지(`channelId=activity`, `data={type, notificationId, reviewId}`, 기기 언어 음식 이름)가 생긴다. 롤백된 요청은 아무것도 만들지 않는다. (FR-001, FR-002, FR-004, FR-006, FR-007, FR-010)

**Independent Test**: 작성자 기기 + activity on 시드 → 다른 회원으로 좋아요 → `eventually` 로 `FakePushSender.sent` 1건과 알림함·발송 이력 확인.

### Tests for User Story 1 (Test-First) ⚠️

- [x] T006 [US1] Add given("좋아요 알림") to `api/src/test/kotlin/com/kbap/api/review/ReviewLikeControllerTest.kt` — `@Autowired` 추가: `FakePushSender`, `NotificationDeviceJpaRepository`, `NotificationSettingJpaRepository`, `NotificationJpaRepository`, `NotificationDispatchJpaRepository`; 헬퍼 `device(memberId, lang)`(`NotificationDevice.register("inst-$memberId-$lang", "ExponentPushToken[$memberId-$lang]", IOS, lang, memberId)` 저장)·`activityOn(device)`(`NotificationSetting(memberId, installationId, activity = true)` 저장)·`helpfulRows(authorId)`(알림 `type=HELPFUL` 행 조회); `beforeSpec` 에서 `fakePushSender.reset()` 과 알림 4테이블 `deleteAll`; 시드 회원 id 는 이 given 전용 대역(8200~) 사용. 시나리오: (a) 작성자 A(8201) 기기 `en` activity on, B(8202) 좋아요 → `eventually(5.seconds)`: `sent` 1건, `channelId == "activity"`, `data["type"] == "HELPFUL"`, `data["reviewId"] == reviewId`, `data["notificationId"]` 존재, `body` 에 음식 이름(`seedFood` 의 korean_name — 번역 없음 → ko 폴백) 포함, `helpfulRows(A)` 1건·그 알림의 dispatch `SENT` 1건 / (b) A 기기 2대(`ko`·`en`) activity on → `sent` 2건, 두 봉투의 `to` 가 다름 / (c) 존재하지 않는 리뷰(400) → `continually(1.seconds)`: `sent` 0건. Red 확인(컴파일은 되나 sent 0 → eventually 실패)

### Implementation for User Story 1

- [x] T007 [P] [US1] Create `data class ReviewLiked(val reviewId: Long, val authorMemberId: Long, val foodId: Long)` in `api/src/main/kotlin/com/kbap/api/review/ReviewLiked.kt`
- [x] T008 [US1] Change `likeReview` in `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt`: 생성자에 `eventPublisher: ApplicationEventPublisher` 주입; `reviewRepository.findByIdOrNull(reviewId) ?: throw BusinessException(REVIEW_NOT_FOUND)` 로 리뷰 로드; `reviewLikeRepository.upsertActive(...)` 유지; 그 뒤 `eventPublisher.publishEvent(ReviewLiked(review.id, review.memberId, review.foodId))` 발행 (US2 에서 조건을 더한다 — 이 단계에서는 무조건 발행)
- [x] T009 [US1] Create `HelpfulPushListener` in `api/src/main/kotlin/com/kbap/api/notification/HelpfulPushListener.kt`: `@Component`, 생성자 `pushHandler: PushHandler`, `foodRepository: FoodJpaRepository`, `notificationRepository: NotificationJpaRepository`; `@Async @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT) fun handle(event: ReviewLiked)` — `try { val food = foodRepository.findByIdOrNull(event.foodId) ?: return; val argsByLang = LanguageCode.entries.associateWith { mapOf("food" to food.displayName(it)) }; val result = pushHandler.send(PushRequest(NotificationType.HELPFUL, listOf(event.authorMemberId), argsByLang = argsByLang, data = mapOf("reviewId" to event.reviewId))); log.info("HELPFUL 발송 reviewId={} sent={} failed={}", ...) } catch (e: Exception) { log.error("HELPFUL 발송 실패 reviewId={} authorMemberId={}", event.reviewId, event.authorMemberId, e) }`. 메서드에 `@Transactional` 금지(research §2). T006 (a)(b)(c) Green
- [x] T010 [US1] Add given("리스너 실패 격리") to `api/src/test/kotlin/com/kbap/api/review/ReviewLikeControllerTest.kt`: `@Autowired HelpfulPushListener` 로 `handle(ReviewLiked(reviewId = 1, authorMemberId = 8299, foodId = 999999))` 직접 호출 → `shouldNotThrowAny`, `sent` 0건 (`LlmCallCostEventListenerTest` 두 번째 시나리오와 같은 형태). Green 확인(T009 의 try/catch·null 반환으로 이미 통과 — 회귀 고정 목적)

**Checkpoint**: 좋아요 → 커밋 → 비동기 HELPFUL 발송이 동작한다. 본인 좋아요·취소·재호출은 아직 걸러지지 않는다(US2).

---

## Phase 4: User Story 2 — 받지 않아야 할 경우엔 받지 않는다 (Priority: P1)

**Goal**: 작성자 본인 좋아요·취소(liked=false)·이미 좋아요 상태의 재호출·activity off 기기·유효 기기 없음에서 HELPFUL 알림이 0건. (FR-003, FR-004)

**Independent Test**: 각 조건을 만들고 좋아요 API 호출 → `continually(1.seconds)` 로 `sent` 0건·알림함 0건.

### Tests for User Story 2 (Test-First) ⚠️

- [x] T011 [US2] Add scenarios to given("좋아요 알림") in `api/src/test/kotlin/com/kbap/api/review/ReviewLikeControllerTest.kt`: (d) 작성자 A(8211) 기기 activity on, A 본인 좋아요 → `continually(1.seconds)`: `sent` 0·`helpfulRows(A)` 0 / (e) B 가 좋아요 없는 상태에서 `unlike` → 0 / (f) B 좋아요 → `eventually` 1건 → 같은 B 가 다시 `like` → `continually` 여전히 1건 / (g) A 기기 activity off(설정 행 `activity = false`) → 0 / (h) A 기기 없음 → 0·알림함 행도 0. Red 확인 — (d)(f) 가 실패해야 한다((e)(g)(h) 는 기존 파이프라인·`unlikeReview` 무발행으로 이미 통과할 수 있음, 그래도 명시)

### Implementation for User Story 2

- [x] T012 [US2] Gate the publish in `likeReview` of `api/src/main/kotlin/com/kbap/api/review/ReviewService.kt`: upsert **전에** `val isNewLike = reviewLikeRepository.findByReviewIdAndMemberId(reviewId, memberId) == null` 을 계산하고, upsert 뒤 `if (isNewLike && !review.isOwnedBy(memberId)) eventPublisher.publishEvent(...)`. `unlikeReview` 는 손대지 않는다. T011 Green
- [x] T013 [US2] Run `./gradlew :api:test` — `ReviewLikeControllerTest` 기존 시나리오(등록·중복·취소·부활 행 수)·`ReviewControllerTest`·`ReviewListControllerTest` 회귀 Green (`existsById` → `findByIdOrNull` 교체로 REVIEW-001 경로 불변 확인)

**Checkpoint**: 트리거 조건이 스펙 FR-003 과 일치한다.

---

## Phase 5: User Story 3 — 같은 리뷰에 반응이 몰려도 알림은 한 번만 (Priority: P2)

**Goal**: 같은 리뷰의 직전 HELPFUL 알림함 행(활성)이 1시간 이내면 새 반응에 알림을 만들지 않는다. 리뷰 단위, 반응자·취소 후 재등록 불문, 실패로 삭제된 행은 제외. (FR-005)

**Independent Test**: B 좋아요 → 1건, C 좋아요 → 여전히 1건, B 취소·재등록 → 1건, A 의 다른 리뷰 → 2건, 기존 행 `created_at` 을 2시간 전으로 옮긴 뒤 C 재등록 → 3건.

### Tests for User Story 3 (Test-First) ⚠️

- [x] T014 [P] [US3] Add given("회원·유형·시각 이후 알림 조회") to `common/src/test/kotlin/com/kbap/common/domain/notification/NotificationJpaRepositoryTest.kt`: `findByMemberIdAndTypeAndCreatedAtAfter(memberId, HELPFUL, since)` — since 이후 행 포함·이전 행 제외(`created_at` 은 저장 후 native UPDATE 로 조정)·다른 type 제외·다른 회원 제외·소프트 삭제(`delete()` 후 save) 행 제외. Red 확인(컴파일 실패)
- [x] T015 [P] [US3] Add scenarios to given("좋아요 알림") in `api/src/test/kotlin/com/kbap/api/review/ReviewLikeControllerTest.kt`: (i) A(8221) 리뷰 R1, B 좋아요 → `eventually` 1건 → C(8223) 좋아요 → `continually` 1건 → B `unlike`·`like` → `continually` 1건 → A 의 리뷰 R2 에 C 좋아요 → `eventually` 2건 / (j) R1 의 HELPFUL 알림 `created_at` 을 SQL 로 2시간 전으로 UPDATE → D(8224) 좋아요 → `eventually` 3건 / (k) `fakePushSender.errorFor = { "DeviceNotRegistered" }` 상태에서 B 좋아요 → `eventually` dispatch FAILED 1·알림함 활성 행 0 → `errorFor` 해제 후 C 좋아요 → `eventually` 알림함 활성 행 1(실패 행이 창을 점유하지 않음). Red 확인((i) 두 번째 단계에서 2건이 되어 실패)

### Implementation for User Story 3

- [x] T016 [US3] Add derived query `fun findByMemberIdAndTypeAndCreatedAtAfter(memberId: Long, type: NotificationType, since: LocalDateTime): List<Notification>` to `common/src/main/kotlin/com/kbap/common/domain/notification/NotificationJpaRepository.kt`. T014 Green
- [x] T017 [US3] Add bundle gate to `handle` in `api/src/main/kotlin/com/kbap/api/notification/HelpfulPushListener.kt` (send 전에): `val since = LocalDateTime.now().minus(BUNDLE_WINDOW)`; `notificationRepository.findByMemberIdAndTypeAndCreatedAtAfter(event.authorMemberId, NotificationType.HELPFUL, since).any { it.data?.get("reviewId")?.toString() == event.reviewId.toString() }` 면 `log.info` 후 return; `companion object { private val BUNDLE_WINDOW: Duration = Duration.ofHours(1) }`. T015 Green

**Checkpoint**: 묶음 정책이 동작한다. 세 스토리 완료.

---

## Phase 6: Polish & Cross-Cutting

- [x] T018 Run `./gradlew build` (arch 포함) — `ModuleBoundaryTest`·`AdminNotificationTestControllerTest`(NEWS → news 채널)·`ScanSuggestionPushJobTest` 회귀 Green, 컴파일 경고 없음
- [ ] T019 [P] Run quickstart.md §2 로컬 검증 — 메인 `.env` 로 `:api:bootRun`(8081), 실제 좋아요 후 `notification`·`notification_dispatch` 행과 1시간 안 재반응 시 행 증가 없음 확인
- [x] T020 [P] Record in `../kbap-agenthub/wiki/` (기존 푸시 문서에 절 추가 또는 `helpful-push-after-commit-event.md`) + `INDEX.md` 한 줄: "활동 알림은 api 가 `@Async + @TransactionalEventListener(AFTER_COMMIT)` 로 즉시 발송(Jira 아웃박스+batch 폴링 대체), 묶음 창 1시간 상수, 활동 채널 `activity`" — 허브에서 커밋

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 2 (Foundational)** → 모든 스토리의 전제(채널·언어별 인자). T001·T002 병렬(같은 파일이지만 다른 given — 순차 편집 권장), T003·T004 는 각 Red 뒤.
- **Phase 3 (US1)** → Phase 2 완료 후. T007 은 T006 과 병렬 가능, T008 → T009 순서.
- **Phase 4 (US2)** → US1 의 `ReviewService` 발행 코드 위에 조건을 얹으므로 US1 뒤.
- **Phase 5 (US3)** → US1 의 리스너에 게이트를 얹으므로 US1 뒤. US2 와는 독립(둘 다 US1 뒤라면 어느 순서든 가능). T014 는 common, T015 는 api — 병렬.
- **Phase 6** → 전부 완료 후.

### Parallel Opportunities

- T001 ∥ T002(같은 테스트 파일이라 실제로는 한 번에 편집), T007 ∥ T006, T014 ∥ T015, T019 ∥ T020.
- 한 명이 구현하므로 실질 순서: T001→T003, T002→T004, T005, T006→T007→T008→T009→T010, T011→T012→T013, T014→T016, T015→T017, T018→T019·T020.

---

## Implementation Strategy

### MVP First (Phase 2 + US1)

1. Phase 2: 채널 `activity`·`argsByLang` — 공용 파이프라인 회귀 Green.
2. Phase 3: 이벤트·리스너 — 좋아요 한 번에 푸시 한 번.
3. **STOP and VALIDATE**: (a)(b)(c) 통과, 관리자 발송·배치 회귀 Green.

### Incremental Delivery

- US2 를 붙이면 본인·취소·재호출이 걸러진다 — 이 상태가 최소 릴리스 가능 지점(잘못 보내는 알림 없음).
- US3 는 도배 방어 — Jira DoD 의 마지막 항목.

---

## Notes

- 리스너 메서드에 `@Transactional` 을 달지 않는다 — readOnly 면 안의 save 가 flush 되지 않고, REQUIRED 면 prepare/record 가 리스너 트랜잭션에 묶인다(research §2).
- 테스트에서 비동기를 동기화(`SyncTaskExecutor`)하지 않는다 — AFTER_COMMIT 동기 실행은 원 트랜잭션 리소스 위에서 돌아 알림 행이 커밋되지 않는다(research §2).
- `continually` 부정 검증은 리스너가 1초 이상 걸리면 오탐 통과할 수 있다 — 통합 컨텍스트에서 Expo 가 페이크라 수십 ms 안에 끝나므로 감수.
- 메트릭 카운터·Clock 주입은 넣지 않는다(2026-09-16 확인 — 발송 결과는 `notification_dispatch` 가 남기고, 시각은 `LocalDateTime.now()` 로 충분).
- Kotlin 소스 주석 금지. 각 태스크 또는 논리 단위마다 커밋.
