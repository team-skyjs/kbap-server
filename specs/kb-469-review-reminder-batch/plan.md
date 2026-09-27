# Implementation Plan: 주문 1시간 후 리뷰 리마인더 푸시 배치 (REVIEW_REMINDER)

**Branch**: `kb-469-review-reminder-batch` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md) · [Jira KB-469](https://simhani1.atlassian.net/browse/KB-469)

**Input**: Feature specification from `specs/kb-469-review-reminder-batch/spec.md`

## Summary

앱의 기기 로컬 "1시간 후 리뷰 리마인더" 를 서버 배치 푸시로 옮긴다. 5분 cron(+HTTP 트리거) 잡이 생성 1~25시간 경과 회원 주문 중 "주문 이후 `REVIEW_REMINDER` 알림 없음 · 리뷰 안 쓴 항목 있음" 인 주문을 골라, **주문 1건 = 발송 요청 1건**으로 공용 발송기(`PushHandler`)에 넘긴다. 문구는 음식명 없는 주문 맥락(본문만 교체), 푸시 data 는 `{type, orderId(숫자), notificationId}`, 알림함 응답에 `orderId` 를 추가한다. 스키마 변경 없음. 구조는 스캔 제안 배치(리더/라이터·청크 100·메트릭)를 그대로 따른다.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1 (기존)

**Primary Dependencies**: Spring Batch(`StepBuilder`/`JobBuilder`, 기존), `PushHandler`(`common.port.push`)·`PushRequest`(KB-468), `OrderJpaRepository`·`Notification`·`Review`·`OrderItem` 엔티티(기존), `Clock` 빈(Asia/Seoul, 기존), Micrometer 카운터(기존).

**Storage**: MySQL 기존 테이블(`orders`·`order_item`·`food_review`·`notification`). JPQL 1개 추가. 마이그레이션 없음.

**Testing**: common `@SpringBootTest(CommonTestApp)` 리포지토리·엔티티 테스트, common 렌더러 단위 테스트, batch `@BatchIntegrationTest`(리더·잡 — `FakePushClient`·`MutableClock`), api `@IntegrationTest`(알림함). 전부 Kotest `BehaviorSpec`.

**Target Platform**: `:batch` bootJar(발송) + `:api` bootJar(알림함 응답) + `:common`(엔티티·리포지토리·문구).

**Project Type**: 모듈러 모놀리스 — 배치 조합(`com.kbap.batch.notification.reminder`) + 공유 영속/문구(`:common`) + api 응답 필드.

**Performance Goals**: 5분 주기 1회 실행이 수 초 이내(주문 일 수백 건 규모). 청크 100 = 외부 푸시 호출 최대 100회/청크.

**Constraints**: 배치 1대·분산 락 없음·부팅 자동 실행 금지(기존 yml). 도메인→port 금지(ArchUnit) — 발송은 배치 라이터가 `PushHandler` 로. Kotlin 주석 금지. 부가 방어(재시도·락·마커 컬럼) 금지. `orders` 발송 마커 컬럼 금지(스펙).

**Scale/Scope**: 신규 6 파일(batch 리더·라이터·창·Config·메트릭 상수 / 테스트 2) + 변경 ~17(`Notification`·`OrderJpaRepository`·`NotificationResponse`·`NotificationApi` 문서·`BatchJobScheduler`·`ScanSuggestionPushWriter`·batch yml·`push_*.properties` 10·KB-468 계약 문서·테스트 3).

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | quickstart 순서대로 엔티티→문구→리포지토리→리더→잡→알림함 각 Red 확인 후 구현. |
| II. Bounded Contexts | PASS (주의) | 컴파일 의존 추가 없음 — `order` 허용 맵 `emptySet()` 유지. 대상 조회 JPQL 이 `Notification`·`Review`·`OrderItem` 을 **문자열로** 참조한다(Complexity Tracking). 배치 조합은 `com.kbap.batch.notification.reminder` 소유(컨벤션 규칙 8). |
| III. Layered Dependency | PASS | 배치 → common 만. 발송은 seam(`PushHandler`) 경유, 어댑터 직접 참조 없음(기존 `PushConfig` 조립 재사용). |
| IV. Persistence Ownership | PASS | 리포지토리는 `common.domain.order` 에 public 추가, 배치가 직접 주입. 새 상태 전이 없음(알림 행은 기존 `PushDispatchService` 가 트랜잭션 안에서 생성). 청크 트랜잭션은 Spring Batch 스텝이 소유(스캔 제안과 동일). |
| V. Language Policy | PASS | 푸시 문구는 UI 문구(코드 템플릿, 10 로케일). 기기 `lang` 미지원 → en 폴백은 기존 렌더러. 음식 콘텐츠 번역 미사용(음식명 제거). |
| Additional Constraints | PASS | 외부 호출은 `PushHandler` 3단(prepare/send/record) 안에서 트랜잭션 분리(기존). 엔티티 미노출(알림함은 `NotificationResponse`). |

**Post-design re-check**: 유지. research §1 이 배치 네이티브 SQL·배치 리포지토리 대안을 기각하고 JPQL 문자열 참조를 감수하는 이유를 기록.

## Project Structure

### Documentation (this feature)

```text
specs/kb-469-review-reminder-batch/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/push-data-and-inbox.md
└── tasks.md                      # /speckit-tasks
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/
├── notification/model/Notification.kt              # + orderIdOrNull, DATA_ORDER_ID, longFromData 공유
└── order/OrderJpaRepository.kt                     # + findReviewReminderTargets(from,to,type,afterId,limit)
common/src/main/resources/messages/push_{ko,en,es,ru,id,ja,th,vi,zh_Hans,zh_Hant}.properties
                                                    # review_reminder.{1,2,3}.body 교체({food} 제거)
common/src/test/kotlin/com/kbap/common/domain/
├── notification/model/NotificationTest.kt          # + orderIdOrNull
├── notification/PushMessageRendererTest.kt         # + 10 로케일 REVIEW_REMINDER 본문 {food} 부재
└── order/OrderJpaRepositoryTest.kt                 # + 대상 조회 6 시나리오 (신규 파일이면 CommonTestApp 컨텍스트)

batch/src/main/kotlin/com/kbap/batch/
├── notification/PushDispatchMetric.kt              # NAME = "kbap.push.dispatch" (스캔 제안 상수 이관)
├── notification/reminder/
│   ├── ReviewReminderWindow.kt                     # MIN_AGE 1h · MAX_AGE 25h · of(clock)
│   ├── ReviewReminderOrderReader.kt                # ItemStreamReader<Order>, id 커서
│   ├── ReviewReminderPushWriter.kt                 # distinctBy memberId → 주문당 PushRequest → 메트릭
│   └── ReviewReminderPushBatchConfig.kt            # job/step 빈, EVERY_5_MINUTES, JOB_NAME
├── notification/suggestion/ScanSuggestionPushWriter.kt   # METRIC → PushDispatchMetric.NAME
└── trigger/scheduler/BatchJobScheduler.kt          # + @Scheduled 5분
batch/src/main/resources/application.yml            # kbap.batch.review-reminder.{chunk-size,ttl}
batch/src/test/kotlin/com/kbap/batch/notification/reminder/
├── ReviewReminderOrderReaderTest.kt
└── ReviewReminderPushJobTest.kt

api/src/main/kotlin/com/kbap/api/notification/
├── NotificationResponse.kt                         # + orderId, foodId 설명 정정
└── NotificationApi.kt                              # 목록·읽음 문서에 orderId·foodId 미사용
api/src/test/kotlin/com/kbap/api/notification/NotificationInboxTest.kt   # + orderId 케이스

specs/kb-468-push-send-pipeline/contracts/push-data-contract.md         # orderId 행 추가·foodId 미사용
```

**Structure Decision**: 배치 조합은 스캔 제안(`batch.notification.suggestion`)과 나란히 `batch.notification.reminder`. 대상 조회는 주문 도메인 리포지토리(구동 테이블 소유자). 알림함 필드는 기존 `api.notification`. 공유 상수 하나만 `batch.notification` 루트로 올린다.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| `OrderJpaRepository` JPQL 이 타 도메인 엔티티(`Notification`·`Review`·`OrderItem`) 를 문자열로 참조 | "주문 이후 알림 없음 · 리뷰 안 쓴 항목 있음" 판정은 한 쿼리여야 커서 페이징·소프트삭제 필터(`@SQLRestriction`)가 자동으로 맞는다 | 배치 네이티브 SQL — status 조건 4곳 수동 관리·선례 없음. 애플리케이션 조인(주문 조회 후 회원별 알림·리뷰 재조회) — 청크마다 N 회 추가 조회, 페이지 정합 깨짐 |
