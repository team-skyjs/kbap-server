# Quickstart: 리뷰 리마인더 배치 (KB-469)

## 구현 순서 (Test-First)

1. **common — 알림함 `orderId`**: `NotificationTest`(orderIdOrNull 4케이스) Red → `Notification.orderIdOrNull` Green.
2. **common — 문구**: `PushMessageRendererTest` 에 "10개 로케일 REVIEW_REMINDER 본문에 `{` 없음·인자 없이 렌더" Red → `push_*.properties` 본문 교체 Green.
3. **common — 대상 조회**: `OrderJpaRepositoryTest`(CommonTestApp) — 창·이미 받음·리뷰 다 씀·항목 0건·소프트 삭제 알림 재대상·커서 Red → `findReviewReminderTargets` Green.
4. **batch — 리더**: `ReviewReminderOrderReaderTest`(@BatchIntegrationTest) Red → 리더 Green.
5. **batch — 잡**: `ReviewReminderPushJobTest` — 발송 data·채널·ttl·같은 회원 2주문 1건·재실행 0건·HTTP 트리거·메트릭 Red → Config·Writer·Scheduler·yml Green.
6. **api — 알림함**: `NotificationInboxTest` orderId 케이스 Red → `NotificationResponse.orderId` + 문서 Green.
7. 문서: KB-468 `contracts/push-data-contract.md` 갱신, Jira KB-469 코멘트(게스트 제외·orderId·foodId 미사용).

## 로컬 확인

```bash
./gradlew :common:test :batch:test :api:test -Dkotest.tags="!arch"
# 수동 트리거 (배치 기동 후)
curl -X POST 'http://localhost:8080/internal/batch/jobs?jobName=reviewReminderPushJob'
```
