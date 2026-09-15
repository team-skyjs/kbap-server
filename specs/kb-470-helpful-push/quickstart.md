# Quickstart: HELPFUL 발송

## 1. TDD 순서 (Red → Green 단위)

1. **채널 매핑** — `common/src/test/.../notification/PushDispatchServiceTest.kt` "유형별 채널" 의 HELPFUL·REVIEW_REMINDER 단언을 `"activity"` 로 바꾼다(Red). → `NotificationType.channelId` 3분기 매핑 → Green. `ExpoPushSenderTest` 는 메시지 값 그대로 직렬화만 보므로 영향 없음.
2. **언어별 인자** — 같은 파일에 given("언어별 인자"): 회원 1·기기 2(ko·en) + activity on, `PushRequest(HELPFUL, argsByLang = {KO: {food: 김치찌개}, EN: {food: Kimchi stew}})` → 봉투 body 가 각 언어의 이름을 담는다 / `argsByLang` 비면 `args` 사용. Red → `PushRequest.argsByLang` + `prepare` 1줄 → Green.
3. **리포지토리 쿼리** — `common/src/test/.../notification/NotificationJpaRepositoryTest.kt`(기존 `CommonTestApp` 컨텍스트) 에 시나리오: 회원·type·since 경계(이후 포함·이전 제외·다른 type·다른 회원 제외·소프트 삭제 행 제외). Red → 파생 쿼리 선언 → Green.
4. **발행 조건** — `api/src/test/.../review/ReviewLikeControllerTest.kt` 에 given("좋아요 알림 이벤트"): 테스트 전용 `@EventListener` 대신 `HelpfulPushListener` 결과(알림함 행)로 관찰한다. 리스너가 아직 없으므로 이 단계는 §5 와 함께 Red 확인. `ReviewService.likeReview`: `findById` → 없으면 REVIEW-001, `findByReviewIdAndMemberId == null && review.memberId != memberId` 일 때 `eventPublisher.publishEvent(ReviewLiked(...))`(upsert 뒤).
5. **리스너 통합** — 같은 테스트 파일에 시나리오(픽스처: `deviceRepository`·`settingRepository` 로 작성자 기기 + `activity = true`, `beforeSpec` 에서 `fakePushSender.reset()` 및 알림 테이블 정리):
   - (a) B 가 A 리뷰 좋아요 → `eventually(5s)`: `fakePushSender.sent` 1건, `channelId == "activity"`, `data.type == "HELPFUL"`, `data.reviewId == reviewId`, `data.notificationId` 존재, body 에 음식 이름(기기 lang 기준), `notification` 행 1·`notification_dispatch` SENT 1.
   - (b) A 기기 2대(ko·en) activity on → 메시지 2건, 각 언어 이름.
   - (c) 작성자 본인 좋아요 / (d) 취소 / (e) 활성 상태 재호출 / (f) activity off / (g) 존재하지 않는 리뷰(400) → `continually(1s)`: sent 0.
   - (h) 묶음: B 좋아요 → eventually 1건 → C 좋아요 → continually 여전히 1건 → B 취소·재등록 → 여전히 1건 → A 의 다른 리뷰에 좋아요 → eventually 2건.
   - (i) 묶음 창 경과: 기존 HELPFUL 행의 `created_at` 을 SQL 로 2시간 전으로 바꾼 뒤 C 좋아요 → eventually 2건.
   Red → `com.kbap.api.review.ReviewLiked` · `com.kbap.api.notification.HelpfulPushListener`(`@Async @TransactionalEventListener(phase = AFTER_COMMIT)`, 묶음 창 확인 → `foodRepository.findById` → `argsByLang` 10개 언어 → `pushHandler.send(PushRequest(HELPFUL, listOf(authorMemberId), argsByLang = …, data = mapOf("reviewId" to reviewId)))` → `log.info`, `try/catch` 로그) → Green.
6. **실패 격리** — `HelpfulPushListener` 를 직접 생성해 `handle(ReviewLiked(존재하지 않는 foodId …))` 가 예외를 던지지 않음(`shouldNotThrowAny`) — `LlmCallCostEventListenerTest` 두 번째 시나리오와 같은 형태.
7. **회귀** — `AdminNotificationTestControllerTest`(NEWS 채널 news 그대로)·`ScanSuggestionPushJobTest`·`ModuleBoundaryTest`(arch). `./gradlew build` 전체 Green.

## 2. 로컬 실행 검증

```bash
# 메인 체크아웃 .env 를 읽는다 — 워크트리엔 .env 없음 (메모리: worktree-bootrun-env)
set -a; source ../../../.env; set +a
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun --no-daemon --args='--server.port=8081'

# 준비: 작성자 회원 A 로 앱(dev 빌드) 로그인 → 토큰 등록 → 알림 설정 activity on, A 의 리뷰 1개
# (또는 notification_device / notification_setting(activity=1) 직접 INSERT)
curl -s -X POST 'http://localhost:8081/api/reviews/<reviewId>/like?liked=true' \
  -H 'Authorization: Bearer <B 의 access token>' -H 'X-API-Version: 1.0' | jq .
mysql -e "SELECT id, type, data, created_at FROM notification WHERE type='HELPFUL' ORDER BY id DESC LIMIT 3" kbap
mysql -e "SELECT notification_id, dispatch_status, ticket_id, error FROM notification_dispatch ORDER BY id DESC LIMIT 3" kbap
# 1시간 안에 다른 회원으로 다시 like → notification 행이 늘지 않아야 한다
```

Expo 실발송을 보려면 `KBAP_PUSH_EXPO_ACCESS_TOKEN` 등 dev 환경 값이 .env 에 있어야 한다. 없으면 `notification_dispatch.error` 로 실패 경로만 확인된다.
