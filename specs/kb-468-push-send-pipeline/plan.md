# Implementation Plan: Expo Push 발송 공용 파이프라인 — 100건 배치 전송·티켓 저장·언어별 렌더

**Branch**: `kb-468-push-send-pipeline` | **Date**: 2026-09-11 | **Spec**: [Jira KB-468](https://simhani1.atlassian.net/browse/KB-468) (spec.md 없음 — `/speckit-specify` 미실행, Jira 본문을 명세로 사용)

**Input**: Jira KB-468 본문(2026-09-11 결정 포함) + KB-464~467 산출물(`common.domain.notification` 엔티티·리포지토리·설정/알림함 API).

## Summary

"정해진 대상에게 종류·언어에 맞게 보내는 방법" 을 api·batch 가 공유하는 부품으로 만든다. **도메인은 port 를 모른다(ArchUnit)** 는 규칙을 지키기 위해 파이프라인을 **prepare(도메인, 트랜잭션) → send(port, 트랜잭션 밖) → record(도메인, 트랜잭션)** 3단으로 나눈다 — 헌법이 지시하는 "pending 저장 → 외부 호출 → 결과 저장" 패턴이다. `common.port.push.PushSender` + `common.infra.push.ExpoPushSender`(RestClient, 100건 청크, 순서 동일 티켓, 예외 대신 error 티켓), `common.domain.notification` 에 첫 공유 도메인 서비스 3개(`PushTargetResolver`·`PushMessageRenderer`·`PushDispatchService`), 조립은 api·batch 각자 `PushConfig`. 실기기 DoD 를 위해 관리자 테스트 발송 엔드포인트 1개. 스키마 변경 없음(`NotificationType.MEAL_TIME` 만 추가).

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1 (기존)

**Primary Dependencies**: `RestClient`(`common` 에 `libs.spring.web` 추가), `MockRestServiceServer`(spring-test, 기존), `NotificationDevice/Setting/Consent/Notification/Dispatch` 엔티티·리포지토리(KB-464), `LanguageCode.from`(미지원→en), `BaseEntity` dirty checking.

**Storage**: MySQL 기존 5테이블. 리포지토리 `in` 일괄 조회 3개 추가(파생 2·JPQL 1). 마이그레이션 없음.

**Testing**: common 단위(`ExpoPushSenderTest`·`PushMessageRendererTest`) + common `@SpringBootTest`(`CommonTestApp` 컨텍스트 재사용 — `PushTargetResolverTest`·`PushDispatchServiceTest`) + api `@IntegrationTest`(관리자 테스트 발송, `FakePushSenderConfig` 를 `@IntegrationTest` 기본 `@Import` 에 추가해 컨텍스트 수 1 유지) + `ModuleBoundaryTest`(arch).

**Target Platform**: api·batch bootJar 양쪽.

**Project Type**: 모듈러 모놀리스 — 공유 부품(common) + 조립(api/batch config).

**Performance Goals**: 100건 청크 1회 왕복 < 10s(read timeout). 회원 수백 규모에서 트리거 1회 수 초 이내.

**Constraints**: 도메인→port 의존 금지(ArchUnit)·어댑터 직접 참조는 config 만·외부 호출은 트랜잭션 밖·Kotlin 주석 금지·부가 방어(재시도·레이트리밋·락) 금지·배치 스캔 범위 확장 금지(`@Import` 로 명시 등록).

**Scale/Scope**: 신규 ~14 파일(port 3·infra 1·domain 6·api config 1·batch config 1·admin 5·페이크 1) + 변경 6(`NotificationType`·리포지토리 3·`NotificationConsentService`·`IntegrationTest`·yml 2·`common/build.gradle.kts`). 테스트 5클래스.

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | quickstart §1 순서대로 어댑터→렌더러→필터→dispatch→조립→관리자 순 각 Red 확인 후 구현. |
| II. Bounded Contexts | PASS | 변경은 `common.domain.notification` 안. 다른 도메인 참조 없음(회원은 id). `allowedDomainDeps["notification"] = emptySet()` 유지. |
| III. Layered Dependency | PASS | 도메인 서비스는 port 를 모른다(3단 분리). `PushSender` 는 `common.port.push`, 구현 `common.infra.push`, 조립 `api.core.config`·`batch.config` 만 어댑터 참조. port 는 Spring-free. |
| IV. Persistence Ownership | PASS | 상태 전이는 엔티티(`markSent/markFailed/markTokenInvalid`), 서비스 `@Transactional` 명시, dirty checking. 필터·렌더·dispatch 로직은 도메인 서비스 소유(소비자엔 3줄 글루뿐). batch 는 `@Import` 로 같은 서비스 사용. |
| V. Language Policy | PASS | 10 로케일 파리티 테스트. 미지원 기기 lang → en 폴백(`LanguageCode.from`). 푸시 문구는 콘텐츠 번역(ADR-0003)이 아니라 UI 문구 — 코드 템플릿. |
| Additional Constraints | PASS | 외부 호출은 트랜잭션 밖(send 단계). 엔티티 미노출(관리자 응답은 `{sent,failed}`). |

**Post-design re-check**: 유지 — research §1 이 규칙 완화 대안을 기각하고 구조로 해결.

## Project Structure

### Documentation (this feature)

```text
specs/kb-468-push-send-pipeline/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/push-data-contract.md   # FE data 계약 · Expo 호출 · 관리자 테스트 발송
└── tasks.md                          # /speckit-tasks
```

### Source Code (repository root)

```text
common/build.gradle.kts                                   # + "implementation"(libs.spring.web)
common/src/main/kotlin/com/kbap/common/
├── port/push/
│   ├── PushMessage.kt · PushTicket.kt · PushSender.kt       # seam (Spring-free)
├── infra/push/
│   └── ExpoPushSender.kt                                     # RestClient, 100건 청크, error 티켓
└── domain/notification/
    ├── model/NotificationType.kt                             # + MEAL_TIME, marketingByDefault
    ├── model/NotificationConsents.kt                         # isMarketingEnabled(open, requiredVersion)
    ├── NotificationDeviceJpaRepository.kt                    # + findByMemberIdInAndTokenInvalidAtIsNull
    ├── NotificationSettingJpaRepository.kt                   # + findByMemberIdIn
    ├── NotificationConsentJpaRepository.kt                   # + findOpenByMemberIdIn
    ├── PushRequest.kt                                        # PushRequest · PushContent · PushEnvelope · PreparedPush · PushOutcome · PushDispatchResult
    ├── PushTemplates.kt                                      # 5 type × 10 locale + optOutNotice
    ├── PushMessageRenderer.kt                                # @Service
    ├── PushTargetResolver.kt                                 # @Service, @Transactional(readOnly)
    └── PushDispatchService.kt                                # @Service, prepare / record

api/src/main/kotlin/com/kbap/api/
├── core/config/PushConfig.kt                                 # @Bean PushSender = ExpoPushSender.create(...)
├── notification/PushNotificationService.kt                  # send(request): prepare→send→record 글루 (PushEnvelope↔PushMessage · PushTicket↔PushOutcome 매핑)
├── notification/NotificationConsentService.kt               # isMarketingEnabled → NotificationConsents 위임
└── admin/AdminNotificationTestController.kt · AdminNotificationTestApi.kt · AdminNotificationTestService.kt
          · AdminNotificationTestRequest.kt · AdminNotificationTestResponse.kt
api/src/main/resources/application.yml                       # kbap.push.expo.{base-url,access-token}

batch/src/main/kotlin/com/kbap/batch/config/PushConfig.kt   # @Bean PushSender + @Import(도메인 서비스 3종)
batch/src/main/resources/application.yml                     # kbap.push.expo.*

common/src/test/kotlin/com/kbap/common/
├── infra/push/ExpoPushSenderTest.kt
└── domain/notification/PushMessageRendererTest.kt · PushTargetResolverTest.kt · PushDispatchServiceTest.kt
api/src/test/kotlin/com/kbap/api/
├── IntegrationTest.kt                                        # @Import += FakePushSenderConfig
├── notification/FakePushSender.kt                            # 기록형 페이크 + Config
└── admin/AdminNotificationTestControllerTest.kt
```

**Structure Decision**: 공유 부품은 ADR-0016 배치 기준("batch 도 쓰는가")대로 `:common`. 도메인 서비스 3개는 `common.domain.notification` 루트(엔티티는 `model/`), seam 은 `common.port.push`, 어댑터는 `common.infra.push`(ADR-0018 — batch 도 쓰는 어댑터). 소비자 글루는 api `api.notification.PushNotificationService` 하나(향후 좋아요 리스너가 같은 걸 호출), batch 는 트리거 잡 태스크에서 같은 3줄. 관리자 테스트 발송은 `api.admin` 의 Admin* 세트(관리자 서비스 분리 원칙).

## Complexity Tracking

위반 없음. 규칙 완화 대안(도메인→port 예외)은 research §1 에서 기각.
