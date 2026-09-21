# Implementation Plan: 스캔 제안 배치 단일 청크 스텝 재구성 + Expo 영수증 확인·재전송

**Branch**: `kb-614-scan-suggestion-chunk-step` | **Date**: 2026-09-21 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/kb-614-scan-suggestion-chunk-step/spec.md`

## Summary

네 덩어리다. 서로 독립적으로 머지할 수 있게 순서를 잡는다.

| # | 덩어리 | 핵심 | 스토리 |
|---|--------|------|--------|
| A | 발송기 단순화 | `ExpoPushSender` 에서 스레드 풀·요청 간격 슬롯·`close()` 제거, 100건 순차 | US8 |
| B | 발송 잡 재구성 | 태스크릿+버퍼 삭제, 리더(`member_id` 커서)·프로세서(슬롯 필터)·기존 라이터의 청크 스텝 하나, 청크 100, 실 트랜잭션 매니저 | US1·2·7 |
| C | 발송 시각 | `MealSlot` 11:00·17:00, 스케줄 상수 | US6 |
| D | 영수증 확인·재전송 | 발송 이력 유형 컬럼, 영수증 조회 포트·Expo 어댑터, 도메인 서비스 `PushReceiptService`, 영수증 잡 2개 | US3·4·5·7 |

새로 만드는 운영 코드는 리더 2·프로세서 1·라이터 1·잡 설정 1·도메인 서비스 1·포트 1·어댑터 1·마이그레이션 1이다. 나머지는 삭제·축소다.

## Technical Context

**Language/Version**: Kotlin 2.3 / Java 21 toolchain

**Primary Dependencies**: Spring Boot 4.1, Spring Batch 6.0.4(`org.springframework.batch.infrastructure.item.*`), Spring Data JPA, Spring `RestClient` + `RetryTemplate`, Micrometer. **새 의존성 없음.**

**Storage**: MySQL. 스키마 변경 1건 — `notification_dispatch.notification_type VARCHAR(30) NULL` 추가 + 기존 행 채우기(Flyway, owner=api). 새 인덱스 없음.

**Testing**: Kotest `BehaviorSpec`. 배치 통합 `@BatchIntegrationTest`(Testcontainers MySQL·`FakePushSender`·`MutableClock` + 신규 `FakePushReceiptClient`), common 은 `@SpringBootTest` + `MySqlContainerConfig`, 어댑터는 로컬 HTTP 서버(`ExpoPushSenderTest` 방식), api 는 `@IntegrationTest`.

**Target Platform**: `:batch`(잡·스케줄), `:common`(포트·어댑터·도메인 서비스·엔티티·리포지토리), `:api`(마이그레이션, 발송기 조립 설정 정리)

**Project Type**: Gradle 멀티모듈 모듈러 모놀리스

**Performance Goals**: 없음(병렬 발송 폐기 결정). 1,200기기 발송 = 순차 요청 12회, 수 초. 영수증 잡은 회차당 확인 대상이 수십~수천 건.

**Constraints**: 한 시점 메모리 상 대상 ≤ 청크 크기(100). Expo 제한 — 발송 요청당 100건, 영수증 조회당 1000건, 영수증 보관 24시간, 권고 대기 15분. 트랜잭션 격리수준 미지정. 부가 방어 기능(분산 락·시간대 가드·skip 정책) 추가 금지. 배포 중 구 코드가 새 스키마 위에서 돈다(api 2대 블루/그린).

**Scale/Scope**: 회원 수백~수천. 변경 파일 약 35개(운영 신규 9·수정 12·삭제 2, 테스트 신규 6·수정 6).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | 덩어리마다 실패 테스트 선행. 기존 잡·발송기·api 시나리오가 회귀 그물 |
| II. Bounded Contexts | PASS | 새 도메인 코드는 전부 `common.domain.notification` 안. 컨텍스트 간 새 의존 없음 |
| III. Layered Dependency | PASS | 새 seam `PushReceiptClient` 는 `common.port.push`, 구현은 `common.infra.push`(batch 소비), 조립은 batch `PushConfig`. 도메인 서비스는 포트를 모른다 — 영수증은 도메인 타입 `ReceiptOutcome` 으로 받는다(기존 `PushOutcome` 과 같은 방식) |
| IV. Persistence Ownership | PASS | 엔티티=도메인 모델(상태 전이는 `NotificationDispatch` 메서드), 정책은 도메인 서비스, 배치는 리포지토리 직접 사용. `PushReceiptService` 는 `PushDispatchService` 와 같은 자리(`:common`) — 소비자가 batch 다 |
| V. Language Policy | N/A | 재전송은 저장된 제목·본문을 그대로 쓴다. 재렌더 없음 |
| 추가 제약 "외부 호출을 DB 트랜잭션 안에서 길게 잡지 않는다" | **위반(정당화)** | 발송 잡·영수증 잡 모두 실제 트랜잭션 매니저 — 사용자 결정 |

Phase 1 설계 후 재평가: 변동 없음.

## 설계

### A. 발송기 단순화 — `ExpoPushSender`

- 제거: `executor`·`minRequestInterval` 인자, `nextSlotAtNanos`, `awaitSlot()`, `threadSeq`, `AutoCloseable`/`close()`, `create(…)` 의 `concurrency`·`minRequestInterval`.
- `send` = `messages.chunked(CHUNK_SIZE).flatMap(::sendChunk)`.
- 유지: `CHUNK_SIZE = 100`, `RetryTemplate`, `isTransient`, `failAll`, 티켓 수 불일치 방어.
- `RestClient` 조립(baseUrl·JSON 컨버터·Bearer)을 같은 패키지 `internal fun expoRestClient(baseUrl, accessToken, builder)` 로 빼 영수증 어댑터(D)와 공유한다.
- api·batch `PushConfig`: 두 `@Value` 와 `destroyMethod = "close"` 제거. 양쪽 `application.yml`: `concurrency`·`min-request-interval` 과 주석 제거.

### B. 발송 잡 재구성 — `ScanSuggestionPushBatchConfig`

```text
scanSuggestion{Lunch|Dinner}PushJob
└─ scanSuggestion{Lunch|Dinner}SendStep   chunk(100), PlatformTransactionManager
     reader    ScanSuggestionMemberIdReader   (신규)
     processor ScanSuggestionSlotFilter        (신규)
     writer    ScanSuggestionPushWriter        (무변경)
```

- `job(slot, …)` 가 슬롯마다 리더·프로세서·라이터를 새로 만든다(리더가 커서 상태를 가지므로 공유 금지). `@StepScope`·`@JobScope` 없음. 잡·스텝 이름, `RunIdIncrementer`, `JobNameMdcListener` 불변.
- **리더** `ItemStreamReader<Long>` — `FoodVectorOutboxItemReader` 와 같은 구조(`cursor`·버퍼·`exhausted`, `open()` 초기화). 쿼리:

  ```kotlin
  @Query("select distinct s.memberId from NotificationSetting s where s.news = true and s.memberId > :afterMemberId order by s.memberId")
  fun findMemberIdsByNewsTrueAfter(@Param("afterMemberId") afterMemberId: Long, limit: Limit): List<Long>
  ```

  `Limit` 은 이 코드베이스 첫 사용 — JPQL `@Query` 와 안 맞으면 `Pageable` 로 바꾼다(테스트가 잡는다).
- **프로세서** `ItemProcessor<Long, Long>` — `existsByMemberIdAndTypeAndCreatedAtAfter(memberId, SCAN_SUGGESTION, startOfCurrentSlot(clock))` 이면 `null`. 소프트 삭제 행은 `@SQLRestriction` 이 제외. 슬롯 시작은 건마다 계산(순수 함수).
- 설정 키 `member-chunk-size`(500) → `chunk-size`(100). 리더 페이지 = 청크. 환경변수·IaC 매핑 없음(확인).
- 삭제: `ScanSuggestionCandidateDto.kt`, `ScanSuggestionTargetTasklet.kt`, `findMemberIdsByNewsTrue`, `findMemberIdsByTypeAndCreatedAtAfter` 와 그 테스트 케이스.

### C. 발송 시각

`MealSlot.LUNCH = 11:00`, `DINNER = 17:00`. `ScanSuggestionSendWindow.LUNCH_CRON = "0 0 11 * * *"`, `DINNER_CRON = "0 0 17 * * *"`. `startOfCurrentSlot` 은 `MealSlot.startTime` 을 읽으므로 로직 변경 없음. `ScanSuggestionSendWindowTest`·`ScanSuggestionPushJobTest` 의 시각 리터럴과 yml 주석 갱신.

### D. 영수증 확인·재전송

**D1. 스키마·엔티티**

- 마이그레이션 `V<생성 시각>__notification_dispatch_type.sql`(api): `ADD COLUMN notification_type VARCHAR(30) NULL` + `UPDATE notification_dispatch d JOIN notification n ON n.id = d.notification_id SET d.notification_type = n.type WHERE d.notification_type IS NULL`. 다른 마이그레이션에 순서 의존 없음.
- `NotificationDispatch.notificationType: NotificationType?`(`@Enumerated(STRING)`, `length = 30`). `pending(notificationId, deviceId, token, type)` 로 시그니처 확장. `PushDispatchService.prepare` 가 `request.type` 을 넘긴다.
- 상태 전이는 이미 있다 — `markDelivered()`(SENT→DELIVERED), `markFailed()`(SENT→FAILED). 추가 없음.

**D2. 포트·어댑터**

```kotlin
// common.port.push
data class PushReceipt(val ok: Boolean, val errorCode: String? = null, val message: String? = null)
fun interface PushReceiptClient { fun fetch(ticketIds: List<String>): Map<String, PushReceipt> }
```

`ExpoPushReceiptClient`(`common.infra.push`): `POST /--/api/v2/push/getReceipts {ids}` → `data` 맵. 1000건씩 `chunked`. 재시도 없음 — HTTP 실패는 예외로 올리고, 다음 회차가 곧 재시도다. 응답에 없는 id 는 맵에서 빠진다(= 아직 없음). 계약: [contracts/expo-push-receipts.md](contracts/expo-push-receipts.md).

`PushSender` 가 `fun interface` 라 메서드를 더할 수 없어 포트를 분리했다. batch `PushConfig` 에 `@ConditionalOnMissingBean(PushReceiptClient::class)` 빈 하나 추가. api 는 영수증을 조회하지 않으므로 조립하지 않는다.

**D3. 도메인 서비스 — `PushReceiptService`(`common.domain.notification`)**

```kotlin
data class ReceiptOutcome(val ok: Boolean, val errorCode: String?, val message: String?)
data class ResendPolicy(val maxResends: Int, val window: Duration)
data class ReceiptApplyResult(val resend: PreparedPush, val tally: Map<Pair<NotificationType, ReceiptResult>, Int>)
enum class ReceiptResult { DELIVERED, FAILED, RESENT }

@Transactional
fun apply(outcomes: Map<Long, ReceiptOutcome>, policy: ResendPolicy, now: LocalDateTime): ReceiptApplyResult
```

`outcomes` 의 키는 발송 이력 id(영수증이 없는 이력은 키가 없다 → 건드리지 않는다). 한 트랜잭션 안에서:

1. 이력·알림함 행·기기·알림별 이력 개수를 id 목록으로 한 번씩 조회한다.
2. `ok` → `markDelivered()`.
3. 오류 → `markFailed(errorCode ?: message ?: "unknown")`. `DeviceNotRegistered` 면 기기 `markTokenInvalid(now)`. 영구 오류 3종은 경고 로그.
4. 재전송 대상 오류(`MessageRateExceeded` 또는 `errorCode == null`)면 조건 판정 — 알림별 이력 개수 `< 1 + maxResends`, `notification.createdAt >= now - window`, 그리고 `PushTargetResolver.resolve(memberIds, type)` 결과에 그 기기가 있는가(유형별로 묶어 호출). 만족하면 같은 `notificationId` 로 새 `PENDING` 이력을 만들고 봉투를 쌓는다 — `to` 는 **판정기가 돌려준 현재 토큰**, 제목·본문·데이터는 알림함 행, `channelId = type.channelId`, `ttlSeconds` 는 광고성이면 `createdAt + window - now` 의 초, 아니면 `null`.
5. 재전송하지 않는 실패는 알림함 행 `delete()`.

재전송 접수 결과는 기존 `PushDispatchService.record(prepared, outcomes)` 를 그대로 쓴다(접수 실패 → `FAILED` + 알림함 소프트 삭제 — FR-021).

오류 코드 상수는 서비스 companion 에 둔다. 새 enum 을 만들지 않는다. 분류는 **재전송 대상(`MessageRateExceeded`·코드 없음)만 명시하고 나머지 전부를 영구 실패**로 둔다 — Expo 가 모르는 새 코드를 내도 재전송하지 않는 쪽이 안전하다.

**D4. 영수증 잡 — `PushReceiptSyncBatchConfig`(`com.kbap.batch.notification`)**

```text
{marketing|activity}PushReceiptSyncJob
└─ {marketing|activity}PushReceiptSyncStep   chunk(100), PlatformTransactionManager
     reader  PushReceiptTargetReader   (신규) 발송 이력 id 커서
     writer  PushReceiptSyncWriter     (신규)
```

- `job(marketing: Boolean, …)` 팩토리 하나가 두 잡을 만든다. 대상 유형 = `NotificationType.entries.filter { it.marketing == marketing }`.
- **리더** — `open()` 에서 시간 창을 한 번 계산(`now - maxAge` ~ `now - minAge`, `Clock` 빈)하고 id 커서로 100건씩:

  ```kotlin
  @Query("select d from NotificationDispatch d where d.dispatchStatus = :status and d.notificationType in :types and d.createdAt between :from and :to and d.id > :afterId order by d.id")
  fun findReceiptTargets(status, types, from, to, afterId, limit: Limit): List<NotificationDispatch>
  ```

  id 커서라 라이터가 상태를 바꿔도 페이지가 밀리지 않고, 재전송으로 생긴 새 이력은 `createdAt` 이 창 밖(15분 미만)이라 같은 회차에 다시 잡히지 않는다. 기존 인덱스 `(dispatch_status, created_at)` 이 받친다. 기존 `findByDispatchStatusAndCreatedAtBefore`(미사용)는 삭제한다.
- **라이터** — 한 청크에 대해:
  1. `receiptClient.fetch(ticketIds)` — 예외면 경고 로그 후 그 청크를 건너뛴다(FR-018, 잡은 계속).
  2. 티켓 id → 이력 id 로 바꿔 `receiptService.apply(…)`.
  3. `resend` 가 있으면 `sender.send(…)` → `dispatchService.record(…)`.
  4. 메트릭 `kbap.push.receipt{type, result=delivered|failed|resent|pending}` 와 로그.
- 설정 `kbap.batch.push-receipt.{chunk-size:100, min-age:15m, max-age:24h, resend-window:2h, max-resends:2}`.
- **스케줄**(`BatchJobScheduler`, 상수는 잡 설정 companion):
  - 광고성: `0 0/10 11-12,17-18 * * *` + `0 0 13,19 * * *`(`@Scheduled` 두 개를 한 메서드에).
  - 활동: `0 0/15 * * * *`.
  - 회차 겹침은 기존 `AlreadyRunning` 건너뜀이 처리한다.

### 테스트 계획 (Red 먼저)

| 덩어리 | 파일 | 시나리오 |
|--------|------|----------|
| A | `ExpoPushSenderTest`(수정) | 동시성·간격 케이스 2개 삭제, "250건이면 요청이 겹치지 않고 하나씩"(동시 진행 최대 1) 추가, `create` 호출부 정리 |
| B | `NotificationSettingJpaRepositoryTest`(수정) | 커서 이후만·오름차순·limit·다기기 distinct·news=false 제외 |
| B | `NotificationJpaRepositoryTest`(수정) | exists — 슬롯 이후 활성 true / 이전·다른 유형·소프트 삭제 false |
| B | `ScanSuggestionMemberIdReaderTest`(신규) | 250명 오름차순 후 null, 0명, `open()` 재호출 |
| B | `ScanSuggestionPushJobTest`(수정) | 기존 전 시나리오 + 250명 100·100·50 + 묶음 일부만 기수신 + `readCount`·`filterCount` |
| C | `ScanSuggestionSendWindowTest`(수정) | 10:59→전날 17:00, 11:00, 16:59, 17:00 |
| D1 | `PushDispatchServiceTest`(수정) | 준비된 이력에 유형 기록 |
| D1 | api 컨텍스트 기동(기존 테스트) | 마이그레이션 + `ddl-auto=validate` 정합 |
| D2 | `ExpoPushReceiptClientTest`(신규) | ok·오류(details.error)·코드 없는 오류·응답에 없는 id·1000건 분할·HTTP 오류는 예외 |
| D3 | `PushReceiptServiceTest`(신규) | spec US3·US4 수용 시나리오 전부 — 결과별 상태, 토큰 무효화, 재전송 조건 3종(횟수·시간·대상 자격), 기기 단위 재전송, 광고성 ttl·활동 null, 영수증 없는 이력 불변 |
| D4 | `NotificationDispatchJpaRepositoryTest`(수정) | 대상 조회 — 상태·유형·15분·24시간 경계·id 커서·유형 NULL 제외 |
| D4 | `PushReceiptSyncJobTest`(신규, 배치 통합) | 광고성 잡은 광고성만·활동 잡은 활동만, 재전송 3회째 최종 실패(이력 3건·알림함 삭제), 두 번째 ok 면 알림함 유지, 조회 예외 청크는 SENT 유지·잡 COMPLETED, 메트릭 |
| D4 | `ScanSuggestionPushJobTest`(수정) | 재전송 진행 중인 알림의 회원은 같은 슬롯 재실행에서 걸러짐(US2-4) |
| 전체 | api 기존 발송 테스트 | 수정 없이 통과(FR-011) |

스케줄 표현식은 테스트하지 않는다(프레임워크 설정 — 로컬 기동 로그로 확인). 동시성·부하 테스트 없음.

### 문서

- 지식 위키 `../kbap-agenthub/wiki/push-send-pipeline.md` 갱신 — 상태 정의, 영수증 잡, 재전송 규칙, 발송 시각 변경, 병렬 발송 폐기, 트랜잭션 예외, Jira 전제 불일치.
- Jira: 구현 후 KB-473 을 KB-614 로 흡수했다고 적고 종료(사용자 확인 후).

## Project Structure

### Documentation (this feature)

```text
specs/kb-614-scan-suggestion-chunk-step/
├── plan.md · research.md · data-model.md · quickstart.md
├── contracts/expo-push-receipts.md
├── checklists/requirements.md
└── tasks.md
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── port/push/PushReceiptClient.kt                      # 신규 (+ PushReceipt)
├── infra/push/
│   ├── ExpoPushSender.kt                                # 수정 — 순차, RestClient 조립 분리
│   └── ExpoPushReceiptClient.kt                        # 신규
└── domain/notification/
    ├── PushReceiptService.kt                            # 신규 (+ ReceiptOutcome·ResendPolicy·ReceiptApplyResult)
    ├── PushDispatchService.kt                           # 수정 — 이력에 유형 기록
    ├── model/NotificationDispatch.kt                    # 수정 — notificationType
    ├── model/MealSlot.kt                                # 수정 — 11:00·17:00
    ├── NotificationDispatchJpaRepository.kt             # 수정 — findReceiptTargets, findByNotificationIdIn, 미사용 메서드 삭제
    ├── NotificationSettingJpaRepository.kt              # 수정 — 커서 쿼리, 전량 쿼리 삭제
    └── NotificationJpaRepository.kt                     # 수정 — exists 파생 쿼리, 전량 쿼리 삭제

batch/src/main/kotlin/com/kbap/batch/
├── notification/
│   ├── ScanSuggestionPushBatchConfig.kt                 # 수정
│   ├── ScanSuggestionMemberIdReader.kt                  # 신규
│   ├── ScanSuggestionSlotFilter.kt                      # 신규
│   ├── ScanSuggestionSendWindow.kt                      # 수정 — cron 상수
│   ├── PushReceiptSyncBatchConfig.kt                    # 신규
│   ├── PushReceiptTargetReader.kt                       # 신규
│   ├── PushReceiptSyncWriter.kt                         # 신규
│   ├── ScanSuggestionCandidateDto.kt                    # 삭제
│   └── ScanSuggestionTargetTasklet.kt                   # 삭제
├── config/PushConfig.kt                                 # 수정 — 인자 정리, PushReceiptClient·PushReceiptService 조립
└── schedule/BatchJobScheduler.kt                        # 수정 — 영수증 잡 스케줄
batch/src/main/resources/application.yml                 # 수정

api/src/main/kotlin/com/kbap/api/core/config/PushConfig.kt           # 수정 — 인자 정리
api/src/main/resources/application.yml                                # 수정
api/src/main/resources/db/migration/V<ts>__notification_dispatch_type.sql   # 신규
```

**Structure Decision**: 기존 모듈·패키지 그대로. 영수증 잡은 발송 잡과 같은 `com.kbap.batch.notification` 에 둔다.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| 헌법 추가 제약 "외부 호출을 DB 트랜잭션 안에서 길게 잡지 않는다" — **발송 잡·영수증 잡**의 청크 트랜잭션 안 Expo 호출 | 조회·거르기·발송을 한 청크 스텝으로 묶는 것이 목표이고 사용자가 비용(발송 지연 = DB 연결 점유, 재시도 대기 최대 약 7초)을 알고 수용 | `ResourcelessTransactionManager` 유지는 사용자 결정과 다르다. research R3 에 차이와 감수하는 위험을 기록. 영수증 잡도 같은 결정을 따른다(업무 데이터와 배치 메타 기록의 원자적 커밋) |
