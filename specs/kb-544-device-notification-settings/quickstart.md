# Quickstart: 알림 설정 기기별 분리 (KB-544)

## 검증 순서

1. **마이그레이션 + 엔티티 정합** — api 통합 컨텍스트가 Flyway 로 스키마를 만들고 `ddl-auto=validate` 로 엔티티를 대조한다.
   ```bash
   ./gradlew :api:test --tests "com.kbap.api.notification.NotificationSettingControllerTest"
   ```
   (Kotest 는 `--tests` 필터를 무시하고 모듈 전체를 돌린다 — 실패 위치는 로그로 확인.)

2. **common 리포지토리·발송 대상**
   ```bash
   ./gradlew :common:test
   ```
   대상: `NotificationSettingJpaRepositoryTest`(고유키 `(member_id, installation_id)`, 회원 기준 조회), `PushTargetResolverTest`(기기별 토글·설정 없는 기기 제외·news 토글).

3. **생명주기** — `AuthNotificationLinkTest` 에 로그아웃 보존·탈퇴 소프트 삭제 시나리오.

4. **전체**
   ```bash
   ./gradlew build
   ```

## 로컬 수동 확인 (선택)

워크트리엔 `.env` 가 없으니 메인 `.env` 를 `set -a; source ../../../.env; set +a` 로 올린 뒤 `./gradlew :api:bootRun --no-daemon -Dserver.port=8081`.

```bash
TOKEN=<accessToken>
# 새 기기 조회 — 전부 false
curl -s localhost:8081/api/notifications/settings -H "X-API-Version: 1.1" -H "X-Installation-Id: dev-A" -H "Authorization: Bearer $TOKEN"
# 마케팅 동의 켜기(회원) + 기기 A 소식 켜기
curl -s -X PATCH localhost:8081/api/notifications/settings -H "X-API-Version: 1.1" -H "X-Installation-Id: dev-A" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"news":{"consent":true,"privacyConsentVersion":2,"receiveConsentVersion":2,"enabled":true}}'
# 기기 A 소식만 끄기 — 동의는 그대로(privacyConsent/receiveConsent 유지)
curl -s -X PATCH localhost:8081/api/notifications/settings -H "X-API-Version: 1.1" -H "X-Installation-Id: dev-A" -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"news":{"enabled":false}}'
# 기기 B 는 여전히 false
curl -s localhost:8081/api/notifications/settings -H "X-API-Version: 1.1" -H "X-Installation-Id: dev-B" -H "Authorization: Bearer $TOKEN"
```

Swagger: `/swagger-ui/index.html` 의 알림 태그에서 기기 단위 서술을 확인한다.

## 완료 조건 체크

- [ ] 마이그레이션 1건, 기존 행 무변경
- [ ] GET/PATCH 가 기기별 값을 돌려주고 1.0 헤더에서도 같은 동작
- [ ] 소식 토글 껐다 켜도 동의 원장 불변, `consent:false` 만 `revoked_at` 기록(기기값 불변)
- [ ] `PushTargetResolver` 가 기기 행 기준으로 대상 선정(행 없음 = 제외)
- [ ] 탈퇴 → 기기 행 DELETED, 로그아웃 → 보존
- [ ] Swagger 서술·위키 절 갱신, FE 에 헤더 필수·consent/enabled 분리 공유
