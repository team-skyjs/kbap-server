# Quickstart: SCAN_SUGGESTION 배치

## 1. TDD 순서 (Red → Green 단위)

1. **시간대·오늘 계산 (순수)** — `batch/src/test/kotlin/com/kbap/batch/notification/ScanSuggestionSendWindowTest.kt`(BehaviorSpec). 07:59:59 닫힘·08:00:00 열림·20:59:59 열림·21:00:00 닫힘(KST 고정 Clock), UTC JVM 존에서 `startOfToday` 가 KST 자정을 올바른 LocalDateTime 으로 돌려주는지(`Clock.fixed` + `TimeZone` 가정 대신 `ZoneId.systemDefault()` 결과를 역산해 검증). Red → `ScanSuggestionSendWindow` → Green.
2. **공용 notifier** — `common/src/test/.../infra/push/ExpoPushNotifierTest.kt`(순수: 페이크 `PushDispatchService` 는 생성자 의존이 많으므로 `CommonTestApp` 컨텍스트 `@SpringBootTest` + `@Import(MySqlContainerConfig)` 로 실체 사용, `PushSender` 만 페이크). 시나리오: 회원 1·기기 2 → `send` 결과 `(2,0)`·dispatch SENT 2·페이크가 받은 `PushMessage` 에 channelId/ttl / 페이크가 error 티켓 → `(1,1)` / 대상 0 → `(0,0)` 이고 sender 미호출. Red → `common.port.push.PushNotifier`·`common.infra.push.ExpoPushNotifier` → Green. api: `PushConfig` 에 `ExpoPushNotifier` 빈, 관리자 테스트 발송 서비스가 `PushNotifier` 주입, `api.notification.PushNotificationService` 삭제 — `AdminNotificationTestControllerTest` 회귀 Green. (tasks Phase 2 와 같은 순서 — 이후 단계는 전부 notifier 위에서 돈다.)
3. **리포지토리 쿼리 2개** — `common/src/test/.../NotificationSettingJpaRepositoryTest.kt`·`NotificationJpaRepositoryTest.kt`(기존 `CommonTestApp` 컨텍스트) 에 시나리오 추가: news=true 행의 회원 id 만 distinct / 소프트 삭제 행 제외 / type·since 경계(같은 시각 포함·이전 제외·다른 type 제외). Red → JPQL 2개 → Green.
4. **파이프라인 봉투** — `PushDispatchServiceTest.kt` 에 "SCAN_SUGGESTION 봉투는 channelId=news, HELPFUL 은 default, ttlSeconds 는 요청값 그대로(미지정 null)". Red → `NotificationType.channelId`·`PushRequest.ttlSeconds`·`PushEnvelope` 필드·`prepare` 채움 → Green. `ExpoPushNotifier` 의 봉투→`PushMessage` 매핑에 ttl 전달.
5. **어댑터 통과** — `ExpoPushSenderTest.kt` 기존 "sound·priority·channelId 기본값" 시나리오를 "메시지의 channelId 를 그대로, ttl 은 지정 시만 직렬화" 로 교체(`jsonPath("$[0].channelId").value("news")`, `$[0].ttl` 10800 / 미지정이면 `doesNotExist`). Red → `PushMessage`·`ExpoMessage` → Green.
6. **어댑터 재시도** — 같은 `ExpoPushSenderTest.kt` 에 추가(테스트용 정책 `RetryPolicy.builder().maxRetries(3).delay(1ms).multiplier(2.0)` 을 `create(baseUrl, accessToken, builder, retryPolicy)` 로 주입 — 실제 대기 없이 횟수만 검증). 시나리오: (a) 5xx·5xx·200 → 요청 3회, 티켓 전부 ok / (b) 429 두 번 뒤 200 → 같음 / (c) 네트워크 오류(`withException(IOException)`)·200 → 2회 / (d) 5xx 4회 연속 → 요청 4회, 청크 전부 error(마지막 오류) 이고 예외 전파 없음 / (e) 400 → 요청 1회, 청크 전부 error / (f) 기존 "두 번째 청크 500 → 그 100건만 error" 는 재시도 소진(4회 500) 으로 갱신. 백오프 배수는 `RetryPolicy` 의 책임이라 시간 측정 대신 정책 값(1s·2.0)을 조립 config 테스트 없이 yml 기본값으로 둔다. Red → `sendChunk` 를 `RetryTemplate.execute` 로 감싸고 predicate 분류 → Green.
   **동시성·페이싱** — 같은 파일에 추가. `MockRestServiceServer` 는 스레드 안전이 아니므로 이 두 시나리오는 `RestClient.Builder` 대신 **페이크 `PushSender` 를 안 쓰고** `ExpoPushSender` 의 HTTP 를 `RestClient` 로 `MockRestServiceServer` 를 `ignoreExpectOrder(true)` 로 묶되 응답 크리에이터 안에서 `Thread.sleep(200)` 과 시작 시각 기록 — (a) 청크 12개·concurrency 6·interval 0 → 총 소요 < 12×200ms(순차보다 빠름)·시작 시각 겹침 관찰 / (b) 청크 12개·concurrency 6·interval 100ms → i번째 시작 시각 ≥ 첫 시작 + i×100ms / (c) 티켓 순서 = 입력 순서(각 청크 응답에 청크 번호를 실어 검증). `MockRestServiceServer` 동시성이 불안정하면 이 두 시나리오만 `HttpServer`(JDK `com.sun.net.httpserver`) 로 대체한다 — 구현 중 판단.
7. **잡 통합** — `ScanSuggestionPushJobTest.kt`(`@BatchIntegrationTest`; `@Import` 에 `FakePushSenderConfig`·`MutableClockConfig` 추가). `beforeSpec` 에서 알림 5테이블 정리, 시계 12:00 KST. 시나리오: (a) 회원 A 기기 2대 news on + 동의 v2, 회원 B news off, 회원 C 설정 없음, 회원 D 동의 v1, 회원 E 무효 토큰, 회원 미연결 기기 → 실행 후 notification 2·dispatch SENT 2·페이크 메시지 2(channelId news, ttl 10800, data.type/notificationId) / (b) 같은 시계로 재실행 → 추가 0 / (c) 시계 21:30 → exitCode NOOP·행 0 / (d) 회원 1,200명(기기 1대씩) + `member-chunk-size` 기본 500 → step writeCount 1200·페이크 메시지 1200·SENT 1200, 페이크 `errorFor` 로 특정 토큰 실패 주입 → 그 건만 FAILED·잡 COMPLETED. (청크 100 분할·동시성·페이싱·재시도는 어댑터 단위 테스트가 맡는다 — 페이크 sender 는 받은 메시지 전체를 티켓으로 돌려줄 뿐.) Red → `ScanSuggestionPushBatchConfig`·tasklet·`ScanSuggestionCandidateBuffer`·`ScanSuggestionPushWriter`·yml 키 → Green.
8. **스케줄** — `BatchJobScheduler` 에 `@Scheduled(cron 외부화)` 메서드 1개. 테스트는 스케줄러 off 라 컨텍스트 로드만 확인(`FoodContentOutboxBatchConfigTest` 류에 `scanSuggestionPushJob` 빈 존재 한 줄).
9. **조립** — api·batch `PushConfig` 가 `kbap.push.expo.{concurrency,min-request-interval,retry.*}` 를 읽어 `ExpoPushSender.create(...)`(`destroyMethod = "close"`) 와 `ExpoPushNotifier` 빈을 만든다. yml 두 곳에 기본값.
10. `./gradlew build` 전체 Green(arch 포함). `BatchJobTriggerControllerTest` 의 404 메시지 assertion 이 잡 목록을 `containsString` 으로 보므로 영향 없음.

## 2. 로컬 실행 검증

```bash
# 메인 체크아웃 .env 를 읽어 로컬 MySQL(api Flyway 적용된 스키마)에 붙인다 — 워크트리엔 .env 없음
set -a; source ../../../.env; set +a
SPRING_PROFILES_ACTIVE=local ./gradlew :batch:bootRun --no-daemon \
  --args='--server.port=8081 --kbap.batch.scheduler.enabled=false'

# 대상 준비: 앱(dev 빌드)에서 로그인·토큰 등록·소식 토글 on·마케팅 동의 on 한 회원이 있어야 한다
# (또는 notification_device / notification_setting(news=1) / notification_consent(2종, v2) 를 직접 INSERT)
curl -s -X POST 'http://localhost:8081/internal/batch/jobs?jobName=scanSuggestionLunchPushJob' | jq .
curl -s 'http://localhost:8081/internal/batch/executions/<id>' | jq '{status, exitCode}'
mysql -e "SELECT id, dispatch_status, ticket_id, error FROM notification_dispatch ORDER BY id DESC LIMIT 5" kbap

# 시간대 가드 확인 — 21:00 이후 로컬에서 트리거하면 exitCode NOOP, 행 0
```

`kbap.push.expo.base-url` 이 실 Expo 라 실기기 토큰이면 그대로 발송된다(Android 는 `news` 채널 헤드업 확인 — FE KB-498 빌드 필요).

## 3. 배포 순서 주의

- 배포 직후 기기 설정 행이 없으면 후보 0 → COMPLETED 0건(정상). **FE 기기 단위 설정 릴리스(KB-497)가 나간 뒤** 스케줄이 실제 대상을 갖는다.
- 스케줄은 12:00·18:00 KST 코드 상수(환경변수 없음). 끄려면 `kbap.batch.scheduler.enabled=false`(전체 스케줄 off — 개별 off 스위치는 두지 않았다).
