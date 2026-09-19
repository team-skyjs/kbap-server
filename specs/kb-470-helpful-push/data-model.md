# Data Model: HELPFUL 발송

**스키마 변경 없음.** Flyway 마이그레이션 없음. 기존 `food_review`·`review_like`·`food`·`notification_device`·`notification_setting`·`notification`·`notification_dispatch` 를 읽고 쓴다.

## 1. 흐름별 읽기·쓰기

| 단계 | 스레드/트랜잭션 | 읽기 | 쓰기 |
|------|----------------|------|------|
| `ReviewService.likeReview` | 요청 스레드, `@Transactional` | `food_review`(작성자·음식 id), `review_like`(활성 행 존재 여부) | `review_like` upsert → 새 좋아요·비작성자면 `ReviewLiked` 발행 |
| `HelpfulPushListener.handle` | `@Async` 스레드, 트랜잭션 없음 | `food`(이름 10개 언어) | — |
| `PushHandler.send` → `prepare` | 같은 스레드, 자체 `@Transactional` | `notification_device`·`notification_setting`(activity) | `notification`(기기당 1행, data = {type, notificationId, reviewId}) · `notification_dispatch`(PENDING) |
| `PushHandler.send` → `record` | 자체 `@Transactional` | `notification_dispatch` | SENT/FAILED, 실패 시 `notification` 소프트 삭제 |

## 2. 이벤트 (신규, `com.kbap.api.review`)

```kotlin
data class ReviewLiked(val reviewId: Long, val authorMemberId: Long, val foodId: Long)
```

발행 조건(세 가지 모두): 리뷰 존재 · 활성 좋아요 행 없음(신규 또는 부활) · `authorMemberId != likerMemberId`. 취소·중복 재호출·롤백에서는 발행되지 않는다.

## 3. 도메인 값 타입 변경 (`common.domain.notification`)

| 타입 | 변경 | 채우는 곳 |
|------|------|-----------|
| `NotificationType` | `channelId`: HELPFUL·REVIEW_REMINDER → `"activity"`, 광고성 → `"news"` (`"default"` 제거) | enum |
| `PushRequest` | `+ argsByLang: Map<LanguageCode, Map<String, String>> = emptyMap()` | 트리거(리스너) |
| `PushDispatchService.prepare` | 기기마다 `request.argsByLang[lang] ?: request.args` 로 렌더링 | 도메인 서비스 |

## 4. 알림함 행의 `data` (HELPFUL)

```json
{ "type": "HELPFUL", "notificationId": 123, "reviewId": 9001 }
```

`type`·`notificationId` 는 `prepare` 가 넣고 `reviewId` 는 트리거의 `PushRequest.data` 로 들어간다(기존 `request.data + mapOf(type, notificationId)` 병합).
