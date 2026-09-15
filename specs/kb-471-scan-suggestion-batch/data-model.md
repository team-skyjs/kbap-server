# Data Model: SCAN_SUGGESTION 배치

스키마 변경 없음. 기존 5테이블(KB-464/466/544)을 읽고 쓴다.

## 1. 읽기·쓰기 흐름

| 단계 | 읽기 | 쓰기 |
|------|------|------|
| 대상 확정 tasklet | `notification_setting`(news=true 회원 id, distinct) · `notification`(이번 슬롯 시작 이후 SCAN_SUGGESTION 회원 id, distinct) | — (버퍼에 회원 id 만) |
| 청크 발송 step(회원 묶음마다 `PushHandler.send`) | 잡 범위 버퍼(회원 id) · 부품 안에서 `PushTargetResolver` 가 `notification_device`·`notification_setting`·`notification_consent` | 부품 안에서 `notification`(기기당 1행)·`notification_dispatch`(PENDING → SENT/FAILED) |

## 2. 리포지토리 추가 (common.domain.notification)

```kotlin
// NotificationSettingJpaRepository
@Query("select distinct s.memberId from NotificationSetting s where s.news = true")
fun findMemberIdsByNewsTrue(): List<Long>

// NotificationJpaRepository
@Query("select distinct n.memberId from Notification n where n.type = :type and n.createdAt >= :since and n.memberId is not null")
fun findMemberIdsByTypeAndCreatedAtAfter(@Param("type") type: NotificationType, @Param("since") since: LocalDateTime): List<Long>
```

- 둘 다 `@SQLRestriction(status='ACTIVE')` 가 자동 적용된다(소프트 삭제 설정 행·알림 행 제외).
- `since` 는 **JVM 시간대의 LocalDateTime** — `ScanSuggestionSendWindow.startOfCurrentSlot(clock)` 이 KST 슬롯 시작(12:00 또는 18:00)을 JVM 존으로 변환해 넘긴다(`createdAt` 이 `@CreationTimestamp` JVM 로컬 시각이므로).
- 인덱스: `notification` 은 `idx_notification_member_id(member_id, id)` 뿐이라 `type + created_at` 범위 스캔은 풀스캔에 가깝다. 알림 행이 하루 수백~수천이고 잡이 하루 1회라 감수. 월 단위로 커지면 `(type, created_at)` 인덱스를 그때 추가.

## 3. 도메인 값 타입 변경 (common.domain.notification / common.port.push)

| 타입 | 변경 | 채우는 곳 |
|------|------|-----------|
| `NotificationType` | `val channelId: String get() = if (marketing) "news" else "default"` | enum |
| `MealSlot`(신규) | `LUNCH(12:00)`·`DINNER(18:00)` — 문구 슬롯 + 슬롯 상한 축 | enum |
| `PushRequest` | `+ mealSlot: MealSlot? = null` | 트리거(배치 잡별 고정) |
| `PushTemplates` | `+ bySlot[SCAN_SUGGESTION][LUNCH/DINNER][lang]`, `optOutNotice` "프로필 > 알림 설정" | 템플릿 |
| `PushMessageRenderer` | `render(type, lang, args, slot = null)` — 슬롯 템플릿 → 기본 | 도메인 서비스 |
| `PushRequest` | `+ ttlSeconds: Int? = null` | 트리거(배치 3h, api 는 미지정) |
| `PushEnvelope` | `+ channelId: String`, `+ ttlSeconds: Int?` | `PushDispatchService.prepare` (`type.channelId`, `request.ttlSeconds`) |
| `PushMessage`(port) | `+ channelId: String`, `+ ttlSeconds: Int? = null` | 소비자 매핑(api `PushNotificationService`, batch writer) |
| `ExpoMessage`(infra, internal) | `channelId` 기본값 제거 → 메시지 값, `+ ttl: Int?`(`@JsonInclude(NON_NULL)`) | 어댑터 |
| `PushHandler`(port, 신규) | `fun send(request: PushRequest): PushDispatchResult` | 호출자가 보는 유일한 문 |
| `ExpoPushHandler`(infra, 신규) | `(dispatchService, sender)` — prepare → 봉투→`PushMessage` 매핑 → send → record | 조립 config |
| `ExpoPushSender`(infra) | `create(baseUrl, accessToken, concurrency: Int, minRequestInterval: Duration, retryPolicy: RetryPolicy)` — 고정 스레드 풀·요청 시작 페이서·`RetryTemplate`(일시 실패만)·`AutoCloseable` | 조립 config(api·batch `PushConfig`, `destroyMethod = "close"`) |

`PreparedPush`·`PushDispatchResult`·`PushOutcome` 불변. `record(prepared, results)` 의 `results.size == dispatchIds.size` 불변식은 청크마다 `PreparedPush(chunkMessages, chunkDispatchIds)` 를 만들어 지킨다.

## 4. 배치 값 타입 (batch.notification)

```kotlin
@Component @JobScope
class ScanSuggestionCandidateBuffer {       // ArrayDeque<Long>
    fun load(memberIds: Collection<Long>)
    fun poll(): Long?
}

class ScanSuggestionPushWriter(             // ItemWriter<Long>
    handler: PushHandler, ttlSeconds: Int, meterRegistry: MeterRegistry,
)   // write(chunk) = handler.send(PushRequest(SCAN_SUGGESTION, chunk.items, ttlSeconds = ttlSeconds)) → 로그·카운터

object ScanSuggestionSendWindow {           // KST 고정
    fun startOfCurrentSlot(clock: Clock): LocalDateTime   // 12:00/18:00 슬롯 시작(없으면 전날 18:00) → JVM 존
    // LUNCH_CRON = "0 0 12 * * *", DINNER_CRON = "0 0 18 * * *" (코드 상수)
}
```

## 5. 상태 전이

`notification_dispatch`: PENDING → SENT(ok 티켓) | FAILED(error 티켓·청크 최종 실패 — 재시도 소진 시 마지막 오류, 영구 실패 시 그 오류). 기존 `markSent/markFailed` 그대로. FAILED 판정 시 대응 `notification` 은 `status=DELETED`(소프트 삭제) — 알림함·슬롯 상한 쿼리에서 자동 제외. 잡: `COMPLETED`(발송 수행, 0건 포함) / `FAILED`(예상 밖 예외 — DB 불가 등; Expo 실패는 FAILED 로 가지 않는다).

## 6. 설정 키

| 키 | 기본 | 의미 |
|----|------|------|
| `kbap.batch.scan-suggestion.member-chunk-size` | `500` | 발송 step 의 회원 묶음 크기(prepare/record 트랜잭션·메모리 단위) |
| `kbap.batch.scan-suggestion.ttl` | `3h` | Expo ttl |
| `kbap.batch.scheduler.enabled` | 기존 | 테스트 off |
| `kbap.push.expo.concurrency` | `6` | 청크 요청 동시 발송 수(고정 스레드 풀). api·batch yml 양쪽 |
| `kbap.push.expo.min-request-interval` | `170ms` | 청크 요청 시작 간격(초당 600건 상한). api·batch yml 양쪽 |
| `kbap.push.expo.retry.max-retries` | `3` | 일시 실패 재시도 횟수(총 시도 = +1). api·batch yml 양쪽 |
| `kbap.push.expo.retry.initial-delay` | `1s` | 첫 재시도 대기 |
| `kbap.push.expo.retry.multiplier` | `2.0` | 회차마다 대기 배수 |
