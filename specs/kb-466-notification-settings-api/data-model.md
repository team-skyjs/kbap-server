# Data Model: 회원 알림 설정 — 활동 푸시·K-Bap 소식 두 그룹

컨텍스트 `com.kbap.common.domain.notification`(KB-464). 이 기능은 두 테이블을 바꾸고 나머지(device·notification·dispatch)는 그대로 둔다.

## 1. notification_setting — 회원 선호 (변경)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity |
| member_id | BIGINT | NOT NULL, UNIQUE `uk_notification_setting_member`, FK → member | 회원 1 : 설정 1 |
| **activity** | BOOLEAN | NOT NULL DEFAULT TRUE | 활동/소식 — 리뷰 도움됨·리뷰 작성 리마인더 수신 |
| **meal_time** | BOOLEAN | NOT NULL DEFAULT TRUE | 식사 시간 알림 — 점심·저녁 넛지 수신. K-Bap 소식이 꺼져 있으면 저장값과 무관하게 발송·표시 모두 꺼짐 |
| ~~helpful~~ · ~~review_reminder~~ | | DROP | KB-464 컬럼. 활동/소식 하나로 통합 |
| status / created_at / updated_at | | BaseEntity | |

**엔티티 `NotificationSetting`** — 필드 `memberId`, `activity = true`, `mealTime = true`. 메서드 `updateActivity(enabled)`, `updateMealTime(enabled)`, companion `defaultFor(memberId)`. 설정 기록이 없는 회원은 저장하지 않은 `defaultFor` 인스턴스로 기본값을 읽는다(별도 값 객체 없음 — KB-464 의 `NotificationPreferences` 는 소비자가 없어 삭제).

**리포지토리** — `findByMemberId(memberId): NotificationSetting?` (변경 없음).

## 2. notification_consent — 동의 원장 (종류 축 추가)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| id | BIGINT | PK | BaseEntity |
| member_id | BIGINT | NULL, FK → member | 회원 동의. 게스트면 NULL |
| installation_id | VARCHAR(36) | NULL, FK 없음 | 동의 받은 기기 (게스트 동의의 키) |
| **consent_type** | VARCHAR(30) | NOT NULL | `MARKETING_PRIVACY`(마케팅 목적 개인정보 수집·이용) / `MARKETING_RECEIVE`(광고성 정보 수신) |
| consent_version | SMALLINT UNSIGNED | NOT NULL | 종류별 문구 버전(구 문구 = 1) |
| granted_at | DATETIME(6) | NOT NULL | 동의 시각 (서버) |
| revoked_at | DATETIME(6) | NULL | 철회 시각. NULL = 열림 |
| status / created_at / updated_at | | BaseEntity | |

제약·인덱스는 KB-464 그대로(`ck_notification_consent_subject`, `idx_notification_consent_member (member_id, revoked_at)`, `idx_notification_consent_installation (installation_id, revoked_at)`, `idx_notification_consent_open`). 종류 필터는 앱이 한다 — 주체당 열린 행이 종류별 1개 수준.

**enum `NotificationConsentType { MARKETING_PRIVACY, MARKETING_RECEIVE }`** — `com.kbap.common.domain.notification.model`.

**엔티티 `NotificationConsent`** — 필드에 `consentType: NotificationConsentType` 추가(`@Enumerated(STRING)`, `length = 30`). companion `grantForMember(memberId, installationId?, type, version, now)`·`grantForInstallation(installationId, type, version, now)` 에 `type` 인자 추가. `isOpen`·`allows`·`revoke`·`claim` 은 그대로.

**리포지토리** — 시그니처 변경 없음. `findOpenByMemberId`·`findOpenGuestByInstallationId`·`closeOpenByMemberId` 가 종류 불문 전체를 다루고, 종류별 판단은 서비스가 `groupBy(consentType)` 로 한다.

## 3. 판정 규칙 (서비스)

| 개념 | 계산 |
|---|---|
| K-Bap 소식 켜짐 (`news.enabled`) | 회원의 열린 동의에 `MARKETING_PRIVACY` 와 `MARKETING_RECEIVE` 가 **둘 다** 있음 |
| 식사 시간 알림 응답값 (`news.mealTime`) | `setting.mealTime AND news.enabled` |
| 활동/소식 응답값 (`activity`) | `setting.activity` (설정 없으면 `defaultFor` 기본값 true) |
| 동의 표시 (`privacyConsent`·`receiveConsent`) | 종류별 열린 행 중 `grantedAt` 최신 1건의 `{version, grantedAt}`, 없으면 null |

## 4. 동의 원장 사건 표 (KB-464 표의 종류 축 반영)

| 사건 | 원장 조작 (종류별) |
|---|---|
| 회원 K-Bap 소식 켜기 `{privacy: vP, receive: vR}` | 종류마다: 열린 행 중 `version != v` 는 `revoke(now)`, `== v` 가 하나라도 있으면 무변화, 없으면 `grantForMember(memberId, installationId?, type, v, now)` INSERT |
| 회원 K-Bap 소식 끄기 | `closeOpenByMemberId(memberId, now)` — 두 종류 열린 행 전부 닫음. 행 보존 |
| 게스트 토큰 등록 `marketing=true` + 두 버전 | 위 켜기와 동일하되 `grantForInstallation` |
| 게스트 토큰 등록 `marketing=false` | 기기의 열린 게스트 행 전부 `revoke(now)` |
| 로그인 (KB-465 갱신) | 게스트 열린 행 각각: 회원에게 **같은 종류** 열린 행 있음 → `revoke(now)`, 없음 → `claim(memberId)` |
| 로그아웃 | 없음 |
| 탈퇴 | `closeOpenByMemberId` (변경 없음) |

## 5. 알림 유형 ↔ 토글 대응 (참고, 소비는 발송 배치)

| `NotificationType` | 그룹 | 토글 |
|---|---|---|
| HELPFUL | 활동 푸시 | activity |
| REVIEW_REMINDER | 활동 푸시 | activity |
| SCAN_SUGGESTION | K-Bap 소식 | mealTime (+ 두 동의 열림) |
| NOTICE | 미정 | 발송 배치 태스크에서 결정 |

발송 대상 질의(KB-464 §3)는 `c.consent_version >= :v` 한 줄이 종류별 두 줄(각 종류 열린 행 존재)로 바뀐다. 이 기능에서는 질의를 작성하지 않는다.

## 6. 마이그레이션

`V2026.09.07.21.07.25__notification_setting_two_groups.sql`

```sql
ALTER TABLE notification_setting
    DROP COLUMN helpful,
    DROP COLUMN review_reminder,
    ADD COLUMN activity  BOOLEAN NOT NULL DEFAULT TRUE AFTER member_id,
    ADD COLUMN meal_time BOOLEAN NOT NULL DEFAULT TRUE AFTER activity;

ALTER TABLE notification_consent
    ADD COLUMN consent_type VARCHAR(30) NOT NULL DEFAULT 'MARKETING_RECEIVE' AFTER installation_id;
ALTER TABLE notification_consent
    ALTER COLUMN consent_type DROP DEFAULT;
```

KB-464 파일(`V2026.09.07.15.39.45`)은 수정하지 않는다. 테스트는 Flyway on + `ddl-auto=validate` 로 엔티티↔스키마 정합을 잡는다.

## 7. 테스트 정리

`TestTables.clearAll` 목록은 KB-464 에서 이미 다섯 테이블을 포함한다 — 변경 없음. `ModuleBoundaryTest` 도 변경 없음(신규 도메인 의존 없음).
