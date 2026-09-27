# Contract: 리뷰 리마인더 잡 (KB-469)

## 잡

| 항목 | 값 |
|------|----|
| 잡 이름 | `reviewReminderPushJob` |
| 스텝 | `reviewReminderSendStep` — chunk 100, 실제 트랜잭션 매니저 |
| 스케줄 | `0 */5 * * * *` Asia/Seoul(`BatchJobScheduler`), 부팅 자동 실행 없음 |
| HTTP 트리거 | `POST /internal/batch/jobs?jobName=reviewReminderPushJob` → 202 `{executionId}` · 실행 중이면 409 · 상태는 `GET /internal/batch/executions/{id}` |
| 대상 | 회원 주문, `created_at` 이 실행 시점 기준 1h 이상 25h 이하, 주문 생성 이후 REVIEW_REMINDER 알림 없음, 리뷰 안 쓴 항목 1개 이상 |
| 대표 음식 | 리뷰 없는 항목 중 항목 id 최소, 음식 행이 있는 것 |
| 설정 | `kbap.batch.review-reminder.chunk-size`(100) · `kbap.batch.review-reminder.ttl`(6h, 24h 미만) |
| 메트릭 | `kbap.push.dispatch{type=REVIEW_REMINDER, result=sent\|failed}` |

## 푸시 `data` (KB-468 계약에 `orderId` 추가)

```json
{ "type": "REVIEW_REMINDER", "orderId": "12", "foodId": "7", "notificationId": 456 }
```

| 필드 | 타입 | 필수 | 채우는 곳 |
|------|------|------|-----------|
| `type` | string | ✅ | 파이프라인 |
| `orderId` | string | REVIEW_REMINDER 에서 ✅ | 트리거(라이터) — **신규** |
| `foodId` | string | REVIEW_REMINDER 에서 ✅ | 트리거(라이터) |
| `notificationId` | number | ✅ | 파이프라인 |

- 채널 `activity`, 광고 표기·수신거부 안내 없음, `ttl` = 설정값 초(기본 21600).
- 앱 착지: `orderId` 있으면 `/profile/order/{orderId}`, 없으면(구 발송분) `foodId` 폴백 — KB-500(FE).

## 알림함 응답 (`GET /api/notifications` · `PATCH /api/notifications/{id}/read`)

항목에 `orderId` 필드 추가. 버전 헤더 값 불변.

```json
{ "id": 456, "type": "REVIEW_REMINDER", "orderId": 12, "foodId": 7,
  "title": "아까 드신 식사, 어떠셨나요? 🍽️", "body": "주문하신 김치찌개, 맛있게 드셨나요? 리뷰로 알려 주세요.",
  "receivedAt": 1789540000000, "read": false }
```

| `type` | `orderId` |
|--------|-----------|
| `REVIEW_REMINDER` 이고 data.orderId 가 정수·정수 문자열 | 그 값 |
| `REVIEW_REMINDER` 이고 data.orderId 없음/비정수(구 발송분) | null |
| 그 외 유형 | null |

## 문구 키 (불변)

`push.review_reminder.{1,2,3}.{title,body}` × 10개 언어 파일, 인자 `{food}`(대표 음식의 기기 언어 표시명, ko 폴백). 값만 주문 맥락으로 바뀐다.
