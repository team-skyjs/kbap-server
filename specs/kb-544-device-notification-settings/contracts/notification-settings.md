# Contract: GET·PATCH /api/notifications/settings (기기 단위 — X-API-Version 1.0 부터)

무버전 매핑 그대로(1.0 부터 모든 버전) **회원 단위 계약을 기기 단위 계약으로 교체**한다(2026-09-11 2차 개정 — dev 전용 서비스라 계약 교체 허용, 새 버전 매핑 없음). 경로·응답 형태는 같고, 달라지는 것은 세 가지 — `X-Installation-Id` 헤더가 **필수**, 값이 **이 기기의 설정**, 소식 그룹에서 **기기 수신(`enabled`)과 회원 동의(`consent`)가 별개 항목**.

## 변경 요약

| 항목 | 변경 전(KB-466) | 변경 후(KB-544) |
|------|----------------|-----------------|
| 단위 | 회원당 설정 1벌 | **(회원, 기기)** 당 1벌 |
| `X-Installation-Id` | GET 없음 / PATCH 선택 | **GET·PATCH 필수**. 누락·공백·36자 초과 → 400 `COMMON-002` |
| 응답 `news.enabled` | 회원 동의 두 종류 열림(계산값) | **이 기기 news 저장값 그대로** |
| 응답 `news.mealTime` | `mealTime && enabled` | `mealTime && news`(기기 저장값끼리) |
| 응답 `privacyConsent`·`receiveConsent` | 종류별 열린 최신 1건 | 동일 — **동의 토글 상태의 출처**(둘 다 있으면 동의 ON) |
| 요청 `news.enabled` | true = 동의 기록 + 켜기(버전 필수), false = 동의 철회 | **이 기기 news 만** 켜고 끈다. 원장 불변. 버전 불필요 |
| 요청 `news.consent` | 없음 | **신규**. true = 회원 동의 보장(두 버전 필수, 같은 버전 무변화·다른 버전 재개방), false = 회원 열린 동의 전부 철회. 기기값 불변 |
| 요청 `news.mealTime: true` | 동의 유효해야 | **이 기기 news 켜짐**이어야(같은 요청 반영 후) — 아니면 400 `NOTIFICATION-001`. 동의 무관 |
| 처리 순서 | activity → enabled → mealTime | activity → **consent** → enabled → mealTime |
| 설정 없는 기기 | 회원 기본값(전부 false) | 전부 false(행 생성 없음) |
| 기존 회원 단위 행 | — | 마이그레이션에서 삭제(읽는 코드 없음). 앱이 다음 실행에서 기기별로 다시 저장 |

FE 매핑: 마케팅 동의 ON → 시트(체크 2) → `consent:true` + 두 버전 / 동의 OFF → 확인 모달 → `consent:false` / 소식 ON·OFF → `enabled` / 식사 시간 → `mealTime`. 동의 없으면 앱이 소식·식사시간 토글을 비활성으로 그린다(서버는 `enabled:true` 저장을 거부하지 않는다 — 발송이 동의를 검사).

**구버전 앱 영향**: 헤더 없이 부르던 앱은 400 `COMMON-002` 를 받는다. dev 환경에서만 쓰이는 기능이라 호환 계층을 두지 않았다.

## 요청

```
GET /api/notifications/settings
X-API-Version: 1.1
X-Installation-Id: <uuid, ≤36>
Authorization: Bearer <accessToken>
```

```
PATCH /api/notifications/settings
X-API-Version: 1.1
X-Installation-Id: <uuid, ≤36>
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "activity": true,
  "news": { "consent": true, "privacyConsentVersion": 2, "receiveConsentVersion": 2, "enabled": true, "mealTime": true }
}
```

모든 필드는 선택(부분 수정). 처리 순서 `activity → news.consent → news.enabled → news.mealTime`. `news.consent: true` 면 두 버전 필수(`@AssertTrue`, 400 `COMMON-002`). `news.enabled` 는 버전 없이 보낸다.

## 응답

```json
{
  "success": true,
  "payload": {
    "activity": true,
    "news": {
      "enabled": true,
      "mealTime": true,
      "privacyConsent": { "version": 2, "grantedAt": "2026-09-11T12:00:00" },
      "receiveConsent": { "version": 2, "grantedAt": "2026-09-11T12:00:00" }
    }
  }
}
```

| 상태 | 조건 |
|------|------|
| 200 | 이 기기의 현재(수정 후) 설정 |
| 400 `COMMON-002` | `X-Installation-Id` 누락·공백·36자 초과, `consent: true` 인데 두 버전 누락·양의 정수 아님 |
| 400 `NOTIFICATION-001` | 이 기기 소식이 꺼진 상태에서 `mealTime: true` |
| 401 | 인증 없음·위조·만료 |

## 시나리오 (spec ↔ 계약)

| spec | 호출 | 기대 |
|------|------|------|
| US1-1 | 기기 A PATCH `{activity:false}` | A GET → false, B GET → 종전값 |
| US1-2 | 새 기기 GET | 전부 false, `notification_setting` 행 없음 |
| US1-3 | 헤더 없음/공백/37자 | 400 `COMMON-002`, 행 없음 |
| US1-4 | `X-API-Version: 1.0` GET | 1.0 부터 같은 기기 단위 동작 |
| US2-1 | 동의 없는 회원, A `{news:{consent:true, v, v}}` | 동의 2건 열림(installation_id=A), A 기기값 불변(행 없음 유지), 응답 consent 2건 |
| US2-2 | 동의 열림, A `{news:{enabled:true}}` | A news=true, 원장 불변, B GET 종전값 |
| US2-3 | A news 켜짐, A `{news:{enabled:false}}` | A false, 동의 열림 유지 |
| US2-4 | 동의 열림·A·B 켜짐, `{news:{consent:false}}` | 동의 두 건 `revoked_at`, A·B GET `enabled=true` 그대로 |
| US2-5 | A news 꺼짐, `{news:{mealTime:true}}` | 400 `NOTIFICATION-001`; A news 켜짐이면 동의 없어도 200 |
| US2-6 | 동의 v1 열림, `{news:{consent:true, v2, v2}}` | v1 닫히고 v2 열림; 같은 버전 재요청은 무변화 |
| US2-7 | `{news:{consent:true, privacyConsentVersion:2}}` | 400 `COMMON-002`, 원장·기기값 불변 |
| US2-8 | 동의 없는 회원 `{news:{consent:false}}` | 200 무변화 |
| US2-9 | `{news:{consent:true, v, v, enabled:true, mealTime:true}}` | 셋 다 켜짐(순서 consent→enabled→mealTime) |
| US4-1 | A 설정 후 로그아웃·재로그인·GET | 값 유지 |
| US4-2 | 탈퇴 | 기기 행 DELETED, 동의 닫힘, 기기 연결 해제 |
| US4-3 | 토큰 등록만 | 행 없음 |

## Swagger 서술(`NotificationApi`)

- `getSettings` — "`X-Installation-Id` 필수. 이 기기의 설정을 돌려준다. 설정이 없는 기기는 전부 false 이고 기록을 만들지 않는다. `news.enabled` 는 이 기기 소식 토글 저장값이고, 동의 상태는 `privacyConsent`·`receiveConsent`(회원 단위, 종류별 열린 최신)로 읽는다. 발송은 기기 토글 AND 회원 동의 유효(버전 ≥ 요구치)를 검사한다. 1.0 부터 동작."
- `updateSettings` — 위 변경 요약의 `consent`(동의 켜기/끄기, 회원 단위)·`enabled`(이 기기 수신)·mealTime 선행 조건·처리 순서.
