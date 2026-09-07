# Data Model: 푸시 알림 데이터 기반

컨텍스트: `com.kbap.common.domain.notification` (신설). 모든 엔티티는 `BaseEntity` 상속(`id`·`status`·`created_at`·`updated_at` 공통, `@SQLRestriction("status = 'ACTIVE'")`). 다른 컨텍스트는 `Long` id 로만 참조.

## 1. push_token — 기기 푸시 토큰

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK AUTO_INCREMENT | BaseEntity |
| installation_id | VARCHAR(36) | NOT NULL, **UNIQUE `uk_push_token_installation`** | 기기 설치 UUID (X-Installation-Id) |
| member_id | BIGINT | NULL, FK `fk_push_token_member` → member(id), KEY `idx_push_token_member` | 연결 회원 (게스트면 NULL) |
| expo_token | VARCHAR(255) | NOT NULL | `ExponentPushToken[...]` |
| platform | ENUM('IOS','ANDROID') | NOT NULL | |
| lang | VARCHAR(10) | NOT NULL | 기기 언어 코드, 검증 없이 보관 |
| guest_settings | JSON | NULL | `NotificationPreferences` 직렬화 (게스트 전용) |
| status / created_at / updated_at | | BaseEntity | |

**엔티티 `PushToken`** — 필드: `installationId`, `memberId: Long?`, `expoToken`, `platform: PushPlatform`, `lang`, `guestSettings: NotificationPreferences?`.
도메인 메서드:
- `linkMember(memberId)` — 회원 연결.
- `unlinkMember()` — `memberId = null`, 나머지 유지.
- `renew(expoToken, platform, lang, guestSettings)` — 같은 기기의 재등록 갱신.
- companion `register(installationId, expoToken, platform, lang, memberId?, guestSettings?)`.

**리포지토리 `PushTokenJpaRepository`** — `findByInstallationId(installationId): PushToken?`, `findByMemberId(memberId): List<PushToken>`, `findByExpoToken(expoToken): List<PushToken>`(영수증 정리용).

## 2. member_notification_setting — 회원 알림 설정

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity |
| member_id | BIGINT | NOT NULL, **UNIQUE `uk_member_notification_setting_member`**, FK → member(id) | 회원 1 : 설정 1 |
| helpful | BOOLEAN | NOT NULL DEFAULT TRUE | 리뷰 도움됨 수신 |
| review_reminder | BOOLEAN | NOT NULL DEFAULT TRUE | 리뷰 리마인더 수신 |
| nudge | BOOLEAN | NOT NULL DEFAULT FALSE | 넛지(광고성) 수신 |
| nudge_opt_in_at | DATETIME(6) | NULL | 넛지 동의 시각 (서버 스탬프) |
| status / created_at / updated_at | | BaseEntity | |

**값 객체 `NotificationPreferences(helpful: Boolean = true, reviewReminder: Boolean = true, nudge: Boolean = false)`** — `DEFAULT` 상수 제공.

**엔티티 `MemberNotificationSetting`** — 필드: `memberId`, `helpful`, `reviewReminder`, `nudge`, `nudgeOptInAt: LocalDateTime?`.
도메인 메서드:
- `preferences(): NotificationPreferences`.
- `updateHelpful(enabled)`, `updateReviewReminder(enabled)`.
- `updateNudge(enabled, now)` — off→on 이면 `nudgeOptInAt = now`, on→off 면 `null`, 같은 값이면 무변화.
- `isNudgeAllowed()` — `nudge && nudgeOptInAt != null`.
- companion `defaultFor(memberId)`.

**리포지토리** — `findByMemberId(memberId): MemberNotificationSetting?`.

## 3. notification — 알림 이력(알림함)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity, 알림함 keyset 커서 |
| member_id | BIGINT | NULL, FK `fk_notification_member` → member(id) | 수신 회원 |
| installation_id | VARCHAR(36) | NULL | 수신 기기 (게스트 대상 시), FK 없음 |
| type | VARCHAR(30) | NOT NULL | `NotificationType` — HELPFUL·NUDGE·REVIEW_REMINDER·NOTICE |
| title | VARCHAR(200) | NOT NULL | 발송 시점 렌더 문자열 |
| body | VARCHAR(1000) | NOT NULL | |
| data | JSON | NULL | FE 계약 `{ type, foodId?, notificationId? }` |
| read_at | DATETIME(6) | NULL | 읽음 시각 |
| status / created_at / updated_at | | BaseEntity | |

인덱스: `idx_notification_member_id (member_id, id)`, `idx_notification_installation_id (installation_id, id)`.

**엔티티 `Notification`** — 필드: `memberId: Long?`, `installationId: String?`, `type: NotificationType`, `title`, `body`, `data: Map<String, Any>?`, `readAt: LocalDateTime?`.
도메인 메서드:
- `markRead(now)` — 멱등(이미 읽었으면 유지).
- `isRead()`.
- companion `forMember(memberId, type, title, body, data)`, `forInstallation(installationId, ...)`.

**리포지토리 `NotificationJpaRepository`** —
- `findPageByMemberId(memberId, cursor: Long?, pageable): List<Notification>` — `@Query` JPQL `where n.memberId = :memberId and (:cursor is null or n.id < :cursor) order by n.id desc`.
- `countByMemberIdAndReadAtIsNull(memberId): Long`.
- `markAllReadByMemberId(memberId, now): Int` — `@Modifying` 벌크 UPDATE (`read_at is null` 만).

## 4. push_dispatch — 발송 추적

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity |
| notification_id | BIGINT | NOT NULL, FK `fk_push_dispatch_notification` → notification(id), KEY | 알림 1 : 추적 N |
| push_token_id | BIGINT | NULL | 발송 당시 토큰 행 id (FK 없음) |
| expo_token | VARCHAR(255) | NOT NULL | 토큰 스냅샷 |
| ticket_id | VARCHAR(64) | NULL | Expo 접수 번호 (실패 시 NULL) |
| dispatch_status | ENUM('PENDING','SENT','DELIVERED','FAILED') | NOT NULL DEFAULT 'PENDING' | `PushDispatchStatus` (`status` 는 BaseEntity 가 점유) |
| error | VARCHAR(255) | NULL | `DeviceNotRegistered` 등 |
| status / created_at / updated_at | | BaseEntity | |

인덱스: `idx_push_dispatch_status_created (dispatch_status, created_at)` — 영수증 조회 대상 스캔용.

**엔티티 `PushDispatch`** — 필드: `notificationId`, `pushTokenId: Long?`, `expoToken`, `ticketId: String?`, `dispatchStatus`, `error: String?`.
도메인 메서드(상태 전이): `markSent(ticketId)` PENDING→SENT, `markDelivered()` SENT→DELIVERED, `markFailed(error)` PENDING|SENT→FAILED.

**리포지토리** — `findByNotificationId(notificationId): List<PushDispatch>`, `findByDispatchStatusAndCreatedAtBefore(status, before, pageable): List<PushDispatch>`.

## 상태 전이

```
PushDispatch: PENDING --markSent--> SENT --markDelivered--> DELIVERED
              PENDING|SENT --markFailed--> FAILED
MemberNotificationSetting.nudge: off --updateNudge(true,now)--> on (optInAt=now)
                                 on  --updateNudge(false)--> off (optInAt=null)
Notification.readAt: null --markRead(now)--> now (재호출 무변화)
```

## 마이그레이션 (한 파일, 생성 시각 버전)

순서 비의존: 참조하는 `member` 는 init 스키마에 존재. 테이블 생성 순서는 `push_token` → `member_notification_setting` → `notification` → `push_dispatch`. 스타일은 `community_post` 마이그레이션(백틱 없는 소문자, `ENUM('ACTIVE','DELETED') NOT NULL DEFAULT 'ACTIVE'`, `DATETIME(6)`)을 따른다.

## 테스트 정리

`TestTables.clearAll` 목록 앞쪽(FK 자식 우선)에 `push_dispatch`, `notification`, `push_token`, `member_notification_setting` 추가. `ModuleBoundaryTest.allowedDomainDeps` 에 `"notification" to emptySet()`.
