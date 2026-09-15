# Implementation Plan: SCAN_SUGGESTION 배치 — 점심 스케줄 스캔 제안 발송(광고성 동의자만)

**Branch**: `kb-471-scan-suggestion-batch` | **Date**: 2026-09-15 | **Spec**: [spec.md](spec.md) · [Jira KB-471](https://simhani1.atlassian.net/browse/KB-471)

**Input**: Feature specification from `/specs/kb-471-scan-suggestion-batch/spec.md`

## Summary

배치 앱에 **첫 스케줄 발송 잡** 두 개(`scanSuggestionLunchPushJob` 12:00 · `scanSuggestionDinnerPushJob` 18:00 KST, 코드 상수, 배치 1대 전제 — ShedLock 없음)를 추가한다. 두 잡은 대상 확정 step 을 공유하고 발송 step 의 문구 슬롯(`MealSlot`)만 다르며, 각각 두 스텝으로 돈다 — **① 대상 확정 tasklet**(소식 토글 켜진 회원 한 번 조회 → 이번 슬롯에 이미 받은 회원 제외 → 기존 `PushDispatchService.prepare` 로 기기별 알림함·dispatch(PENDING) 저장 → 발송 대기 목록을 잡 범위 버퍼에 적재) → **② 청크 발송 step**(후보 회원 id 를 500명 묶음으로 읽어 **공용 발송 부품** `PushNotifier.send(PushRequest(SCAN_SUGGESTION, memberIds, ttl, mealSlot))` 한 줄 호출). 슬롯별 문구는 `PushTemplates.bySlot` 한 곳(10 로케일 × 2슬롯), 렌더러가 슬롯 템플릿 → 기본 템플릿 순으로 고른다. 공용 발송 부품은 이번에 신설한다 — **port `common.port.push.PushNotifier`(알림 유형·대상 회원을 받는 계약) + 구현 `common.infra.push.ExpoPushNotifier`(prepare → Expo 발송 → record) + 조립은 api·batch `PushConfig`**. Expo 어댑터 `ExpoPushSender` 는 청크 100 분할·**동시 발송(고정 스레드 풀, 기본 6)**·**초당 600 페이싱**·**일시 실패 지수 백오프 재시도**를 소유한다. 봉투에 `channelId`(광고성=`news`)·`ttl` 을 실어 어댑터가 그대로 보낸다. api 의 3줄 글루 `PushNotificationService` 는 port 로 대체돼 삭제된다. 스키마 변경 없음. 리포지토리 쿼리 2개 추가. 제네릭은 쓰지 않는다(research §8).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1 / Spring Batch(Boot 4.1 라인) — 기존

**Primary Dependencies**: 기존 `PushDispatchService`·`PushTargetResolver`·`PushMessageRenderer`(common.domain.notification, batch `PushConfig` 가 `@Import`), `PushSender`/`ExpoPushSender`(port/infra), `BatchJobLauncher`·`BatchJobScheduler`·`JobNameMdcListener`(batch), `org.springframework.core.retry.RetryTemplate`/`RetryPolicy`(Framework 7 코어 — 벡터 잡이 이미 사용). **신규 의존 없음**(ShedLock 은 2026-09-15 사용자 결정으로 제외 — 배치 1대).

**Storage**: MySQL 기존 테이블(`notification_setting`·`notification_consent`·`notification_device`·`notification`·`notification_dispatch`). 마이그레이션 없음. 잡 범위 버퍼는 회원 id 만 든다.

**Testing**: batch `@BatchIntegrationTest`(컨텍스트 1개 유지 — `@Import` 에 `FakePushSenderConfig`·`MutableClockConfig` 추가; 페이크는 port `PushSender` 수준이라 공용 `ExpoPushNotifier` 는 실체가 돈다) 잡 통합 1클래스 + 순수 단위 1클래스(`ScanSuggestionSendWindowTest`) / common `CommonTestApp` 컨텍스트에 리포지토리 쿼리 2개·`PushDispatchServiceTest` 시나리오 추가·`ExpoPushNotifierTest`(페이크 sender 로 prepare→send→record 글루) / `ExpoPushSenderTest` channelId·ttl·재시도·동시성·페이싱 시나리오(`MockRestServiceServer` 는 스레드 안전하지 않으므로 동시성 시나리오는 지연을 넣은 페이크 `RestClient` 대신 **요청 시작 시각 기록** 으로 검증) / api 기존 관리자 테스트 발송 회귀(`PushNotifier` 주입으로 교체) / `ModuleBoundaryTest`(arch) 회귀.

**Target Platform**: `:batch` bootJar(ECS 상시 기동 1대 — 스케줄 동시성 문제 없음). common 변경은 api 도 컴파일 대상.

**Project Type**: 모듈러 모놀리스 — 배치 잡(batch) + 공용 파이프라인 소폭 확장(common).

**Performance Goals**: 회원 수백 규모 → 청크 1~3개, 잡 수 초. 처리량 = min(600/s, 동시성 × 100 ÷ Expo 지연). 지연 300ms 면 순차 333/s → 동시성 2 이상에서 600/s 상한 도달. 1만 기기 ≈ 17초, 10만 기기 ≈ 3분(상한이 하한).

**Constraints**: Expo 요청당 ≤100·초당 ≤600·페이로드 ≤4096B / 외부 호출은 트랜잭션 밖(청크 step 은 `ResourcelessTransactionManager`, `record` 는 자체 `@Transactional`) / 부팅 자동 실행 금지(`spring.batch.job.enabled=false` 유지) / 청크·동시성·페이싱·재시도는 어댑터 안에서만, 발송 절차(prepare→send→record)는 공용 notifier 안에서만 — 호출자는 `PushRequest` 한 줄 / 배치 스캔 범위 확장 금지 / Kotlin 주석 금지 / 테스트 컨텍스트 1개.

**Scale/Scope**: 신규 common 2(`port.push.PushNotifier`·`infra.push.ExpoPushNotifier`) + common 변경 7(`NotificationType`·`PushRequest`(봉투)·`PushDispatchService`·`PushMessage`·`ExpoPushSender`(channelId/ttl·스레드 풀·페이서·재시도)·리포지토리 2) + 신규 batch 5(config·window·tasklet·buffer·writer) + 테스트 픽스처 2 + api 변경 3(`PushConfig` 조립·관리자 발송 서비스가 `PushNotifier` 주입·`PushNotificationService` 삭제) + batch `PushConfig` + yml 2(api·batch 의 `kbap.push.expo.{concurrency,min-request-interval,retry.*}`). 테스트 신규 4·수정 5.

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | quickstart §1 순서: window 단위 → 리포지토리 쿼리 → 파이프라인 봉투(channelId/ttl) → 어댑터 통과 → writer 단위 → 잡 통합. 각 단계 Red 확인 후 구현. |
| II. Bounded Contexts | PASS | 도메인 변경은 `common.domain.notification` 안. 회원은 id 로만(회원 도메인 조회 없음 — 탈퇴 시 기기 연결·설정이 이미 해제되므로 회원 상태 조인 불필요, research §2). 허용 맵 변경 없음. |
| III. Layered Dependency | PASS | 호출자(api 기능·batch 잡)는 port `PushNotifier` 만 참조. 구현 `ExpoPushNotifier`·`ExpoPushSender` 직접 참조는 api·batch `PushConfig` 뿐(ArchUnit "어댑터 조립 창구"). port 가 도메인 값 타입(`PushRequest`·`PushDispatchResult`)을 쓰는 것은 허용 방향("포트가 도메인 타입을 반환")이고, `common.infra` → `common.domain` 은 금지 규칙이 없다(`infra.llm` 선례). 도메인은 여전히 port 를 모른다. |
| IV. Persistence Ownership | PASS | 후보·상한 조회는 리포지토리 쿼리 2개(파생/JPQL) 를 소비 계층(batch tasklet)이 직접 호출 — 위임 창구 서비스 없음. 대상 판정·저장·상태 전이는 기존 도메인 서비스·엔티티 소유. 트랜잭션: prepare/record 가 `@Transactional`, step 은 Resourceless. |
| V. Language Policy | PASS | 문구는 기존 `PushTemplates`(10 로케일) 그대로. 기기 `lang` → `LanguageCode.from`. |
| Additional Constraints | PASS | 외부 호출은 청크 writer 에서 트랜잭션 밖. 엔티티 노출 없음(HTTP 계약 변경 없음). |

**Post-design re-check (Phase 1 후, 재시도·공용 notifier·동시성 추가 후)**: PASS 유지 — 오케스트레이션이 `common.infra.push` 로 옮겨가지만 도메인은 port 를 모르고(3단 분리 유지), 호출자는 port 만 본다. KB-468 research §1 이 기각했던 "(b) 오케스트레이터를 infra 에" 를 이번에 채택하는 근거는 research §8(호출자 3+개, ADR-0018 "어댑터 = 외부 시스템 구현" 을 "Expo 발송 모듈" 로 읽음). 신규 추상화는 port 1개(구현 1개) — 페이크가 `PushSender` 수준에 있어 notifier 는 테스트에서도 실체가 돈다. research §3 이 "대상 확정 ↔ 발송" 사이 데이터 전달을 ExecutionContext 직렬화 대신 `@JobScope` 버퍼로 결정해 JobRepository 에 페이로드를 남기지 않는다. 신규 추상화 없음(인터페이스 1개 구현 없음·config 프로퍼티는 실제 환경별로 바뀌는 값 3개뿐).

## Project Structure

### Documentation (this feature)

```text
specs/kb-471-scan-suggestion-batch/
├── spec.md
├── plan.md                      # 이 파일
├── research.md                  # Phase 0
├── data-model.md                # Phase 1
├── quickstart.md                # Phase 1 — TDD 순서·로컬 검증
├── contracts/scan-suggestion-job.md   # 잡 이름·설정 키·종료 코드·Expo 메시지 변경·로그/메트릭
├── checklists/requirements.md
└── tasks.md                     # /speckit-tasks
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/
├── port/push/
│   ├── PushNotifier.kt                                # 신규 계약: send(PushRequest): PushDispatchResult — 호출자가 보는 유일한 문
│   └── PushMessage.kt                                 # + channelId, ttlSeconds?
├── infra/push/
│   ├── ExpoPushNotifier.kt                            # 신규: PushDispatchService.prepare → PushSender.send → record (구 api PushNotificationService 글루 이관)
│   └── ExpoPushSender.kt                              # 청크 100 · 고정 스레드 풀(concurrency) · 요청 시작 페이서(min-request-interval) · RetryTemplate(일시 실패) · channelId/ttl 직렬화
└── domain/notification/
    ├── model/NotificationType.kt                      # + channelId (marketing → "news", 아니면 "default")
    ├── model/MealSlot.kt                              # 신규: LUNCH(12:00)·DINNER(18:00) — 슬롯 문구·슬롯 상한의 축
    ├── PushTemplates.kt                               # + bySlot[SCAN_SUGGESTION][LUNCH|DINNER] 10 로케일, optOutNotice "프로필 > 알림 설정"
    ├── PushMessageRenderer.kt                         # render(..., slot) — 슬롯 템플릿 우선, 없으면 기본
    ├── PushRequest.kt                                 # PushRequest.ttlSeconds?·mealSlot?, PushEnvelope.channelId·ttlSeconds
    ├── PushDispatchService.kt                         # prepare 가 봉투에 channelId·ttl 채움
    ├── NotificationSettingJpaRepository.kt            # + findMemberIdsByNewsTrue()
    └── NotificationJpaRepository.kt                   # + findMemberIdsByTypeAndCreatedAtAfter(type, since)

batch/
├── src/main/kotlin/com/kbap/batch/
│   ├── notification/                                  # 신규 기능 패키지 (outbox·vector 와 나란히)
│   │   ├── ScanSuggestionPushBatchConfig.kt           # Clock 빈·tasklet step(공유)·슬롯별 send step + job 2개(Lunch/Dinner)
│   │   ├── ScanSuggestionSendWindow.kt                # 슬롯(12:00·18:00) 시작 계산 + cron 상수
│   │   ├── ScanSuggestionTargetTasklet.kt             # 후보 조회 → 이번 슬롯 받은 회원 제외 → 버퍼 적재
│   │   ├── ScanSuggestionCandidateBuffer.kt           # @JobScope 잡 범위 회원 id 큐 + reader
│   │   └── ScanSuggestionPushWriter.kt                # ItemWriter<Long>: notifier.send(PushRequest(SCAN_SUGGESTION, ids, ttl, slot)) → 로그·카운터
│   ├── config/PushConfig.kt                           # + ExpoPushSender.create(..., concurrency, interval, retryPolicy) · ExpoPushNotifier 빈
│   └── schedule/BatchJobScheduler.kt                  # + @Scheduled 점심(12:00)·저녁(18:00) KST 메서드 2개
├── src/main/resources/application.yml                 # kbap.batch.scan-suggestion.{member-chunk-size,ttl} · kbap.push.expo.{concurrency,min-request-interval,retry.*}
└── src/test/kotlin/com/kbap/batch/
    ├── BatchIntegrationTest.kt                        # @Import + FakePushSenderConfig, MutableClockConfig
    ├── notification/
    │   ├── FakePushSenderConfig.kt                    # @Primary PushSender — 요청 기록·n번째 요청 실패 주입
    │   ├── MutableClockConfig.kt                      # @Primary Clock — 테스트가 시각을 바꾼다
    │   ├── ScanSuggestionSendWindowTest.kt            # 순수 경계값
    │   └── ScanSuggestionPushJobTest.kt               # @BatchIntegrationTest — 대상/제외/슬롯당 1회/1,200명 묶음/부분 실패/HTTP 트리거
    └── resources/application.yml                      # (변경 없음 — 스케줄러 off, 기본 프로퍼티 사용)

api/
├── src/main/kotlin/com/kbap/api/core/config/PushConfig.kt        # ExpoPushSender.create(..., concurrency, interval, retryPolicy) · ExpoPushNotifier 빈
├── src/main/kotlin/com/kbap/api/notification/PushNotificationService.kt   # 삭제 — 관리자 테스트 발송 서비스가 PushNotifier 주입
└── src/main/resources/application.yml                            # kbap.push.expo.{concurrency,min-request-interval,retry.*}
```

**Structure Decision**: 발송의 "어떻게" 는 전부 `common.port.push`(계약) + `common.infra.push`(Expo 발송 모듈 = 오케스트레이션 + 어댑터) 에 모인다 — 세 전송(스캔 제안·식사시간·활동/리뷰)과 관리자 발송이 같은 `PushNotifier` 를 쓴다. 배치 기능 패키지 `com.kbap.batch.notification` 은 "누구에게·언제" 만 안다(기존 `outbox`·`vector` 선례). 유형→채널 매핑은 `NotificationType` 한 곳. 스케줄은 기존 `BatchJobScheduler` 에 메서드 하나로 얹는다.

## Complexity Tracking

없음 — 헌법 위반 없음. 단순화 선택(하루 1회 상한을 회원 단위로 판정·페이서는 요청 시작 시각 고정 간격·제네릭 미사용)은 research 에 근거를 남겼다.
