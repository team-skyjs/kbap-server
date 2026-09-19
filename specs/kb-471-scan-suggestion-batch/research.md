# Research: SCAN_SUGGESTION 배치

## 1. 잡 구조 — tasklet(대상 확정) + chunk step(회원 묶음 발송), Expo 청크는 공용 부품 안

- **Decision**: `scanSuggestionPushJob` = `scanSuggestionTargetStep`(tasklet, `ResourcelessTransactionManager` — 가드·후보 회원 id 확정·버퍼 적재) → `scanSuggestionSendStep`(chunk-oriented `chunk<Long, Long>(member-chunk-size, 기본 500)`, `ResourcelessTransactionManager`, reader = 잡 범위 버퍼에서 회원 id `poll`, writer = `pushHandler.send(PushRequest(SCAN_SUGGESTION, ids, ttlSeconds))` 한 줄 + 로그·카운터). Expo 청크(100)·동시성·페이싱·재시도는 공용 부품 안이라 배치 step 의 청크 단위는 **회원 묶음**이다(500명 ≈ 기기 최대 수백~1천 ≈ Expo 요청 5~10개).
- **Rationale**: 사용자 지시(스텝 조합·tasklet 일괄 조회·청크 제약·공용 발송 모듈). 회원 묶음 chunk 는 (1) `prepare`/`record` 트랜잭션과 메모리를 묶음 크기로 묶고, (2) `writeCount`(발송 요청한 회원 수) 가 `BATCH_STEP_EXECUTION`·`spring.batch.step` 메트릭에 남으며, (3) 발송 방식은 호출자가 모른다(FR-018). 두 step 모두 Resourceless 라 Expo 호출이 DB 트랜잭션 밖이고, `prepare`/`record` 는 자기 `@Transactional` 로 짧게 커밋한다 — 기존 outbox tasklet 과 같은 패턴.
- **Alternatives considered**: (a) tasklet 하나에서 전부 — "스텝 조합" 지시에 어긋나고 묶음 카운트가 메트릭에 안 남는다. (b) 배치 청크 = Expo 청크 100(초안) — 발송 방식이 호출자(배치)에 새어 나와 공용 부품 결정과 충돌. 기각. (c) reader 가 DB 의 PENDING dispatch 를 페이징 — 재시작 가능성은 좋지만 notification·dispatch 조인 로더가 추가로 필요. 잡이 수 초~수 분이라 재시작 요구가 없어 기각.

## 2. 후보 회원 수집과 슬롯당 1회 상한 — 리포지토리 쿼리 2개, 회원 단위 차집합

- **Decision**: `NotificationSettingJpaRepository.findMemberIdsByNewsTrue(): List<Long>`(JPQL `select distinct s.memberId from NotificationSetting s where s.news = true`) − `NotificationJpaRepository.findMemberIdsByTypeAndCreatedAtAfter(SCAN_SUGGESTION, startOfCurrentSlot): List<Long>`(JPQL distinct) = `memberIds` → `PushDispatchService.prepare(PushRequest(SCAN_SUGGESTION, memberIds, ttlSeconds))`. 기기 토글·동의 v≥2·유효 토큰·회원 연결 판정은 `PushTargetResolver` 그대로.
- **실패 건 처리 (2026-09-16)**: `PushDispatchService.record` 가 FAILED 판정한 dispatch 의 `notification` 을 `BaseEntity.delete()` 로 소프트 삭제한다(`findAllById` 한 번). 알림함 조회·슬롯 상한 쿼리는 `@SQLRestriction(status=ACTIVE)` 로 자동 제외되므로 추가 조인이 없다. 부수 효과: 전 기기가 실패한 회원은 같은 슬롯 재실행에서 다시 시도된다(일시 장애 복구에 유리, 영구 실패는 재시도해도 같은 결과 — DeviceNotRegistered 토큰 정리는 KB-473).
- **Rationale**: 전 회원 순회 없이 "소식 켜진 설정 행" 에서 출발한다(FR-003). 상한은 **회원 단위**로 뺀다 — 알림함 행이 기기 단위라 spec 은 기기 단위를 말하지만, 같은 실행에서 한 회원의 기기들은 함께 만들어지므로 회원 단위 제외는 기기 단위의 상위 집합이고("어느 기기도 하루 2번 받지 않는다" 만족) 쿼리 하나로 끝난다. 유일한 차이는 "오늘 이미 받은 회원이 오늘 새 기기를 켠 경우 그 기기는 내일부터" — 감수. 회원 ACTIVE 조인은 두지 않는다: 탈퇴는 기기 연결 해제·설정 소프트 삭제·동의 닫기(#260·#261)로 이미 대상에서 빠지고, `SUSPENDED` 는 기기·설정이 남아 대상이 될 수 있으나 정지 회원에게 광고 1건이 가는 것은 비치명(필요해지면 `memberRepository` 로 한 줄 필터).
- **슬롯 시작 계산 (2026-09-15 개정 — 12:00·18:00 두 번 발송)**: 상한을 "오늘" 이 아니라 **현재 슬롯**(12:00~18:00, 18:00~다음 날 12:00 KST) 기준으로 잰다 — 하루 1회로 두면 18:00 발송이 12:00 수신자를 전부 제외한다. `ScanSuggestionSendWindow.startOfCurrentSlot(clock)`: 현재 KST 시각 이하의 마지막 슬롯 시작(없으면 전날 18:00). `createdAt` 은 `@CreationTimestamp`(JVM 기본 시간대) 라 KST 슬롯 시작을 `withZoneSameInstant(ZoneId.systemDefault())` 로 바꿔 비교한다. 테스트에서는 실제 시각으로 찍히는 `created_at` 을 가짜 시계로 덮어 슬롯 경계를 검증한다.
- **Alternatives considered**: `PushRequest` 에 제외 기기 목록/술어를 추가해 파이프라인이 기기 단위로 거르기 — 공용 서비스 시그니처 확장. 지금은 회원 단위 차집합이 더 짧다.

## 3. 스텝 간 데이터 전달 — `@JobScope` 버퍼

- **Decision**: `@Component @JobScope class ScanSuggestionCandidateDto`(`ArrayDeque<Long>`; `load(memberIds)`·`poll()`). tasklet 이 채우고, reader 빈은 `ItemReader { candidates.poll() }` 한 줄.
- **Rationale**: Job `ExecutionContext` 는 JobRepository(JDBC) 에 직렬화돼 저장된다 — 회원 id 수천 개를 `BATCH_JOB_EXECUTION_CONTEXT` 에 남길 이유가 없고 직렬화 포맷(Batch 6 기본 직렬화기) 의존이 생긴다. `DefaultBatchConfiguration`(`BatchJdbcJobRepositoryConfig` 가 상속)이 `jobScope`/`stepScope` 를 등록하므로 추가 설정 없이 `@JobScope` 를 쓸 수 있다. 한 잡 실행 = 한 버퍼라 동시 실행 이슈 없음(launcher 가 AlreadyRunning 으로 막고, 배치는 1대다). kotlin-spring 플러그인이 `@Component` 클래스를 open 으로 만들어 CGLIB 스코프 프록시가 된다.
- **Alternatives considered**: 싱글턴 홀더 — 전역 가변 상태. `@StepScope` `ListItemReader` — 프록시 하나 더. 둘 다 기각.

## 4. 발송 시간대 가드 — 없음 (2026-09-15 결정)

- **Decision**: 08~21 KST 가드와 NOOP flow 전이를 제거했다. 발송 시각은 `ScanSuggestionSendWindow.LUNCH_CRON/DINNER_CRON`(12:00·18:00 KST) 이 정하고, 수동 HTTP 트리거는 시각과 무관하게 보낸다. 잡은 `start(target).next(send)` 단순 SimpleJob.
- **Rationale**: 사용자 결정 — cron 이 주간 두 시각으로 고정돼 조건절이 중복이다. 야간 광고 동의 문제는 cron 을 야간으로 옮기지 않는 것으로 지킨다.
- **Clock**: `startOfCurrentSlot` 이 여전히 `Clock` 을 쓴다. 테스트는 `@Primary MutableClock`.

## 5. Expo 제약 — 청크 100·초당 600·일시 실패 재시도(어댑터)

- **Decision (동시성)**: `ExpoPushSender` 가 **고정 스레드 풀**(`Executors.newFixedThreadPool(concurrency)`, 스레드 이름 `expo-push-N`, 기본 `kbap.push.expo.concurrency=6`) 을 소유한다(`AutoCloseable` — 빈 `destroyMethod = "close"`). `send(messages)` 는 100건 청크마다 페이서 슬롯을 얻은 뒤 풀에 제출하고, `Future` 를 **제출 순서대로** join 해 티켓을 이어 붙인다(입력 순서 = 출력 순서 불변식 유지). 재시도 대기는 풀 스레드에서 일어난다.
- **Decision (페이싱)**: 인스턴스 단위 페이서 — `synchronized` 로 `nextSlotAt = max(now, nextSlotAt) + minRequestInterval`(기본 `kbap.push.expo.min-request-interval=170ms`) 를 잡고 그 시각까지 호출 스레드가 잔다. 청크 요청 **시작** 이 초당 최대 ≈5.9 개 → 100건 × 5.9 ≈ 590건/s < 600. api 스레드 여러 개가 동시에 `send` 해도 같은 페이서를 지난다.
- **Rationale (동시성)**: 공식 문서: Node SDK 는 "최대 6개 동시 연결" 을 열고 자동 throttle 한다 — 우리도 같은 수치. 처리량 = min(600/s, C × 100 / L). Expo 지연 L=300ms 면 순차 333/s, C≥2 에서 상한 도달; L=1s 여도 C=6 이면 600/s. 즉 **동시성은 지연을 겹쳐 상한까지 끌어올리는 수단**이고 상한 자체는 Expo 가 정한다(10만 기기 = 최소 167초). 식사시간 알림처럼 시각이 중요한 발송은 이 상한이 곧 지연 하한이므로, 그 이상은 Expo 상향 요청 또는 대상 분할(범위 밖). 배치 writer 에서 병렬화하지 않는 이유: 발송 방식은 공용 부품 소유(FR-018), api 호출자도 같은 이득을 받는다.
- **인스턴스 간 합산**: 페이서는 JVM 단위라 api 2대 + batch 1대가 동시에 대량 발송하면 이론상 1,800/s. api 발송은 이벤트 건별(수 건)이고 대량은 배치뿐이라 감수. 배치가 둘 이상 생기면 그때 분산 페이서를 검토.
- **Decision (재시도)**: `ExpoPushSender.sendChunk` 의 HTTP 호출을 Spring Framework 7 코어 `RetryTemplate(RetryPolicy)` 로 감싼다. 정책: `maxRetries=3`·`delay=1s`·`multiplier=2.0`(1s→2s→4s, jitter 없음)·`predicate` = `ResourceAccessException`(네트워크·타임아웃) ∪ `HttpServerErrorException`(5xx) ∪ `HttpClientErrorException.TooManyRequests`(429). 그 밖의 `HttpClientErrorException`(400·401·403…)·`HttpMessageConversionException` 은 predicate 밖이라 즉시 `failAll`. 재시도 소진 시 `RetryException` 의 cause(마지막 오류)로 `failAll`. 200 응답 안의 건별 error 티켓은 재시도 대상이 아니다(어댑터는 티켓을 값으로 돌려줄 뿐). 값은 `kbap.push.expo.retry.{max-retries,initial-delay,multiplier}` 로 api·batch yml 양쪽에서 외부화하고 `ExpoPushSender.create(baseUrl, accessToken, retryPolicy)` 로 조립 config 가 넘긴다.
- **Rationale**: 사용자 3차 입력 + 공식 문서 "Retry on failure": 네트워크·429·5xx 는 일시적이라 지수 백오프 재시도, 400·자격 증명 오류는 영구라 재시도 무의미. KB-468 이 "필요해지면 어댑터 안에서만" 으로 자리를 비워 뒀고, 어댑터 밖(도메인 3단·writer)은 건드리지 않는다 — port 계약(예외 대신 값) 불변. `RetryPolicy.builder()` 는 벡터 잡이 이미 쓰는 Framework 7 API 라 의존 추가 없음. 하루 1회 상한 때문에 FAILED 건은 같은 날 재발송이 없으므로 청크 단위 재시도가 유실을 막는 유일한 수단이다.
- **비용**: 최악 청크당 재시도 대기 7s + 읽기 타임아웃 10s×4 ≈ 47s. 배치는 감수. 관리자 테스트 발송(api, 동기) 도 같은 어댑터라 Expo 장애 시 요청이 최대 그만큼 길어진다 — 관리 도구라 감수, 필요하면 api yml 에서 `max-retries` 를 낮춘다(키는 공유).
- **Alternatives considered**: (a) writer(배치) 에서 재시도·병렬 — api 호출자는 못 받고, 발송 방식이 호출자로 샌다. 기각. (b) Spring Batch `faultTolerant().retry()`·멀티스레드 step — 청크 트랜잭션·ItemWriter 재실행 의미론이 얽히고 api 는 못 쓴다. 기각. (c) `Retry-After` 헤더 존중 — Expo 문서에 명시 없음, 고정 백오프로 충분. (d) 토큰 버킷/`RateLimiter`(Resilience4j·Guava) — 의존 추가; 요청 시작 시각 고정 간격 페이서(8줄)가 같은 효과. (e) Spring `ThreadPoolTaskExecutor` 빈 주입 — 풀은 어댑터 내부 자원이라 바깥에 노출할 이유가 없고 종료 책임이 갈라진다. (f) gzip 요청 본문 — 문서가 권하지만 100건 × 수백 B 라 이득 미미, 범위 밖. 청크 크기를 프로퍼티화 — Expo 상한이 고정이라 상수.

## 6. 공용 파이프라인 확장 — 봉투에 channelId·ttl

- **Decision (문구 슬롯, 2026-09-16)**: `common.domain.notification.model.MealSlot { LUNCH(12:00), DINNER(18:00) }` 를 두고 `PushRequest.mealSlot: MealSlot? = null` → `PushMessageRenderer.render(type, lang, args, slot)` 가 `PushTemplates.bySlot[type][slot][lang]` 을 먼저 찾고 없으면 `byType` 으로 떨어진다. 스캔 제안 점심·저녁 문구 10 로케일 × 2 는 `PushTemplates.bySlot` 한 곳 — 교체는 그 맵의 문자열 수정으로 끝난다. FE `data.type` 은 `SCAN_SUGGESTION` 그대로(유형을 쪼개면 FE 알림함 KEYS 맵에 없어 기록이 드롭된다). `MealSlot` 은 배치의 슬롯 상한(`startOfCurrentSlot`) 축이기도 하고 앞으로 MEAL_TIME 점심/저녁에도 그대로 쓴다. 수신거부 안내는 실제 앱 경로 "프로필 > 알림 설정" 으로 10 로케일 교체.
- **Decision**: `NotificationType.channelId` = `if (marketing) "news" else "default"`. `PushEnvelope(to,title,body,data, channelId, ttlSeconds: Int?)`, `PushRequest.ttlSeconds: Int? = null`, `PushMessage(to,title,body,data, channelId, ttlSeconds)`, `ExpoMessage.channelId` 는 메시지 값·`ttl: Int?` 은 `@JsonInclude(NON_NULL)`. api `PushNotificationService`·batch writer 의 `PushEnvelope → PushMessage` 매핑 한 줄에 두 필드 추가. 배치는 `PushRequest(..., ttlSeconds = kbap.batch.scan-suggestion.ttl(기본 3h).seconds)`.
- **Rationale**: Jira 권고(유형→채널 매핑은 파이프라인 한 곳, 트리거는 type 만). 도메인은 port 를 모르므로 값은 봉투에 싣고 소비자가 매핑(KB-468 §1 구조 유지). 비광고성은 `default` 유지(FE 채널명 미확정, spec Assumptions). ttl 은 스캔 제안이 점심 맥락이라 3h — 다른 유형은 null(Expo 기본 4주).
- **Alternatives considered**: 어댑터가 `data.type` 을 읽어 채널 결정 — 어댑터가 도메인 enum 을 알게 되고 매핑이 두 곳(도메인 marketing + 어댑터 문자열)으로 갈라진다. 기각.

## 7. 스케줄 — ShedLock 없음

- **Decision (2026-09-16 재개정 — 잡 2개)**: `BatchJobScheduler` 에 `pushLunchScanSuggestions()`(`LUNCH_CRON = "0 0 12 * * *"`) 와 `pushDinnerScanSuggestions()`(`DINNER_CRON = "0 0 18 * * *"`) 두 메서드가 각각 `scanSuggestionLunchPushJob`·`scanSuggestionDinnerPushJob` 을 띄운다. 두 잡은 `scanSuggestionTargetStep`·reader 빈을 공유하고 send step 만 `MealSlot` 을 박은 writer 로 다르다(`ScanSuggestionPushBatchConfig.job(slot)` 팩토리, 잡 이름은 `jobNameOf(slot)`). 잡을 둘로 나눈 이유: 점심·저녁 문구가 다르고, 잡 파라미터로 슬롯을 넘기려면 launcher·트리거 API 를 바꿔야 하는데 잡 이름으로 구분하면 기존 HTTP 트리거(`?jobName=`) 가 그대로 쓰인다. 환경변수·yml 키 없음. 분산 락(ShedLock) 은 넣지 않는다.
- **Rationale**: 사용자 결정(2026-09-15): 배치 앱은 1대만 돌아 스케줄러 동시성 문제가 없다. 같은 인스턴스 안의 중복은 `BatchJobLauncher` 의 AlreadyRunning 가드가 막는다. Jira DoD 의 "ShedLock 스케줄" 문구는 이 결정으로 대체된다(Jira 코멘트로 남긴다). 다중 인스턴스로 늘리는 날 기존 outbox·vector 스케줄과 함께 한 번에 도입한다.
- **Alternatives considered**: ShedLock 도입(api 선례·락 테이블 존재) — 의존 2개·config 추가·락 파라미터 튜닝이 1대 환경에서 얻는 게 없다. 기각.

## 8. 공용 발송 부품 — `PushHandler`(port) + `ExpoPushHandler`(infra), 제네릭 미채택

- **Decision**: `common.port.push.PushHandler { fun send(request: PushRequest): PushDispatchResult }` 를 신설하고 `common.infra.push.ExpoPushHandler(dispatchService: PushDispatchService, sender: PushSender)` 가 구현한다 — 몸체는 구 api `PushNotificationService` 의 3줄(prepare → `PushEnvelope→PushMessage` 매핑 → send → record). 조립은 api·batch `PushConfig`(`ExpoPushSender` 빈 + `ExpoPushHandler` 빈). 호출자는 `PushRequest(type, memberIds, args, data, ttlSeconds)` 만 만든다: 관리자 테스트 발송(NEWS)·스캔 제안 배치(SCAN_SUGGESTION)·앞으로의 식사시간 잡(MEAL_TIME)·리뷰 리마인더/도움돼요(REVIEW_REMINDER/HELPFUL). api `PushNotificationService` 는 삭제한다.
- **Rationale**: 사용자 지시("common 에 Expo 푸시 구현체, 유형을 매개변수로"). KB-468 research §1 은 호출자가 하나뿐일 때 3줄 글루 중복을 택하며 "(b) 오케스트레이터를 infra 에" 를 ADR-0018 정의 위반으로 기각했다. 호출자가 3+개가 되면 글루 중복이 세 곳으로 늘고, 이번에 청크·동시성·페이싱·재시도가 들어오면서 "발송을 아는 코드" 의 무게가 커졌다 — `common.infra.push` 를 "Expo 어댑터" 가 아니라 **"Expo 발송 모듈(오케스트레이션 + 어댑터)"** 로 읽으면 ADR-0018 의 "외부 시스템 구현은 infra" 취지와 맞는다. ArchUnit: port → 도메인 값 타입 허용, infra → 도메인 금지 규칙 없음(`infra.llm` 선례), 호출자 → infra 금지는 그대로(호출자는 port 만). 도메인 → port 금지도 그대로(3단 분리 유지). 테스트 페이크는 `PushSender` 수준이라 api·batch 통합 테스트에서 `ExpoPushHandler` 실체가 돈다.
- **제네릭 검토 → 미채택**: 세 전송이 다른 것은 `type`(템플릿·채널·광고 표기의 키)·`args`(문구 치환)·`data`(FE 딥링크) 값뿐이고 절차는 동일하다. `PushHandler<T : PushPayload>` 로 만들면 (1) 유형마다 페이로드 클래스, (2) `T → NotificationType` 매핑 또는 유형별 구현/등록소, (3) 조립 시 유형별 빈이 필요해지는데 런타임 동작은 한 줄도 달라지지 않는다 — 타입 매개변수가 막아 주는 오류가 없다(현재 `args` 누락은 빈 문자열 치환으로 조용히 통과하지만 이는 제네릭이 아니라 sealed 요청 타입으로만 잡힌다). 유형별 인자의 컴파일타임 검증이 필요해지면 `sealed interface PushTrigger { ScanSuggestion; MealTime; Helpful(foodId) }` + `toRequest()` 를 port 앞에 얹는 편이 제네릭보다 짧다 — 지금은 두지 않는다.
- **Alternatives considered**: (a) 글루를 `common.domain.notification` 에 — 도메인 → port 금지(ArchUnit) 위반. (b) 새 최상위 `common.notification` — KB-468 과 같은 이유(ArchUnit 회색 지대) 로 기각. (c) api·batch 각자 글루 유지(현행) — 세 번째 호출자부터 중복. 기각.

## 9. 로그·메트릭

- **Decision**: tasklet 로그 `스캔 제안 대상 확정 candidates={} excludedThisSlot={} targets={}`, writer 는 회원 묶음마다 `스캔 제안 발송 members={} sent={} failed={}` 와 `MeterRegistry` 카운터 `kbap.push.dispatch{type=SCAN_SUGGESTION,result=sent|failed}`. 잡/스텝 상태·소요·write 수(회원)는 기존 `spring.batch.*` 메트릭(actuator prometheus, KB-380). Expo 요청 수·재시도는 어댑터 로그(`Expo push 청크 발송 실패 …` 기존 + 재시도 warn).
- **Rationale**: FR-011. 카운터 2줄이면 Grafana 에서 일별 발송·실패를 볼 수 있다.

## 10. 테스트 픽스처 — 배치 컨텍스트 1개 유지

- **Decision**: `@BatchIntegrationTest` 의 `@Import` 에 `FakePushSenderConfig`(`@Primary PushSender` — 받은 메시지를 기록, `errorFor` 로 건별 error 티켓 주입; api `FakePushSender` 와 같은 모양) 와 `MutableClockConfig`(`@Primary Clock`) 를 추가. 페이크는 port `PushSender` 수준이므로 공용 `ExpoPushHandler` 는 실체가 돈다 — 청크 100 분할·동시성·페이싱은 어댑터 단위 테스트(`ExpoPushSenderTest`)가 맡고, 잡 통합 테스트는 "누가 대상인가·몇 건 SENT/FAILED 인가" 만 본다. `PushConfig` 의 `@ConditionalOnMissingBean` 은 사용자 config 간 순서가 보장되지 않아 의지하지 않고(api 선례 `FakePushSender` 도 `@Primary`) 두 빈이 공존하되 primary 가 주입된다. 잡 통합 테스트는 `BatchJobLauncher.launch` + 기존 `awaitStatus` 폴링 선례로 실행하고 리포지토리로 결과를 검증한다. 배치 테스트 스키마는 Hibernate `create` 라 `member` FK 가 없어 임의 memberId 로 시드한다(`PushDispatchServiceTest` 선례).
- **Rationale**: CLAUDE.md 테스트 헤더 고정 규칙(KB-392). 시각 의존 시나리오(슬롯당 1회)를 컨텍스트 재생성 없이 검증.
