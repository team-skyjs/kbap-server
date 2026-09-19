# Quickstart: 회원 알림함 API

## 1. TDD 순서

1. `api/src/test/kotlin/com/kbap/api/notification/NotificationInboxControllerTest.kt` — `@IntegrationTest` BehaviorSpec. 로그인 헬퍼는 `NotificationSettingControllerTest` 의 `login(sub)` 복제. 시드는 `@Autowired NotificationJpaRepository.save(Notification.forMember(...))`. 8일 전 알림은 저장 후 `JdbcTemplate` 로 `UPDATE notification SET created_at = ? WHERE id = ?`. `beforeSpec` 에서 `TestTables.clearAll(dataSource)`.
2. `./gradlew :api:test` → 새 클래스 전부 Red 확인(리포트에서 클래스만 본다 — Kotest 는 `--tests` 필터 무시).
3. ErrorCode → 리포지토리 파생 쿼리 2개 → 응답 DTO → 서비스 → Api 인터페이스 → 컨트롤러 → WebConfig 순으로 구현 → Green.
4. `NotificationTokenControllerTest`(게스트 토큰 등록)·`NotificationSettingControllerTest` 가 그대로 Green 인지 확인 — 필터 경로 변경 회귀.
5. `ErrorCodeStatusTest`(arch) 가 `NOTIFICATION-002` 형식·유일성 검사.

## 2. 로컬 확인

```bash
TOKEN=<회원 access token>
H='-H "X-API-Version: 1.0" -H "Authorization: Bearer '$TOKEN'"'
mysql -h127.0.0.1 -uroot kbap -e "INSERT INTO notification(member_id,type,title,body,status,created_at,updated_at) VALUES (35,'NOTICE','t','b','ACTIVE',NOW(6),NOW(6))"

curl -s $H localhost:8080/api/notifications | jq .payload
curl -s -X PATCH $H localhost:8080/api/notifications/1/read | jq .payload.read     # true
curl -s -X PATCH $H localhost:8080/api/notifications/999999/read | jq .code        # NOTIFICATION-002
curl -s -X PUT -H "X-API-Version: 1.1" -H "X-Installation-Id: 00000000-0000-0000-0000-000000000001" \
  -H "Content-Type: application/json" -d '{"expoToken":"ExponentPushToken[x]","platform":"IOS"}' \
  localhost:8080/api/notifications/tokens -o /dev/null -w "%{http_code}\n"         # 게스트 토큰 등록 여전히 200
```

## 3. Jira

구현 후 KB-467 코멘트: "회원 전용(게스트 미제공) · 최근 7일만·페이징 없음 · 모두 읽기·읽음 취소·미읽음 수 없음 · 응답은 id·제목·본문·수신 시각·읽음 여부 · 제목/본문 저장값 그대로".
