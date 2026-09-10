# Data Model: Expo Push 발송 공용 파이프라인

스키마 변경 없음(KB-464 테이블 그대로). 이 문서는 파이프라인이 다루는 **값 타입·도메인 서비스·엔티티 사용 규칙**을 적는다.

## 1. 열거형 변경

- `NotificationType` += `MEAL_TIME` (컬럼 `notification.type VARCHAR(30)` — 마이그레이션 불필요).
- `NotificationType.marketingByDefault: Boolean` — `SCAN_SUGGESTION` 만 true. 렌더 시 `marketing` 인자의 기본값.

## 2. Port 값 타입 — `common.port.push`

| 타입 | 필드 | 규칙 |
|------|------|------|
| `PushMessage` | `to: String`(Expo 토큰), `title`, `body`, `data: Map<String, Any>` | 순수 값. Expo 옵션(sound/priority/channelId)은 어댑터가 채운다. |
| `PushTicket` | `ok: Boolean`, `id: String?`, `error: String?` | `ok=true` 면 `id` 존재, `ok=false` 면 `error` 존재(255자 이내). |
| `PushSender` | `fun send(messages: List<PushMessage>): List<PushTicket>` | **반환 크기 = 입력 크기, 순서 동일.** 예외를 던지지 않는다(청크 실패 → 해당 청크 전부 error 티켓). |

## 3. 도메인 서비스 — `common.domain.notification`

### `PushRequest`(입력 값 객체)

| 필드 | 타입 | 의미 |
|------|------|------|
| `type` | `NotificationType` | 알림 유형(필터·템플릿 키) |
| `memberIds` | `Collection<Long>` | 후보 회원(트리거가 "누구에게" 를 정해 넘긴다) |
| `args` | `Map<String, String>` | 템플릿 치환값(`{food}` 등; NOTICE 는 `title`·`body`) |
| `data` | `Map<String, Any>` | 푸시 `data` 추가 필드(`foodId` 등). `type`·`notificationId` 는 파이프라인이 채운다. |
| `marketing` | `Boolean = type.marketingByDefault` | 광고성 표기·수신거부 안내 부착 여부 |

### `PushTargetResolver.resolve(memberIds, type): List<NotificationDevice>`

- 입력 회원 집합에서 다음을 통과한 **유효 기기**만 돌려준다: `token_invalid_at is null` AND 유형별 조건(research §4 표). 회원당 기기 0~n.
- 쿼리 3개(모두 `in` 일괄): `NotificationDeviceJpaRepository.findByMemberIdInAndTokenInvalidAtIsNull`, `NotificationSettingJpaRepository.findByMemberIdIn`, `NotificationConsentJpaRepository.findOpenByMemberIdIn`(신규 파생/JPQL).
- 광고성 동의 판정: `NotificationConsents.isMarketingEnabled(open, requiredVersion = 2)` — 두 `consentType` 모두 열린 행 존재 AND 그 행들의 `consentVersion >= requiredVersion`. (기존 api `NotificationConsentService.isMarketingEnabled` 는 `requiredVersion = 0` 으로 이 함수에 위임 — 설정 화면 동작 불변.)

### `PushMessageRenderer.render(type, lang: LanguageCode, args, marketing): PushContent`

- `PushContent(title: String, body: String)`.
- `PushTemplates[type][lang]` 조회 → `{key}` 치환(미제공 키는 빈 문자열) → `marketing` 이면 `title = "(광고) " + title`, `body = body + "\n" + optOutNotice[lang]`.
- 불변식(테스트): 전 `NotificationType` × 전 `LanguageCode` 항목 존재·비어 있지 않음; `optOutNotice` 전 로케일 존재. 결과 title ≤ 200, body ≤ 1000(엔티티 컬럼 길이) — 템플릿 길이로 보장, NOTICE 는 `take(n)` 절단.

### `PushDispatchService`

```
@Transactional prepare(request: PushRequest): PreparedPush
@Transactional record(prepared: PreparedPush, results: List<PushOutcome>): PushDispatchResult
```

- 도메인은 port 타입(`PushMessage`·`PushTicket`)을 참조할 수 없으므로(ArchUnit) 자체 값 타입을 쓴다: `PushEnvelope(to, title, body, data)`, `PushOutcome(ok, ticketId, error)`. 글루(api `PushNotificationService`·batch 잡)가 `PushEnvelope → PushMessage`, `PushTicket → PushOutcome` 를 한 줄씩 매핑한다.
- `PreparedPush(messages: List<PushEnvelope>, dispatchIds: List<Long>)` — 같은 인덱스가 같은 기기. `isEmpty()` 면 호출자는 `send` 를 건너뛴다.
- `PushDispatchResult(sent: Int, failed: Int)`.
- `prepare` 절차: `resolver.resolve` → 기기를 회원별로 묶음 → 회원마다 (a) 알림함 언어 = `updatedAt` 최신 기기의 `lang` 으로 렌더한 `Notification.forMember(...)` 저장, `data += notificationId` (b) 기기마다 기기 `lang` 으로 렌더한 `PushEnvelope` + `NotificationDispatch.pending(notificationId, device.id, device.expoToken)` 저장. 기기 0대 회원은 아무것도 저장하지 않는다.
- `record` 절차: `require(results.size == dispatchIds.size)`; `dispatchRepository.findAllById(dispatchIds)` 를 순서대로 결과와 짝지어 `ok → markSent(id)` / `!ok → markFailed(error)`; `error == "DeviceNotRegistered"` 면 `notificationDeviceId` 의 기기 `markTokenInvalid(now)`. dirty checking(`save` 호출 없음).

## 4. 엔티티 상태 전이(기존, 사용만)

```
NotificationDispatch: PENDING ─record(ok)─▶ SENT     (KB-473 이 DELIVERED 로)
                      PENDING ─record(err)─▶ FAILED
NotificationDevice:   tokenInvalidAt = null ─DeviceNotRegistered─▶ 스탬프 (renew 가 되돌림)
```

## 5. 리포지토리 추가 메서드

| 리포지토리 | 메서드 | 형태 |
|-----------|--------|------|
| `NotificationDeviceJpaRepository` | `findByMemberIdInAndTokenInvalidAtIsNull(memberIds: Collection<Long>)` | 파생 |
| `NotificationSettingJpaRepository` | `findByMemberIdIn(memberIds: Collection<Long>)` | 파생 |
| `NotificationConsentJpaRepository` | `findOpenByMemberIdIn(memberIds: Collection<Long>)` | JPQL (`revokedAt is null`) |

## 6. 설정 프로퍼티

| 키 | 기본값 | 위치 |
|----|--------|------|
| `kbap.push.expo.base-url` | `https://exp.host` | api·batch `application.yml` |
| `kbap.push.expo.access-token` | `${EXPO_ACCESS_TOKEN:}` (빈 값 = 헤더 생략) | api·batch `application.yml` |
