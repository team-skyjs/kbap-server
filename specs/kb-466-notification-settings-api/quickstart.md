# Quickstart: 회원 알림 설정 API

## 검증 순서

1. `./gradlew :common:test :api:test --tests "com.kbap.api.notification.*"` — 통합 테스트(Testcontainers MySQL, Flyway on). 새 마이그레이션이 `ddl-auto=validate` 를 통과해야 컨텍스트가 뜬다.
2. `./gradlew build` — ArchUnit·전체 회귀.
3. dev 배포 후 Swagger 에서 `GET/PATCH /api/notifications/settings`(`/v3/api-docs/1.0` 그룹부터)와 갱신된 `PUT /api/notifications/tokens`(`1.1` 그룹) 계약 확인 → FE 세션에 공유, Jira KB-465·KB-466 본문 갱신.

## 수동 시나리오 (dev, 회원 access 토큰 필요)

```bash
H='-H "Authorization: Bearer $ACCESS" -H "X-API-Version: 1.0" -H "Content-Type: application/json"'
BASE=https://dev.kbap.site/api/notifications/settings

# 기본값
curl -s $H $BASE                      # activity=true, news.enabled=false, consents null

# K-Bap 소식 켜기 (두 동의)
curl -s -X PATCH $H $BASE -d '{"news":{"enabled":true,"privacyConsentVersion":1,"receiveConsentVersion":1}}'
# → enabled=true, mealTime=true, 두 consent 에 version·grantedAt

# 식사 시간 알림만 끄기
curl -s -X PATCH $H $BASE -d '{"news":{"mealTime":false}}'

# 끄기 → 다시 켜기: mealTime=false 가 복원되는지
curl -s -X PATCH $H $BASE -d '{"news":{"enabled":false}}'
curl -s -X PATCH $H $BASE -d '{"news":{"enabled":true,"privacyConsentVersion":1,"receiveConsentVersion":1}}'

# 동의 없이 식사 시간 알림 켜기 → 400 NOTIFICATION-001
curl -s -X PATCH $H $BASE -d '{"news":{"enabled":false}}'
curl -s -X PATCH $H $BASE -d '{"news":{"mealTime":true}}'
```

원장 확인(dev DB):

```sql
SELECT id, member_id, consent_type, consent_version, granted_at, revoked_at
FROM notification_consent WHERE member_id = :memberId ORDER BY id;
```

켜기 → 끄기 → 다시 켜기 후 종류별로 (v1 열림→철회) + (v1 새 열림) 이 남아야 하고 삭제된 행은 없어야 한다.

## 되돌리기

앱 연동 전이므로 코드 revert 만으로 충분하다. 새 마이그레이션은 dev DB 에 적용된 뒤엔 되돌리지 않는다(KB-464 규칙과 동일) — 구 컬럼이 필요하면 새 마이그레이션으로 다시 추가한다.
