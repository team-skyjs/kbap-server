# Research: Expo Push 발송 공용 파이프라인

입력은 Jira [KB-468](https://simhani1.atlassian.net/browse/KB-468)(spec.md 없음 — `/speckit-specify` 미실행, 본 플랜이 Jira 본문을 명세로 삼는다).

## 1. 파이프라인 위치 — 도메인은 port 를 모른다(ArchUnit) → prepare / send / record 3단 분리

- **Decision**: 파이프라인을 **세 조각**으로 나눈다.
  1. `common.domain.notification.PushDispatchService`(공유 도메인 서비스, `@Service`·`@Transactional`) — `prepare(...)`: 대상 필터 → 언어별 렌더 → `notification`·`notification_dispatch(PENDING)` 저장 → 발송할 `PushMessage` 목록(dispatch id 매핑 포함)을 반환. `record(prepared, results)`: 결과를 dispatch 에 반영(SENT/FAILED + 실패 사유). 기기 토큰은 건드리지 않는다.
  2. `common.port.push.PushSender`(seam) + `common.infra.push.ExpoPushSender`(어댑터) — 순수 HTTP: 100건 청크로 Expo 호출, 입력 순서와 같은 `PushTicket` 목록 반환. DB 를 모른다.
  3. 소비자 글루 3줄 — `prepared = dispatch.prepare(...)` → `tickets = sender.send(prepared.messages.map { PushMessage(...) })` → `dispatch.record(prepared, tickets.map { PushOutcome(...) })`. 도메인은 port 타입도 참조할 수 없으므로 봉투(`PushEnvelope`)·결과(`PushOutcome`)는 도메인 자체 값 타입이고 글루가 한 줄씩 매핑한다. api 는 `api.notification.PushNotificationService` 한 곳에 두고(트리거들이 공유), batch 는 잡 tasklet 안에서 같은 3줄을 쓴다(트리거 태스크 범위).
- **Rationale**: `ModuleBoundaryTest` 가 **`common.domain..` → `common.port..` 의존을 금지**한다("포트가 도메인 타입을 반환하는 방향만 허용", KB-244 이후 일관). Jira 가 말한 "렌더러·대상 필터 = 공유 도메인 서비스" 는 `common.domain.notification` 에 그대로 두되, port 를 호출하는 오케스트레이션은 도메인 밖에 있어야 한다. 3단 분리는 헌법 Additional Constraints 가 지시하는 **"pending 저장 → 외부 호출 → 결과 저장" 패턴 그 자체**이고, 외부 호출을 트랜잭션 밖에 두는 규약(2026-07-14)도 자연히 충족한다. 글루는 3줄이라 중복 비용이 규칙 완화 비용보다 작다.
- **Alternatives considered**: (a) ArchUnit 에 `common.domain.notification → common.port.push` 예외 허용 — 헌법 수준 결정을 기능 티켓에서 바꾸는 셈이라 기각. (b) 오케스트레이터를 `common.infra.push` 에 두고 port 로 노출 — 어댑터 패키지에 유스케이스가 들어가 ADR-0018 의 "어댑터 = 외부 시스템 구현" 정의를 깨므로 기각. (c) 새 최상위 패키지 `common.notification` — ArchUnit 이 감시하지 않는 회색 지대가 생겨 기각.

## 2. PushSender 계약과 Expo 어댑터

- **Decision**:
  ```kotlin
  data class PushMessage(val to: String, val title: String, val body: String, val data: Map<String, Any>)
  data class PushTicket(val ok: Boolean, val id: String? = null, val error: String? = null)
  fun interface PushSender { fun send(messages: List<PushMessage>): List<PushTicket> }
  ```
  `ExpoPushSender` 는 `RestClient` 로 `POST {base-url}/--/api/v2/push/send` 를 **100건씩 청크** 호출한다. 본문 각 항목은 `{to,title,body,data,sound:"default",priority:"high",channelId:"default"}`(ttl 미지정 — Expo 기본). 응답 `data[i]` 를 `{status:"ok",id}` → `PushTicket(ok=true,id)`, `{status:"error",message,details.error}` → `PushTicket(ok=false, error = details.error ?: message)` 로 순서대로 매핑한다. 청크 단위 HTTP/파싱 실패(`RestClientException`·`HttpMessageConversionException`)는 **던지지 않고** 그 청크 전부를 `PushTicket(ok=false, error=예외 클래스명+메시지 255자 절단)` 로 채운다. 반환 크기 = 입력 크기 불변식. `Authorization: Bearer <token>` 은 `kbap.push.expo.access-token` 이 비어 있지 않을 때만 붙인다. 타임아웃 connect 2s / read 10s. 재시도 없음.
  생성은 `FrankfurterExchangeRateClient` 선례대로 `companion.create(baseUrl, accessToken)` + `internal create(baseUrl, accessToken, RestClient.Builder)`(테스트가 `MockRestServiceServer.bindTo(builder)` 로 잡는다).
- **Rationale**: Jira 계약(배열 최대 100·순서 동일·티켓 스키마). 결과를 예외가 아니라 값으로 돌려야 record 단계가 dispatch 를 건별로 FAILED 처리할 수 있고, 좋아요 응답을 깨지 않는다는 트리거 요구도 지킨다. 재시도·초당 600 제한은 현재 볼륨(회원 수백)에서 필요 없다 — 필요해지면 어댑터 안에서만 바꾼다.
- **Alternatives considered**: expo-server-sdk-java 도입 — 의존성 하나에 100줄짜리 HTTP 를 감싸는 것이라 기각(RestClient 직접 호출은 Jira 결정). `HttpServiceProxyFactory` 인터페이스 — 단일 POST 라 `RestClient` 직접 호출이 더 짧다.

## 3. 어댑터 조립 — api·batch 각자 config, batch 는 도메인 서비스를 `@Import`

- **Decision**: `common` 에 `libs.spring.web` 을 `implementation` 추가(RestClient — spring-ai 전이 의존에 기대지 않는다). `api.core.config.PushConfig` 와 `batch.config.PushConfig` 가 각각 `@Bean fun pushSender(@Value("\${kbap.push.expo.base-url}") ..., @Value("\${kbap.push.expo.access-token:}") ...) : PushSender = ExpoPushSender.create(...)`(`@ConditionalOnMissingBean` — 테스트 페이크 교체용). batch `PushConfig` 는 `@Import(PushDispatchService::class, PushTargetResolver::class, PushMessageRenderer::class)` 로 도메인 서비스 3개를 올린다(`scanBasePackages` 는 그대로).
- **Rationale**: ADR-0018 "조립은 소비 앱 config". batch 스캔 범위를 넓히지 않고 필요한 빈만 명시 등록하는 것이 CLAUDE.md 의 "배치는 도메인 서비스 그래프를 올리지 않는다" 취지에 가깝다. 트랜잭션은 batch 에도 data-jpa 자동구성이 `PlatformTransactionManager` 를 올리므로 도메인 서비스의 `@Transactional` 이 그대로 동작한다.
- **yml**: api·batch `application.yml` 에 `kbap.push.expo.base-url: https://exp.host`, `access-token: ${EXPO_ACCESS_TOKEN:}`. SSM `/kbap/<env>/EXPO_ACCESS_TOKEN` + 태스크 정의 secrets 는 Enhanced push security 를 켤 때 배포 쪽에서 붙인다(코드는 비어 있으면 헤더 생략이라 선반영 무해).

## 4. 대상 필터 규칙 — 선호 토글 매핑(Jira "착수 전 확정" ①)

- **Decision**(기본안 채택): `PushTargetResolver.resolve(memberIds, type): List<NotificationDevice>`.

  | type | 선호 토글 | 광고성 동의 | 비고 |
  |------|-----------|-------------|------|
  | HELPFUL | `setting.activity` | 불필요 | 정보성 |
  | REVIEW_REMINDER | `setting.activity` | 불필요 | 정보성 |
  | MEAL_TIME | `setting.mealTime` | **필요** | KB-466 에서 mealTime 은 소식 동의 하위 토글 |
  | SCAN_SUGGESTION | 없음 | **필요** | 광고성 |
  | NOTICE | 없음 | 불필요 | 운영 공지(광고성 공지는 §6) |

  광고성 동의 = `MARKETING_PRIVACY`·`MARKETING_RECEIVE` **둘 다** 열린 행이 있고, 각 행의 `consent_version >= 2`(구 문구 1 제외). 판정 함수 `NotificationConsents.isMarketingEnabled(open, requiredVersion)` 를 `common.domain.notification.model` 에 두고 api `NotificationConsentService.isMarketingEnabled` 는 이를 위임하도록 바꾼다(요구 버전은 설정 화면에선 0 — 기존 동작 유지). 기기는 `member_id in (...) and token_invalid_at is null` 만. 설정 행이 없는 회원은 토글 기본값 false 라 제외.
- **Rationale**: Jira 기본안. 동의 판정 로직을 common 으로 올려야 batch·resolver 가 같은 규칙을 쓴다(중복 정의 금지).
- **Alternatives considered**: MEAL_TIME 을 정보성으로(동의 불필요) — KB-466 UI 가 소식 동의 아래에 두므로 기각.

## 5. 언어 — 기기 lang 으로 렌더, 알림함 행은 "가장 최근 갱신 기기" 언어(Jira "착수 전 확정" ②)

- **Decision**: 푸시 본문은 **기기별** `NotificationDevice.lang` → `LanguageCode.from`(미지원 → en). `notification` 행(알림함)은 회원당 1건이며 언어는 **그 회원의 유효 기기 중 `updatedAt` 이 가장 최근인 기기의 lang** 으로 렌더한다. 대상 기기가 0대인 회원은 `notification` 행도 만들지 않는다(발송 안 한 알림을 알림함에 남기지 않는다).
- **Rationale**: 회원 프로필에 언어가 없다(`country_code` 뿐). 기기 1대 사용자(대다수)에게 푸시와 알림함이 항상 일치하고, 다기기 사용자는 마지막에 쓴 기기 언어가 가장 그럴듯하다.
- **Alternatives considered**: `country_code` → 언어 매핑 — 국가≠언어(미국 거주 베트남인)라 기각. 알림함 행을 기기별로 저장 — 알림함에 같은 알림이 n번 보이므로 기각.

## 6. 렌더러 — 코드 내 템플릿 표, `{key}` 치환, 광고성 자동 부착

- **Decision**: `PushMessageRenderer.render(type, lang, args: Map<String,String>, marketing: Boolean): PushContent(title, body)`. 템플릿은 `PushTemplates` Kotlin `object` 의 `Map<NotificationType, Map<LanguageCode, PushContent>>`(5종 × 10로케일 = 50항목). 치환은 `{food}` 같은 `{key}` 를 `args` 로 단순 치환. **NOTICE 템플릿은 `{title}`/`{body}` 그대로**(운영자 문구 통과) — 파리티 테스트가 전 타입 × 전 로케일에 빈 값이 없음을 강제한다. `marketing = true` 면 제목 앞 `(광고) ` 고정 접두 + 본문 끝 로케일별 수신거부 안내 문장 부착(광고성 문구도 10로케일 표). `marketing` 기본값은 `type == SCAN_SUGGESTION`; NOTICE 광고성 공지는 호출자가 `marketing = true` 로 넘긴다.
- **Rationale**: 리소스 번들·i18n 프레임워크는 50개 문자열에 과하다. Kotlin 표는 타입으로 키가 잠기고 파리티 테스트가 한 줄이다. `(광고)` 는 국내 법정 표기라 언어와 무관하게 고정.
- **Alternatives considered**: `MessageSource`/properties — 키 오타가 런타임에 드러나 기각. DB 템플릿 — 운영 편집 요구 없음(YAGNI).

## 7. 저장 형태 — notification 1행 + dispatch n행, data 스키마

- **Decision**: `prepare` 는 회원별 `Notification.forMember(memberId, type, title, body, data)` 1행 저장 후 `data` 에 `notificationId`(number) 를 넣어 갱신하고, 유효 기기마다 `NotificationDispatch.pending(notificationId, deviceId, expoToken)` 를 저장한다. 푸시 `data` = `{type: <enum name>, foodId?: string, notificationId: number}`(FE 고정 계약, 4KB 한참 아래). 렌더 args 와 data 는 입력 `PushRequest(type, memberIds, args, data, marketing)` 로 받는다.
- **record**: 결과 `ok` → `markSent(id)`; 아니면 `markFailed(error)` — 실패 사유(`DeviceNotRegistered`·네트워크 오류 등)를 `error` 컬럼에 남기는 것까지만 한다. **기기 토큰은 무효화하지 않고 재전송도 하지 않는다**(2026-09-11 결정, Codex 리뷰 #259 반영): 실패 원인이 토큰인지 네트워크인지 티켓만으로 확정할 수 없고, prepare~record 사이에 앱이 토큰을 재등록했을 수 있으며(그 갱신은 DB 에 반영된다고 낙관), 우리 손을 벗어난 사유의 실패는 감수한다. 토큰 무효화는 KB-473 영수증 정리가 맡는다. 발송 결과 반환 `PushDispatchResult(sent, failed)` 로 로그·잡 요약에 쓴다.
- **Rationale**: KB-464 스키마 결정(dispatch 는 토큰 스냅샷 + device id 참조). 상태 전이는 엔티티가 보장.

## 8. dev 실기기 검증 경로 — 관리자 테스트 발송 엔드포인트

- **Decision**: `POST /api/admin/notifications/test-push` `{ memberId }` → NOTICE 로 `PushNotificationService.send(PushRequest(NOTICE, [memberId], args={title,body 고정 테스트 문구}))` 호출, 응답 `{ sent, failed }`. `api.admin.AdminNotificationTestController` + `AdminNotificationTestService`(관리자 서비스 분리 원칙). 관리자 인터셉터가 `ApiPaths.ADMIN/**` 를 이미 보호한다.
- **Rationale**: DoD "dev 실기기 1대 NOTICE 발송 성공" 에 트리거 태스크(좋아요·식사·리뷰요청) 없이 쓸 수 있는 진입점이 필요하다. 향후 운영 공지 발송의 씨앗이기도 하다. NOTICE 는 필터가 없어 토글·동의 없이도 발송된다.
- **Alternatives considered**: 배치 `/internal/batch/jobs` 트리거 — 테스트 발송은 Spring Batch 잡이 아니라 기각. 통합 테스트만으로 대체 — 실기기 DoD 를 못 채운다.

## 9. MEAL_TIME enum 추가 + FE 계약

- **Decision**: `NotificationType.MEAL_TIME` 추가(컬럼은 VARCHAR(30) 라 마이그레이션 없음). FE 계약(`data.type` 5종)은 `contracts/push-data-contract.md` 에 적고 구현 후 KB-468 코멘트로 FE 에 공유한다.

## 10. 테스트 전략

| 대상 | 방식 |
|------|------|
| `ExpoPushSender` | common 단위 테스트, `MockRestServiceServer` — 250건 → 3청크 요청 수·순서·ok/error 매핑·청크 HTTP 500 → 그 청크 전부 실패·Bearer 유무 |
| `PushMessageRenderer` | common 순수 단위 — 파리티(5×10 비어있지 않음), `{key}` 치환, 광고성 접두/안내 부착·정보성 미부착 |
| `PushTargetResolver` | common `@SpringBootTest`(`CommonTestApp` 이 `common.domain` 을 스캔하므로 `@Service` 가 올라온다) — 토글·동의 버전·무효 토큰 시나리오 |
| `PushDispatchService` | common `@SpringBootTest` — prepare 가 notification 1행·dispatch n행 PENDING·언어 선택, record 가 SENT/FAILED + 사유 기록(기기 토큰 불변) |
| 조립 | `ModuleBoundaryTest`(arch) 통과 + api·batch 컨텍스트 기동(`@IntegrationTest`·`@BatchIntegrationTest` 기존 컨텍스트에 `PushSender` 빈 존재 확인) |
| 관리자 테스트 발송 | api `@IntegrationTest` — `PushSender` 를 페이크로 바꿔(`@ConditionalOnMissingBean` 이 페이크를 우선) 요청→dispatch SENT 확인 |

api 통합 컨텍스트에 페이크 `PushSender` 를 **기본 포함**시킨다(`@IntegrationTest` 의 `@Import` 에 추가 — 새 컨텍스트를 만들지 않기 위해).

## 11. 만들지 않는 것

- 영수증(receipts) 조회·DELIVERED 전이 — KB-473. 재시도·초당 600 제한·`@Async`·트리거 3종(좋아요 리스너·식사시간 잡·리뷰요청 폴링) — 각자 태스크. 게스트(installation) 대상 발송 — 비회원 알림 제거 예정(2026-09-07). 템플릿 DB 화·운영 편집.
