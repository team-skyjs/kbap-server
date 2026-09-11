# Data Model: 알림 설정 기기별 분리 (KB-544)

## 변경 요약

| 엔티티/테이블 | 변경 |
|---------------|------|
| `NotificationSetting` / `notification_setting` | 컬럼 2개 추가(`installation_id` NOT NULL, `news`), 기존 회원 단위 행 삭제, 고유키 교체 `(member_id)` → `(member_id, installation_id)`, 도메인 메서드 `updateNews`·팩토리 `defaultFor(memberId, installationId)` 추가 |
| `NotificationConsent` / `notification_consent` | 변경 없음(회원 단위 원장 유지). `installation_id` 는 종전처럼 "동의 받은 기기" 출처 기록 |
| `NotificationDevice` / `notification_device` | 변경 없음. 회원의 기기 집합(`member_id`)이 "연결된 기기" 의 단일 출처 |
| `Notification`·`NotificationDispatch` | 변경 없음 |

## NotificationSetting (변경 후)

```kotlin
@Entity
@Table(
    name = "notification_setting",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_notification_setting_member_installation", columnNames = ["member_id", "installation_id"]),
    ],
)
class NotificationSetting(
    @Column(name = "member_id", nullable = false) var memberId: Long = 0,
    @Column(name = "installation_id", nullable = false, length = 36) var installationId: String = "",
    @Column(name = "activity", nullable = false) var activity: Boolean = false,
    @Column(name = "meal_time", nullable = false) var mealTime: Boolean = false,
    @Column(name = "news", nullable = false) var news: Boolean = false,
) : BaseEntity() {
    fun updateActivity(enabled: Boolean)
    fun updateMealTime(enabled: Boolean)
    fun updateNews(enabled: Boolean)
    companion object {
        fun defaultFor(memberId: Long, installationId: String): NotificationSetting
    }
}
```

| 필드 | 타입 | 제약 | 의미 |
|------|------|------|------|
| `memberId` | Long | NOT NULL, FK member(id) | 소유 회원 |
| `installationId` | String | VARCHAR(36) NOT NULL | 기기 설치 id(`notification_device.installation_id` 와 같은 값) |
| `activity` | Boolean | NOT NULL DEFAULT FALSE | 활동 알림(HELPFUL·REVIEW_REMINDER) |
| `mealTime` | Boolean | NOT NULL DEFAULT FALSE | 식사시간 알림(MEAL_TIME) |
| `news` | Boolean | NOT NULL DEFAULT FALSE | 소식 알림(SCAN_SUGGESTION·NEWS) **기기 토글** |

고유키: `(member_id, installation_id)`.

### 불변 규칙

- 기기 행은 **설정 조회·수정 시 처음 만들어진다**(조회는 만들지 않고 기본값을 계산해 돌려준다). 토큰 등록·로그인은 행을 만들지 않는다.
- `news` 는 기기 저장값이고 회원 동의와 독립이다. 응답 `news.enabled` 는 저장값 그대로. 동의 상태는 회원 원장(`notification_consent`)에서 종류별 열린 최신 1건으로 따로 실린다. 발송만 "기기 `news` AND 회원 동의 유효(버전 ≥ 요구치)" 를 검사한다.
- `mealTime = true` 는 이 기기 `news = true` 일 때만 허용된다(동의 무관). 응답의 `news.mealTime` 은 `mealTime && news` 로 계산한다.
- 동의 원장은 명시적 `consent` 요청으로만 바뀐다. 기기 토글 변경은 원장을 건드리지 않고, 철회는 기기 토글을 건드리지 않는다.
- 탈퇴 시 기기 행은 `status = DELETED`(BaseEntity 소프트 삭제). 로그아웃은 행을 건드리지 않는다.

### 상태 전이 (기기 행 기준)

```
없음 ──(GET)──▶ 없음 (응답은 전부 꺼짐)
없음 ──(PATCH)──▶ 기본 행(전부 FALSE) → 요청 필드 반영
처리 순서: activity → news.consent → news.enabled → news.mealTime
activity: 요청값 그대로
news.consent=true : 회원 동의 보장(grant, 두 버전 필수) — 기기 행 불변
news.consent=false: 회원 열린 동의 전부 닫기(revoked_at) — 기기 행 불변
news.enabled     : news=요청값 — 원장 불변
news.mealTime=true : 이 기기 news 가 false 면 NOTIFICATION-001, 아니면 mealTime=true
news.mealTime=false: mealTime=false
탈퇴 ──▶ DELETED
```

## 마이그레이션

파일: `api/src/main/resources/db/migration/V<생성 시각>__notification_setting_per_device.sql`

```sql
ALTER TABLE notification_setting
    ADD COLUMN installation_id VARCHAR(36) NULL AFTER member_id,
    ADD COLUMN news BOOLEAN NOT NULL DEFAULT FALSE AFTER meal_time;

DELETE FROM notification_setting WHERE installation_id IS NULL;

ALTER TABLE notification_setting
    MODIFY COLUMN installation_id VARCHAR(36) NOT NULL,
    ADD UNIQUE KEY uk_notification_setting_member_installation (member_id, installation_id);

ALTER TABLE notification_setting
    DROP INDEX uk_notification_setting_member;
```

기존 회원 단위 행은 읽는 코드가 없어 삭제한다(FR-013, 2차 개정). 순서 의존 없는 독립 마이그레이션(out-of-order 안전).

## 리포지토리 변경

`NotificationSettingJpaRepository`

| 메서드 | 용도 |
|--------|------|
| `findByMemberIdAndInstallationId(memberId, installationId): NotificationSetting?` | 이 기기 행 단건 |
| `findByMemberIdIn(memberIds): List<NotificationSetting>` | 유지 — 발송 대상 조회(호출부가 (member_id, installation_id) 로 키잉) |
| `findByMemberId(memberId): List<NotificationSetting>` | 탈퇴 시 기기 행 소프트 삭제 |

`NotificationConsentJpaRepository`·`NotificationDeviceJpaRepository`: 변경 없음(`findByMemberId(memberId): List<NotificationDevice>` 재사용).

## 조회 규칙 (읽기 조립)

새 계약 응답 조립 입력: 기기 행(없으면 기본값) + 회원 열린 동의.

```
enabled     = row.news
activity    = row.activity
mealTime    = row.mealTime && row.news
privacyConsent / receiveConsent = 종류별 열린 최신 1건(동의 상태의 단일 출처)
```

## 요청 DTO

`NotificationSettingsUpdateRequest`(`activity: Boolean?`, `news: NewsUpdateRequest?`)
`NewsUpdateRequest`(`enabled: Boolean?`, `consent: Boolean?`, `mealTime: Boolean?`, `privacyConsentVersion: Int?`, `receiveConsentVersion: Int?`) — 버전은 기존 `@Positive`·`@Max(65535)`, `@AssertTrue`: `consent != true || (두 버전 non-null)`. 종전 DTO 를 같은 이름으로 교체(2차 개정).

발송 대상(`PushTargetResolver`): 기기 `d` 의 행 `s = settings[(d.memberId, d.installationId)]`
```
toggledOn = when(type) { HELPFUL, REVIEW_REMINDER -> s?.activity; MEAL_TIME -> s?.mealTime; SCAN_SUGGESTION, NEWS -> s?.news } == true
allowed   = toggledOn && (!type.marketing || isMarketingEnabled(consents[memberId], REQUIRED_VERSION))
```
