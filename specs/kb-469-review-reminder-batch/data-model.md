# Data Model: 리뷰 리마인더 배치 (KB-469)

스키마 변경 없음(마이그레이션 0건). 기존 엔티티만 읽고, 알림 행은 기존 발송 파이프라인이 만든다.

## 읽는 엔티티

| 엔티티 | 테이블 | 쓰는 필드 | 역할 |
|--------|--------|-----------|------|
| `Order` | `orders` | `id`·`memberId`·`createdAt` | 대상 판정 기준. 창 `createdAt ∈ [now-25h, now-1h]` |
| `OrderItem` | `order_item` | `orderId`·`foodId` | "리뷰 안 쓴 항목이 하나라도 있는가" |
| `Review` | `food_review` | `memberId`·`foodId` | 항목 리뷰 유무(활성 행 존재) |
| `Notification` | `notification` | `memberId`·`type`·`createdAt`·`data` | 중복 판정(`REVIEW_REMINDER` 이고 `createdAt >= order.createdAt` 인 활성 행 존재 → 제외) / 알림함 `orderId` 노출 |

모두 `BaseEntity` 상속 → `@SQLRestriction("status='ACTIVE'")` 가 JPQL 서브쿼리에도 적용된다. 실패한 알림 행은 `delete()`(status=DELETED) 로 빠져 다음 주기에 재대상이 된다.

## 만들어지는 데이터 (기존 파이프라인)

`PushDispatchService.prepare` 가 기기마다:
- `notification` 행 — `type=REVIEW_REMINDER`, `data = { "orderId": <Long>, "type": "REVIEW_REMINDER", "notificationId": <id> }`
- `notification_dispatch` 행 — pending → SENT/FAILED

## 코드 변경 (모델 계층)

- `Notification.orderIdOrNull(): Long?` — `REVIEW_REMINDER` 이고 `data["orderId"]` 가 Int/Long/정수 문자열이면 값, 아니면 null. `foodIdOrNull` 과 변환 로직 공유(`longFromData`). 상수 `DATA_ORDER_ID = "orderId"`.
- `OrderJpaRepository.findReviewReminderTargets(from, to, afterId, limit): List<Order>`(알림 유형은 JPQL enum 리터럴로 고정 — ArchUnit 도메인 방향 맵 준수) — research §1 JPQL.

## 배치 도메인 값

| 이름 | 위치 | 내용 |
|------|------|------|
| `ReviewReminderWindow` | `batch.notification.reminder` | `MIN_AGE = 1h`, `MAX_AGE = 25h`, `of(clock) → (from, to)` |
| 리더 | `ReviewReminderOrderReader : ItemStreamReader<Order>` | open 에서 창 고정, id 커서 페이징(page = chunk) |
| 라이터 | `ReviewReminderPushWriter : ItemWriter<Order>` | `distinctBy memberId` → 주문마다 `PushRequest(REVIEW_REMINDER, [memberId], data={orderId}, ttl)` → 메트릭 |

## 검증 규칙(스펙 매핑)

| FR | 강제 지점 |
|----|-----------|
| FR-002 창 | 리더 JPQL `between :from and :to` |
| FR-003 이미 받음 | JPQL `not exists Notification` |
| FR-004 리뷰 다 씀 | JPQL `exists OrderItem … not exists Review` (항목 0건도 제외) |
| FR-006 data | 라이터 `data = mapOf("orderId" to order.id)`; 파이프라인이 type·notificationId 추가 |
| FR-009 재시도 | `@SQLRestriction` + 소프트 삭제 |
| FR-012 알림함 | `Notification.orderIdOrNull` + `NotificationResponse.orderId` |
