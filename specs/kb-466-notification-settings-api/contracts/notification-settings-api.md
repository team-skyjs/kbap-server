# Contract: 회원 알림 설정 API

베이스 `/api/notifications/settings`. 두 오퍼레이션 모두 무버전 매핑이라 `X-API-Version: 1.0` 부터 동작(헤더 자체는 필수), JWT 필수(게스트 401 `AUTH-002`), 응답은 `BaseResponse<NotificationSettingsResponse>`.

## GET /api/notifications/settings

요청 본문 없음. 헤더 `Authorization: Bearer <access>`, `X-API-Version`(1.0 이상), `X-Installation-Id`(선택, 이 오퍼레이션에서는 미사용).

응답 200:

```json
{
  "success": true,
  "payload": {
    "activity": true,
    "news": {
      "enabled": true,
      "mealTime": false,
      "privacyConsent": { "version": 1, "grantedAt": "2026-09-07T12:00:00" },
      "receiveConsent": { "version": 2, "grantedAt": "2026-09-07T12:00:00" }
    }
  }
}
```

| 필드 | 타입 | 의미 |
|---|---|---|
| `activity` | boolean | 활동/소식(리뷰 도움됨·리뷰 작성 리마인더). OS 알림 정책상 설정 없으면 `false` |
| `news.enabled` | boolean | 두 동의(개인정보 수집·이용 + 광고성 수신) 모두 유효 |
| `news.mealTime` | boolean | 식사 시간 알림. `enabled=false` 면 저장값과 무관하게 `false` |
| `news.privacyConsent` | object \| null | 마케팅 목적 개인정보 수집·이용 동의 — 열린 최신 1건 `{version, grantedAt}`. 없으면 `null` |
| `news.receiveConsent` | object \| null | 광고성 정보 수신 동의 — 위와 같음 |

설정 기록이 없는 회원: `{ "activity": false, "news": { "enabled": false, "mealTime": false, "privacyConsent": null, "receiveConsent": null } }`. 조회는 기록을 만들지 않는다.

## PATCH /api/notifications/settings

부분 수정. 보낸 필드만 반영하고 응답은 GET 과 같은 전체 설정.

요청 본문 `NotificationSettingsUpdateRequest`:

```json
{
  "activity": false,
  "news": {
    "enabled": true,
    "mealTime": true,
    "privacyConsentVersion": 1,
    "receiveConsentVersion": 2
  }
}
```

| 필드 | 타입 | 규칙 |
|---|---|---|
| `activity` | boolean? | 없으면 유지 |
| `news` | object? | 없으면 그룹 전체 유지 |
| `news.enabled` | boolean? | `true` = 두 동의 기록(켜기) + 하위 토글(`mealTime`) 전부 켜짐, `false` = 두 동의 철회(끄기). 없으면 동의 상태 유지 |
| `news.privacyConsentVersion` | int? (1~65535) | `enabled=true` 면 **필수**. 그 외 무시 |
| `news.receiveConsentVersion` | int? (1~65535) | `enabled=true` 면 **필수**. 그 외 무시 |
| `news.mealTime` | boolean? | 없으면 유지. `true` 는 (이 요청의 `enabled` 반영 후) 소식이 켜져 있어야 함 |
| `grantedAt` 류 클라이언트 시각 | 받지 않음 | 서버 시각으로 기록 |

처리 순서: `activity` → `news.enabled`(동의 grant/revoke) → `news.mealTime`. 빈 본문 `{}` 은 무변화 200.

동의 grant 규칙(종류별): 열린 행 중 버전이 다른 것은 철회 시각 스탬프, 같은 버전이 있으면 무변화, 없으면 새 행. 켜기는 `mealTime` 을 함께 켠다. 끄기는 두 종류의 열린 행 전부 철회(행 보존).

### 오류

| 상황 | 상태 | code |
|---|---|---|
| `enabled=true` 인데 두 버전 중 하나라도 없음 / 양의 정수 아님 | 400 | `COMMON-002` |
| `mealTime=true` 인데 소식이 꺼짐(동의 미완) | 400 | `NOTIFICATION-001` (`MARKETING_CONSENT_REQUIRED`) |
| 인증 없음 | 401 | `AUTH-002` |
| `X-API-Version` 누락·미지원 | 400 | `COMMON-002` |

### 예시

켜기(첫 동의): `{ "news": { "enabled": true, "privacyConsentVersion": 1, "receiveConsentVersion": 1 } }` → `enabled=true, mealTime=true`(하위 토글 전부 켜짐), 두 consent 채워짐.

식사 시간 알림만 끄기: `{ "news": { "mealTime": false } }` → `mealTime=false`, 동의 그대로.

끄기: `{ "news": { "enabled": false } }` → `enabled=false, mealTime=false`, 두 consent `null`.

다시 켜기(같은 버전): 위 켜기 본문 → 원장 무변화(새 행 없음), `mealTime=true` 로 켜짐.

수신 동의 문구 개정 후 재동의: `{ "news": { "enabled": true, "privacyConsentVersion": 1, "receiveConsentVersion": 2 } }` → receive 이전 행 철회 + v2 새 행, privacy 무변화.

## 부수 계약 변경 — PUT /api/notifications/tokens (KB-465)

게스트 동의 `settings` 객체가 두 버전을 받는다.

```json
{ "token": "...", "platform": "ios", "lang": "ko",
  "settings": { "marketing": true, "privacyConsentVersion": 1, "receiveConsentVersion": 1 } }
```

`marketingConsentVersion` 필드는 삭제. `marketing=true` 면 두 버전 필수(400 `COMMON-002`). 회원 요청의 `settings` 는 종전대로 무시. 앱은 아직 연동 전이라 호환 유지 없이 교체하며, Jira KB-465 본문과 FE 공유를 갱신한다.
