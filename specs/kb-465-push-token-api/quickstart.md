# Quickstart: 푸시 토큰 등록 API 검증

## 1. Red 확인 (테스트 먼저)

`api/src/test/kotlin/com/kbap/api/notification/NotificationTokenControllerTest.kt`(US1·US2)와 `api/src/test/kotlin/com/kbap/api/auth/AuthNotificationLinkTest.kt`(US3)를 `@IntegrationTest` + `BehaviorSpec` 으로 작성한다. 두 테스트 모두 **`X-API-Version: 1.1` 을 명시**(MockMvc 기본은 1.0)하고, `beforeSpec` 에서 `TestTables.clearAll(dataSource)`, 회원 로그인은 `FakeSocialTokenVerifier`(`AuthControllerTest` 방식), 상태 검증은 `DataSource` 로 `notification_device`·`notification_consent` 를 직접 SELECT.

```bash
./gradlew :api:test
```

컨트롤러가 없으면 `PUT /api/notifications/tokens` 가 404 로 떨어지고, 1.1 헤더로 `login()` 을 호출해도 기기 연결이 안 되어 US3 단정이 실패하는 것이 Red 다. Kotest 는 `--tests` 필터를 무시하므로 모듈 전체를 돌린다(컨테이너 1개 공유).

## 2. Green

`core/ApiHeaders.kt`·`notification/` 4개 파일을 추가하고 `AuthController`·`AuthApi` 에 `1.1+` 매핑을, `AuthService` 에 기본 인자를 더한 뒤 다시 `:api:test`. **기존 `AuthControllerTest`(1.0)가 무수정으로 Green 이어야 한다** — 1.0 무영향의 증거. `ModuleBoundaryTest`(태그 `arch`)가 `api.notification` 의 의존 방향(→ `common.domain.notification`·`common.core`)을 함께 검사한다.

## 3. 로컬 실기동 확인

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew :api:bootRun
```

```bash
# 게스트 등록 + 동의 on
curl -X PUT localhost:8080/api/notifications/tokens \
  -H 'X-API-Version: 1.1' -H 'X-Installation-Id: 11111111-1111-1111-1111-111111111111' \
  -H 'Content-Type: application/json' \
  -d '{"token":"ExponentPushToken[abc]","platform":"ios","lang":"en","settings":{"marketing":true,"marketingConsentVersion":1}}'

# 헤더 누락 → 400 COMMON-002
curl -X PUT localhost:8080/api/notifications/tokens -H 'X-API-Version: 1.1' -H 'Content-Type: application/json' -d '{"token":"t","platform":"ios","lang":"en"}'

# 1.0 버전 → 404 (토큰 API 는 1.1 이상 전용 — 이 버전에 존재하지 않는 API)
curl -X PUT localhost:8080/api/notifications/tokens -H 'X-API-Version: 1.0' -H 'X-Installation-Id: 11111111-1111-1111-1111-111111111111' -H 'Content-Type: application/json' -d '{"token":"t","platform":"ios","lang":"en"}'
```

```sql
SELECT installation_id, member_id, expo_token, token_invalid_at FROM notification_device;
SELECT member_id, installation_id, consent_version, granted_at, revoked_at FROM notification_consent;
```

Swagger UI(`/swagger-ui.html`)에서 그룹 `X-API-Version 1.1` 을 고르면 "푸시" 태그에 `PUT /api/notifications/tokens` 가 헤더·본문 스키마와 함께 보이고, 인증 태그의 login·logout 에 `X-Installation-Id` 가 보이면 SC-001 충족. `1.0` 그룹에는 토큰 API 가 없고 인증 문서가 종전과 같아야 한다.

## 4. 수용 시나리오 ↔ 테스트 매핑

| Spec | 테스트 클래스 · given |
|---|---|
| US1-AS1~4 (생성·갱신·무효 해제·회원 연결) | `NotificationTokenControllerTest` — "기기 토큰 등록" |
| US1-AS5~6 (헤더 없음·검증 실패 400) | `NotificationTokenControllerTest` — "잘못된 등록 요청" |
| US2-AS1~7 (게스트 동의 on/off/버전 교체/회원 무시/버전 필수) | `NotificationTokenControllerTest` — "게스트 광고성 동의" |
| US3-AS1~2 (로그인 인수/철회 분기) · AS3 (미등록 기기) · AS6 (헤더 없음) | `AuthNotificationLinkTest` — "게스트 기기에서 로그인" |
| US3-AS4 (로그아웃 unlink, 원장 불변) | `AuthNotificationLinkTest` — "회원 기기에서 로그아웃" |
| US3-AS5 (탈퇴 — 기기 2대 unlink + 동의 전부 철회, 행 보존) | `AuthNotificationLinkTest` — "회원 탈퇴" |
| Edge — 게스트 동의 2건 열림 상태에서 로그인 | `AuthNotificationLinkTest` — "게스트 기기에서 로그인" 에 시나리오 추가 |
| Edge — 회원 A 기기에서 B 로그인 | `AuthNotificationLinkTest` — "다른 회원이 연결된 기기에서 로그인" |
| SC-002 (설치→등록→로그인→로그아웃→재로그인 기록 1건) | `AuthNotificationLinkTest` — 위 given 들의 연속 시나리오로 카운트 단정 |
| FR-016 — 토큰 API 1.0 요청 404 | `NotificationTokenControllerTest` — "잘못된 등록 요청" |
| FR-016 — 1.0 로그인·로그아웃·탈퇴는 기기·동의 무처리 | `AuthNotificationLinkTest` — "1.0 인증 API 무영향" + 기존 `AuthControllerTest` 무수정 Green |

무효 스탬프(US1-AS3)는 API 로 만들 수 없으므로 테스트가 `UPDATE notification_device SET token_invalid_at = NOW(6)` 로 심는다.
