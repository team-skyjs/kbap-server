# Data Model: KB-614

## 스키마 변경 (1건)

```sql
-- api/src/main/resources/db/migration/V<생성 시각>__notification_dispatch_type.sql
ALTER TABLE notification_dispatch
    ADD COLUMN notification_type VARCHAR(30) NULL AFTER notification_device_id;

UPDATE notification_dispatch d
    JOIN notification n ON n.id = d.notification_id
SET d.notification_type = n.type
WHERE d.notification_type IS NULL;
```

- 값은 `NotificationType` 이름(`notification.type` 과 같은 형식).
- NULL 허용 — 블루/그린 중 구 코드의 INSERT 를 받기 위함. `NOT NULL` 전환은 다음 릴리스.
- 새 인덱스 없음. 다른 마이그레이션에 순서 의존 없음(out-of-order 안전).
- 버전은 파일 생성 시각의 점 구분 timestamp.

## 엔티티

### `NotificationDispatch` (수정)

| 필드 | 변경 |
|------|------|
| `notificationType: NotificationType?` | **추가.** `@Enumerated(STRING)`, `@Column(name = "notification_type", length = 30)` |
| `pending(notificationId, deviceId, token, type)` | 팩토리에 `type` 추가 |

상태 전이(기존, 변경 없음):

```text
PENDING ──markSent──▶ SENT ──markDelivered──▶ DELIVERED
   │                    │
   └──markFailed──▶ FAILED ◀──markFailed──┘
```

`SENT` 는 24시간이 지나면 어떤 전이도 일어나지 않는다(미확인 종결 — 상태값은 그대로).

한 알림함 행에 붙는 이력: 최초 1 + 재전송 최대 2 = **최대 3**. 활성(`SENT`·`PENDING`) 이력은 한 기기당 동시에 하나뿐이다 — 재전송은 직전 이력을 `FAILED` 로 닫은 뒤에만 만든다.

### `MealSlot` (수정)

`LUNCH(11:00)`, `DINNER(17:00)`.

### 변경 없는 엔티티

`Notification`(생성 시각 = 슬롯 판정·재전송 창 기준, 최종 실패 시 `delete()`), `NotificationDevice`(`markTokenInvalid`), `NotificationSetting`.

## 리포지토리

| 리포지토리 | 변경 | 메서드 |
|-----------|------|--------|
| `NotificationSettingJpaRepository` | 추가 | `findMemberIdsByNewsTrueAfter(afterMemberId, limit): List<Long>` |
| | 삭제 | `findMemberIdsByNewsTrue()` |
| `NotificationJpaRepository` | 추가 | `existsByMemberIdAndTypeAndCreatedAtAfter(memberId, type, since): Boolean` |
| | 삭제 | `findMemberIdsByTypeAndCreatedAtAfter(type, since)` |
| `NotificationDispatchJpaRepository` | 추가 | `findReceiptTargets(status, types, from, to, afterId, limit): List<NotificationDispatch>` |
| | 추가 | `findByNotificationIdIn(notificationIds): List<NotificationDispatch>` — 알림별 시도 횟수 |
| | 삭제 | `findByDispatchStatusAndCreatedAtBefore(…)` — 미사용, 새 쿼리가 대체 |

받치는 인덱스(기존): `uk_notification_setting_member_installation (member_id, installation_id)`, `idx_notification_member_id (member_id, id)`, `idx_notification_dispatch_status_created (dispatch_status, created_at)`, `idx_notification_dispatch_notification (notification_id)`.

## 도메인 값 타입 (신규, `common.domain.notification`)

| 타입 | 필드 | 용도 |
|------|------|------|
| `ReceiptOutcome` | `ok`, `errorCode?`, `message?` | 포트의 `PushReceipt` 를 도메인으로 옮긴 값(도메인은 포트를 모른다) |
| `ResendPolicy` | `maxResends: Int`, `window: Duration` | 재전송 상한·시간 창 |
| `ReceiptResult` | `DELIVERED`·`FAILED`·`RESENT` | 집계 키 |
| `ReceiptApplyResult` | `resend: PreparedPush`, `tally` | 재전송할 봉투 + 유형별 집계 |

## 포트 타입 (신규, `common.port.push`)

`PushReceipt(ok, errorCode?, message?)`, `fun interface PushReceiptFetcher { fun fetch(ticketIds): Map<String, PushReceipt> }`

## 메모리 상 상태

| 구성 요소 | 상태 | 상한 |
|-----------|------|------|
| `ScanSuggestionMemberIdReader` | `cursor`, 버퍼, `exhausted` | 버퍼 ≤ 100 |
| `PushReceiptTargetReader` | `cursor`, 버퍼, `exhausted`, 시간 창(`open()` 에서 1회 계산) | 버퍼 ≤ 100 |
| `ScanSuggestionSlotFilter`·`PushReceiptSyncWriter` | 없음 | — |

## 설정

| 키 | 전 | 후 |
|----|----|----|
| `kbap.batch.scan-suggestion.member-chunk-size` | 500 | 삭제 |
| `kbap.batch.scan-suggestion.chunk-size` | — | 100 |
| `kbap.batch.scan-suggestion.ttl` | 3h | 불변 |
| `kbap.batch.push-receipt.chunk-size` | — | 100 |
| `kbap.batch.push-receipt.min-age` | — | 15m |
| `kbap.batch.push-receipt.max-age` | — | 24h |
| `kbap.batch.push-receipt.resend-window` | — | 2h |
| `kbap.batch.push-receipt.max-resends` | — | 2 |
| `kbap.push.expo.concurrency` (api·batch) | 6 | 삭제 |
| `kbap.push.expo.min-request-interval` (api·batch) | 170ms | 삭제 |
| `kbap.push.expo.retry.*` | 3 / 1s / 2.0 | 불변 |

## 메트릭

| 이름 | 태그 | 상태 |
|------|------|------|
| `kbap.push.dispatch` | `type`, `result=sent\|failed` | 불변 |
| `kbap.push.receipt` | `type`, `result=delivered\|failed\|resent\|pending` | 신규 |
