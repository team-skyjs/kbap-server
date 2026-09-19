# Quickstart: 비회원 광고 알림 동의 제거 (KB-543) 검증

## 1. 테스트

```bash
./gradlew :common:test :api:test
```

- `NotificationTokenControllerTest` — 토큰 없이 등록 401 + 기기 행 없음, 회원 등록·갱신·멱등, 회원의 `settings` 무시·원장 불변, 헤더 검증.
- `AuthNotificationLinkTest` — 로그인 연결(잔존 게스트 동의 행 불변), 로그아웃 해제, 탈퇴 정리, 로그인→등록→로그아웃→재로그인 흐름, 1.0 무영향.
- `NotificationSettingControllerTest`·`NotificationInboxTest` — 무수정 회귀(FR-008).
- `NotificationConsentJpaRepositoryTest`·`NotificationJpaRepositoryTest` — 게스트 given 블록 삭제 후 통과.
- `ModuleBoundaryTest`(arch) 포함 전체가 통과해야 한다.

## 2. 설치 id 전용 진입점 0건(SC-003)

```bash
grep -rn -E 'grantForInstallation|revokeForInstallation|findOpenGuestByInstallationId|forInstallation\(|fun claim\(|MarketingSettingsRequest|AuthMemberIdOrNull' \
  api/src/main common/src/main | grep -v 'AuthMemberIdOrNull' ; echo "expect: no output"
grep -n 'notifications/tokens' api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt ; echo "expect: no GuestExemption line"
```

`AuthMemberIdOrNull` 자체는 home·food·review·community 컨트롤러가 계속 쓴다 — `NotificationTokenController` 에서만 사라지면 된다.

## 3. 마이그레이션 무추가(SC-004)

```bash
git diff --stat develop -- api/src/main/resources/db/migration ; echo "expect: empty"
```

## 4. Swagger 확인(FR-007)

`./gradlew :api:bootRun` 후 `/swagger-ui/index.html` → 그룹 `1.1` → 알림 태그 `PUT /api/notifications/tokens`:
- 요청 스키마에 `settings`·`MarketingSettingsRequest` 없음.
- 설명에 "회원 전용" 이 있고 "게스트" 문구가 없음.
- 401 응답 설명이 "Authorization 없음·위조·만료".

## 5. 수동 재현(선택)

```bash
# 비회원 → 401
curl -i -X PUT localhost:8080/api/notifications/tokens -H 'X-API-Version: 1.1' -H 'X-Installation-Id: dev-1' \
  -H 'Content-Type: application/json' -d '{"token":"ExponentPushToken[x]","platform":"ios","lang":"en"}'
# 회원 → 200, notification_device.member_id 채워짐
curl -i -X PUT localhost:8080/api/notifications/tokens -H 'X-API-Version: 1.1' -H 'X-Installation-Id: dev-1' \
  -H "Authorization: Bearer $ACCESS" -H 'Content-Type: application/json' \
  -d '{"token":"ExponentPushToken[x]","platform":"ios","lang":"en","settings":{"marketing":true,"privacyConsentVersion":1,"receiveConsentVersion":1}}'
# → notification_consent 행 수 불변
```
