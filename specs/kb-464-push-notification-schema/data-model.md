# Data Model: 푸시 알림 데이터 기반

컨텍스트: `com.kbap.common.domain.notification` (신설). 모든 엔티티는 `BaseEntity` 상속(`id`·`status`·`created_at`·`updated_at` 공통, `@SQLRestriction("status = 'ACTIVE'")`). 다른 컨텍스트는 `Long` id 로만 참조.

설계 축은 **주체 × 종류** 둘이다. 선호(도움됨·리마인더)는 회원만 갖는 현재 상태값이라 회원 테이블에, 광고성 동의는 회원이든 게스트든 "동의 행위" 이므로 원장 한 테이블에 둔다(2026-09-07 DBA·CTO 교차 검토 결론, research R4·R5·R7).

## 1. notification_device — 기기 푸시 토큰 (기기 정체성, 삭제하지 않음)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK AUTO_INCREMENT | BaseEntity |
| installation_id | VARCHAR(36) | NOT NULL, **UNIQUE `uk_notification_device_installation`** | 기기 설치 UUID (X-Installation-Id) |
| member_id | BIGINT | NULL, FK `fk_notification_device_member` → member(id), KEY `idx_notification_device_member` | 연결 회원 (게스트면 NULL) |
| expo_token | VARCHAR(255) | NOT NULL | `ExponentPushToken[...]` |
| platform | ENUM('IOS','ANDROID') | NOT NULL | |
| lang | VARCHAR(10) | NOT NULL | 기기 언어 코드, 검증 없이 보관 |
| token_invalid_at | DATETIME(6) | NULL | Expo `DeviceNotRegistered` 스탬프. NULL = 유효 |
| status / created_at / updated_at | | BaseEntity | |

**이 테이블에는 `BaseEntity.delete()` 를 호출하지 않는다.** 소프트삭제하면 UNIQUE 는 남고 `@SQLRestriction` 조회는 그 행을 못 봐 같은 기기의 재등록이 영구히 duplicate key 로 실패한다. 무효 토큰은 `markTokenInvalid(now)` 스탬프, 재등록(`renew`)이 `tokenInvalidAt = null` 로 되살린다. 발송 대상 조회는 `token_invalid_at IS NULL` 을 건다.

**엔티티 `NotificationDevice`** — 필드: `installationId`, `memberId: Long?`, `expoToken`, `platform: DevicePlatform`, `lang`, `tokenInvalidAt: LocalDateTime?`.
도메인 메서드: `isTokenValid()`, `linkMember(memberId)`, `unlinkMember()`, `renew(expoToken, platform, lang)`(무효 스탬프 해제 포함), `markTokenInvalid(now)`, companion `register(installationId, expoToken, platform, lang, memberId?)`.

**리포지토리 `NotificationDeviceJpaRepository`** — `findByInstallationId(installationId): NotificationDevice?`, `findByMemberId(memberId): List<NotificationDevice>`. 토큰 문자열 역조회는 두지 않는다 — 영수증 정리는 `notification_dispatch.notification_device_id` 로 기기를 찾는다.

## 2. notification_setting — 회원 선호 (회원 전용)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity |
| member_id | BIGINT | NOT NULL, **UNIQUE `uk_notification_setting_member`**, FK → member(id) | 회원 1 : 설정 1 |
| helpful | BOOLEAN | NOT NULL DEFAULT TRUE | 리뷰 도움됨 수신 |
| review_reminder | BOOLEAN | NOT NULL DEFAULT TRUE | 리뷰 리마인더 수신 |
| status / created_at / updated_at | | BaseEntity | |

게스트는 행이 없다 — 리뷰·주문이 회원 전용 행위라 선호가 존재하지 않는다.

**값 객체 `NotificationPreferences(helpful = true, reviewReminder = true)`** — `DEFAULT` 상수. 설정 응답 조립용.

**엔티티 `NotificationSetting`** — 필드: `memberId`, `helpful`, `reviewReminder`. 메서드 `preferences()`, `updateHelpful`, `updateReviewReminder`, companion `defaultFor(memberId)`.

**리포지토리** — `findByMemberId(memberId): NotificationSetting?`.

## 3. notification_consent — 광고성 수신 동의 원장 (유일한 정본, append-only)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity |
| member_id | BIGINT | NULL, FK `fk_notification_consent_member` → member(id) | 회원 동의. 게스트면 NULL. FK 는 NULL 을 검사하지 않고 값이 있으면 실존 회원을 강제 |
| installation_id | VARCHAR(36) | NULL, FK 없음 | 동의를 받은 기기(증빙). 게스트 동의의 키. 기기 행이 무효화돼도 이력은 남아야 하므로 FK 를 걸지 않는다 |
| consent_version | SMALLINT UNSIGNED | NOT NULL | 동의 문구 버전(정수). 구 문구("점심 스캔 알림" 한정) = 1 |
| granted_at | DATETIME(6) | NOT NULL | 동의 시각 (서버 스탬프) |
| revoked_at | DATETIME(6) | NULL | 철회 시각. NULL = 현재 유효(열린 행) |
| status / created_at / updated_at | | BaseEntity | |

제약·인덱스: `CHECK ck_notification_consent_subject (member_id IS NOT NULL OR installation_id IS NOT NULL)`(둘 다 NULL 금지, 둘 다 채워진 행은 "회원 동의 + 받은 기기" 정상 상태), `idx_notification_consent_member (member_id, revoked_at)`, `idx_notification_consent_installation (installation_id, revoked_at)`, `idx_notification_consent_open (revoked_at, consent_version, granted_at)` — 발송 대상·2년 재확인 스윕용.

**행 1개 = 동의 1회의 생애.** 원장 행은 절대 삭제하지 않는다(탈퇴 포함 — `delete()` 호출 금지). "주체당 열린 행 1개" 는 MySQL 에 부분 유니크가 없어 앱이 지키되, 철회가 열린 행을 **전부** 닫고 발송 조회가 `DISTINCT` 라 위반해도 무해하다.

| 사건 | 원장 조작 |
|---|---|
| 게스트가 토큰 등록 시 동의 on | `grantForInstallation(installationId, version, now)` INSERT |
| 회원이 설정에서 on | `grantForMember(memberId, 요청 기기, version, now)` INSERT |
| off (철회) | `closeOpenByMemberId(memberId, now)` — 열린 행 전부 `revoked_at` 스탬프. 행 보존 |
| 재동의·문구 버전 변경·2년 재확인 | 열린 행 닫고 새 행 INSERT → 최초 동의 시각 보존 |
| 게스트 기기에서 로그인 (KB-465) | 회원에게 열린 행 없음 → `findOpenGuestByInstallationId` 행에 `claim(memberId)` (인수, 시각 보존). 있음 → 게스트 행 `revoke` |
| 로그아웃 | 아무것도 안 함. 기기에 열린 게스트 행이 없으니 옛 동의가 되살아날 수 없다 |
| 탈퇴 (KB-465) | `closeOpenByMemberId` — 보존하되 대상에서 제외 |

**엔티티 `NotificationConsent`** — 필드: `memberId: Long?`, `installationId: String?`, `consentVersion: Int`, `grantedAt`, `revokedAt: LocalDateTime?`.
도메인 메서드: `isOpen()`, `allows(requiredVersion: Int)` = 열림 AND 버전 ≥ 요구, `revoke(now)`(이미 철회면 `IllegalStateException`), `claim(memberId)`(회원 행이거나 철회됐으면 `IllegalStateException`), companion `grantForMember`, `grantForInstallation`.

**리포지토리 `NotificationConsentJpaRepository`** — `findOpenByMemberId(memberId): List<NotificationConsent>`(설정 API 는 `firstOrNull`), `findOpenGuestByInstallationId(installationId): NotificationConsent?`(`member_id IS NULL` 조건 포함 — 로그아웃 부활 차단), `closeOpenByMemberId(memberId, now): Int`(`@Modifying`).

발송 대상 조회(KB-471 에서 작성):
```sql
-- 회원
SELECT DISTINCT d.id, d.expo_token, d.lang
FROM notification_consent c JOIN notification_device d ON d.member_id = c.member_id
WHERE c.revoked_at IS NULL AND c.consent_version >= :v AND d.token_invalid_at IS NULL;
-- 게스트
SELECT DISTINCT d.id, d.expo_token, d.lang
FROM notification_consent c JOIN notification_device d ON d.installation_id = c.installation_id
WHERE c.revoked_at IS NULL AND c.member_id IS NULL AND d.member_id IS NULL
  AND c.consent_version >= :v AND d.token_invalid_at IS NULL;
```

## 4. notification — 알림 이력(알림함)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity, 알림함 keyset 커서 |
| member_id | BIGINT | NULL, FK `fk_notification_member` → member(id) | 수신 회원 |
| installation_id | VARCHAR(36) | NULL | 수신 기기 (게스트 대상 시), FK 없음 |
| type | VARCHAR(30) | NOT NULL | `NotificationType` — HELPFUL·SCAN_SUGGESTION·REVIEW_REMINDER·NOTICE |
| title | VARCHAR(200) | NOT NULL | 발송 시점 렌더 문자열 |
| body | VARCHAR(1000) | NOT NULL | |
| data | JSON | NULL | FE 계약 `{ type, foodId?, notificationId? }` |
| read_at | DATETIME(6) | NULL | 읽음 시각 |
| status / created_at / updated_at | | BaseEntity | |

인덱스: `idx_notification_member_id (member_id, id)`, `idx_notification_installation_id (installation_id, id)`.

**엔티티 `Notification`** — 필드: `memberId: Long?`, `installationId: String?`, `type`, `title`, `body`, `data: Map<String, Any>?`, `readAt: LocalDateTime?`. 메서드 `markRead(now)`(멱등), `isRead()`, companion `forMember(...)`, `forInstallation(...)`.

**리포지토리 `NotificationJpaRepository`** — `findPageByMemberId(memberId, cursor: Long?, pageable)`(JPQL, `id desc`), `countByMemberIdAndReadAtIsNull(memberId)`, `markAllReadByMemberId(memberId, now)`(`@Modifying`, `read_at is null` 만).

## 5. notification_dispatch — 발송 추적

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity |
| notification_id | BIGINT | NOT NULL, FK `fk_notification_dispatch_notification`, KEY `idx_notification_dispatch_notification` | 알림 1 : 추적 N |
| notification_device_id | BIGINT | NULL, FK 없음 | 발송 당시 기기 행 id. 영수증 정리가 기기를 찾는 키 |
| expo_token | VARCHAR(255) | NOT NULL | 토큰 스냅샷 |
| ticket_id | VARCHAR(64) | NULL | Expo 접수 번호 (실패 시 NULL) |
| dispatch_status | ENUM('PENDING','SENT','DELIVERED','FAILED') | NOT NULL DEFAULT 'PENDING' | `NotificationDispatchStatus` |
| error | VARCHAR(255) | NULL | `DeviceNotRegistered` 등 |
| status / created_at / updated_at | | BaseEntity | |

인덱스: `idx_notification_dispatch_status_created (dispatch_status, created_at)`.

**엔티티 `NotificationDispatch`** — 상태 전이 `markSent(ticketId)` PENDING→SENT, `markDelivered()` SENT→DELIVERED, `markFailed(error)` PENDING|SENT→FAILED, 그 외 `IllegalStateException`. companion `pending(notificationId, notificationDeviceId, expoToken)`.

**리포지토리** — `findByNotificationId(notificationId)`, `findByDispatchStatusAndCreatedAtBefore(status, before, pageable)`.

## 상태 전이

```
NotificationDispatch: PENDING --markSent--> SENT --markDelivered--> DELIVERED
                      PENDING|SENT --markFailed--> FAILED
NotificationConsent:  (open) --revoke(now)--> (closed, 보존)   /   guest(open) --claim(memberId)--> member(open)
NotificationDevice.tokenInvalidAt: null --markTokenInvalid--> now --renew--> null
Notification.readAt:  null --markRead(now)--> now (재호출 무변화)
```

## 마이그레이션 (한 파일, 생성 시각 버전)

`V2026.09.07.15.39.45__push_notification_tables.sql` — 순서 비의존(참조하는 `member` 는 init 스키마). 생성 순서 `notification_device` → `notification_setting` → `notification_consent` → `notification` → `notification_dispatch`. 신규 테이블만이라 카나리 중 구 코드와 공존 안전.

## 테스트 정리

`TestTables.clearAll` 목록 앞쪽(FK 자식 우선)에 `notification_dispatch`, `notification`, `notification_device`, `notification_setting`, `notification_consent`. `ModuleBoundaryTest.allowedDomainDeps` 에 `"notification" to emptySet()`.
