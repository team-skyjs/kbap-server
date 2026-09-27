# Contract: REVIEW_REMINDER 푸시 data · 알림함 `orderId` (KB-469)

## 1. 푸시 `data` (KB-468 계약 개정)

```json
{ "type": "REVIEW_REMINDER", "orderId": 12, "notificationId": 456 }
```

| 필드 | 타입 | 필수 | 채우는 곳 |
|------|------|------|-----------|
| `type` | string | ✅ | 파이프라인 |
| `orderId` | number(64비트 정수) | ✅ (REVIEW_REMINDER) | 배치 라이터 `PushRequest.data` |
| `notificationId` | number | ✅ | 파이프라인 |
| `foodId` | — | **미사용** | 현재 어떤 발송처도 채우지 않는다(도움돼요는 `reviewId`). 계약 표에서 "HELPFUL·REVIEW_REMINDER" 표기를 미사용으로 정정 |

앱 착지: `/profile/order/{orderId}` (KB-500). 서버 조회: `GET /api/orders/{orderId}` (주문 소유 회원만).

## 2. 알림함 응답 (`GET /api/notifications` · `PATCH /api/notifications/{id}/read`)

항목 스키마에 `orderId` 추가. API 버전 불변.

```json
{ "id": 456, "type": "REVIEW_REMINDER", "orderId": 12, "foodId": null,
  "title": "…", "body": "…", "receivedAt": 1789540000000, "read": false }
```

| 필드 | 규칙 |
|------|------|
| `orderId` | `REVIEW_REMINDER` 이고 data.orderId 가 정수·정수 문자열이면 값, 그 외 유형·값 없음·비정수면 null |
| `foodId` | 유지. 문서 설명을 "미사용(항상 null)" 로 정정 |

## 3. 배치 잡

| 항목 | 값 |
|------|----|
| 잡 이름 | `reviewReminderPushJob` |
| 트리거 | `POST /internal/batch/jobs?jobName=reviewReminderPushJob` → 202, `GET /internal/batch/executions/{id}` |
| cron | `0 */5 * * * *` Asia/Seoul (`BatchJobScheduler`) |
| 설정 | `kbap.batch.review-reminder.chunk-size`(100) · `kbap.batch.review-reminder.ttl`(6h) |
| 메트릭 | `kbap.push.dispatch{type=REVIEW_REMINDER,result=sent|failed}` |

## 4. 문구 (`push_*.properties`, 10개 언어)

`push.review_reminder.{1,2,3}.body` 교체 — `{food}` 제거, 주문 맥락, 시간 부사 없음. 제목 유지. 한국어:

```
push.review_reminder.1.body=주문하신 식사, 맛있게 드셨나요? 리뷰로 알려 주세요.
push.review_reminder.2.body=주문하신 메뉴가 어땠는지 한 문장만 남겨 주세요.
push.review_reminder.3.body=주문하신 음식 경험을 리뷰로 나눠 주세요. 다음 사람에게 도움이 돼요.
```
