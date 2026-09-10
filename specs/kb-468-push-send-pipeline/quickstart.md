# Quickstart: Expo Push 발송 공용 파이프라인

## 1. TDD 순서 (Red → Green 단위)

1. **Port + 어댑터** — `common/src/test/kotlin/com/kbap/common/infra/push/ExpoPushSenderTest.kt`(BehaviorSpec, `MockRestServiceServer.bindTo(RestClient.builder())` — `FrankfurterExchangeRateClientTest` 선례). 시나리오: 250건 → 요청 3회·본문 크기 100/100/50·티켓 250개 순서 유지 / ok·error(`details.error`) 매핑 / 두 번째 청크 500 → 그 100건만 error / access-token 있으면 Bearer, 없으면 헤더 없음. Red 확인 → `common/build.gradle.kts` 에 `libs.spring.web` → `common.port.push`(`PushMessage`·`PushTicket`·`PushSender`) → `common.infra.push.ExpoPushSender` → Green.
2. **렌더러** — `common/src/test/.../domain/notification/PushMessageRendererTest.kt`(순수). 파리티(5×10)·치환·광고성 부착/미부착·길이 상한. → `NotificationType.MEAL_TIME`·`marketingByDefault`, `PushTemplates`, `PushMessageRenderer` → Green.
3. **대상 필터** — `PushTargetResolverTest.kt`(`@SpringBootTest + @Import(MySqlContainerConfig::class)`, `CommonTestApp` 컨텍스트 — 기존 `Notification*JpaRepositoryTest` 와 동일 헤더). 시나리오: activity 꺼짐 제외 / mealTime 켜졌지만 동의 없음 제외 / 동의 v1 제외·v2 통과 / 무효 토큰 제외 / NOTICE 는 필터 없음 / 다기기 회원 n건. → 리포지토리 `in` 쿼리 3개, `NotificationConsents.isMarketingEnabled`(api `NotificationConsentService` 위임으로 교체), `PushTargetResolver` → Green.
4. **dispatch 서비스** — `PushDispatchServiceTest.kt`(같은 컨텍스트). prepare: 회원 1·기기 2(ko/ja) → notification 1행(최신 기기 언어)·dispatch 2행 PENDING·messages 2·data 에 type/notificationId / 기기 0 → 아무것도 저장 안 함. record: ok → SENT+ticketId / error → FAILED+error(기기 토큰은 건드리지 않음). → `PushRequest`·`PreparedPush`·`PushDispatchResult`·`PushDispatchService` → Green.
5. **조립** — api `PushConfig`·batch `PushConfig`(+`@Import` 도메인 서비스 3종)·yml 키. `@IntegrationTest` 의 `@Import` 에 `FakePushSenderConfig`(api `api/src/test/.../notification/FakePushSender.kt` — `FakePlaceSearchClient` 선례, 보낸 메시지 기록·전부 ok 티켓) 추가. `./gradlew :api:test --tests '*ModuleBoundaryTest*'` 로 arch 통과 확인(Kotest 는 필터를 무시하고 모듈 전체를 돌린다 — 리포트에서 클래스만 본다).
6. **관리자 테스트 발송** — `api/src/test/.../admin/AdminNotificationTestControllerTest.kt`(`@IntegrationTest`, `tokenOf(MemberRole.ADMIN)` 선례). 기기 등록된 회원 → 200 `{sent:1,failed:0}` + dispatch SENT / 기기 없음 → `{0,0}` / 비관리자 403. → `api.notification.PushNotificationService.send(request)`(prepare→send→record 글루), `AdminNotificationTestController/Api/Service/Request/Response`, `WebConfig` 는 `ADMIN/**` 이 이미 보호.
7. `./gradlew build` 전체 Green.

## 2. dev 실기기 검증 (DoD)

```bash
# 1) 앱에서 로그인 + 토큰 등록(KB-465) 완료된 회원 id 확인
mysql -h<dev> kbap -e "SELECT id, member_id, lang, token_invalid_at FROM notification_device ORDER BY updated_at DESC LIMIT 3"

# 2) 관리자 토큰으로 테스트 발송
curl -s -X POST -H "X-API-Version: 1.0" -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  -d '{"memberId": 35}' https://<dev-api>/api/admin/notifications/test-push | jq .payload   # {"sent":1,"failed":0}

# 3) 저장 확인
mysql -h<dev> kbap -e "SELECT id, dispatch_status, ticket_id, error FROM notification_dispatch ORDER BY id DESC LIMIT 3"
```

로컬은 `kbap.push.expo.base-url` 이 실 Expo 라 실기기 토큰만 있으면 그대로 발송된다. Enhanced push security 를 켜면 `EXPO_ACCESS_TOKEN` 을 SSM `/kbap/<env>/EXPO_ACCESS_TOKEN` + 태스크 정의 secrets 로 공급(코드는 비어 있으면 헤더 생략).

## 3. Jira 코멘트 (DoD "결정 기록")

구현 후 KB-468 에: "선호 토글 — HELPFUL·REVIEW_REMINDER=activity, MEAL_TIME=meal_time+광고성 동의(v≥2), SCAN_SUGGESTION=광고성 동의만, NOTICE=필터 없음 · 알림함 행 언어 = 회원의 유효 기기 중 마지막 갱신 기기 lang · 파이프라인은 prepare/send/record 3단(도메인→port 금지 규칙 유지) · FE data 계약 5종(MEAL_TIME 추가)".
