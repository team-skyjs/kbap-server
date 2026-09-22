# Implementation Plan: HELPFUL 발송 — 리뷰 좋아요 시 작성자에게 알림

**Branch**: `kb-470-helpful-push` | **Date**: 2026-09-16 | **Spec**: [spec.md](spec.md) · [Jira KB-470](https://simhani1.atlassian.net/browse/KB-470)

**Input**: Feature specification from `/specs/kb-470-helpful-push/spec.md`

## Summary

리뷰 좋아요(`POST /api/reviews/{reviewId}/like?liked=true`)가 **새 좋아요이고 작성자 본인이 아니면** `ReviewService.likeReview` 가 트랜잭션 안에서 `ReviewLiked(reviewId, authorMemberId, foodId)` 를 발행한다. api 의 `HelpfulPushListener` 가 `@Async @TransactionalEventListener(AFTER_COMMIT)` 로 받아 음식 이름을 10개 언어로 만든 뒤 기존 공용 `PushHandler.send(PushRequest(HELPFUL, [author], argsByLang, data = {reviewId}))` 한 줄로 발송한다. Jira 의 아웃박스+batch 폴링은 채택하지 않는다(research §1). 공용 파이프라인 변경은 둘뿐: `NotificationType.channelId` 활동 유형 `"activity"`, `PushRequest.argsByLang`(기기 언어별 인자). 묶음 정책은 2026-09-16 결정으로 범위 밖(research §4). 스키마 변경·신규 프로퍼티·신규 의존 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1 — 기존

**Primary Dependencies**: 기존 `PushHandler`(port, api `PushConfig` 가 `ExpoPushHandler` 조립)·`PushDispatchService`·`PushTemplates`(HELPFUL 10 로케일 이미 존재)·`BackgroundConfig(@EnableAsync)`·`ApplicationEventPublisher`. **신규 의존 없음.**

**Storage**: MySQL 기존 테이블(`food_review`·`review_like`·`food`·`notification_*`). **마이그레이션 없음.**

**Testing**: api `@IntegrationTest`(컨텍스트 1개 — `FakePushSender` 기본 포함) `ReviewLikeControllerTest` 에 시나리오 추가, 비동기는 Kotest `eventually`/`continually` / common `PushDispatchServiceTest`(채널·언어별 인자) / `ModuleBoundaryTest`(arch) 회귀.

**Target Platform**: `:api` bootJar(ECS 2대). 요청 단위 트리거라 인스턴스 간 중복 없음. common 변경은 batch 도 컴파일 대상(`PushRequest` 기본값 추가라 호환).

**Project Type**: 모듈러 모놀리스 — api 기능(review·notification) + common 파이프라인 소폭 확장.

**Performance Goals**: 좋아요 요청에 추가되는 것은 `findById` 1회(`existsById` 대체)와 `findByReviewIdAndMemberId` 1회. 발송은 별도 스레드 — 알림 도착 수 초(SC-001 10초).

**Constraints**: Expo 호출은 요청 트랜잭션 밖(커밋 이후, 다른 스레드) / 리스너 메서드에 `@Transactional` 금지(research §2) / 재시도·아웃박스·락 금지(spec FR-009, 메모리 "부가 방어 금지") / Kotlin 주석 금지 / 테스트 컨텍스트 1개 유지(`@IntegrationTest` 헤더 불변).

**Scale/Scope**: 신규 api 2(`review.ReviewLiked`·`notification.HelpfulPushListener`) + api 변경 1(`ReviewService.likeReview`) + common 변경 3(`NotificationType`·`PushRequest`·`PushDispatchService`) + 테스트 수정 2(`PushDispatchServiceTest`·`ReviewLikeControllerTest`).

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | quickstart §1: 채널 → 언어별 인자 → 발행 조건+리스너 통합 → 실패 격리. 각 단계 Red 확인 후 구현. |
| II. Bounded Contexts | PASS | 도메인 변경은 `common.domain.notification` 안. 리뷰→알림은 api 기능 패키지 간 이벤트(`api.review.ReviewLiked` → `api.notification` 리스너)로, 도메인 허용 맵 변경 없음. 리뷰·음식·회원은 id 로만. |
| III. Layered Dependency | PASS | 리스너는 port `PushHandler` 만 참조. 어댑터 직접 참조 없음(조립은 기존 `PushConfig`). |
| IV. Persistence Ownership | PASS | 리스너는 음식 조회에 리포지토리를 직접 쓴다 — 위임 창구 서비스 없음. 트랜잭션: `likeReview` 명시 `@Transactional`, `prepare`/`record` 자체 `@Transactional`, 리스너는 무트랜잭션(의도적 — research §2). |
| V. Language Policy | PASS | 문구는 기존 `PushTemplates`, 음식 이름은 `Food.displayName(lang)`(번역 부재 → ko 폴백) 을 기기 언어별로. |
| Additional Constraints | PASS | 외부 호출은 커밋 이후 별도 스레드. HTTP 계약·엔티티 노출 변경 없음. |

**Post-design re-check (Phase 1 후)**: PASS 유지. 신규 추상화 없음(이벤트 data class 1·리스너 1). `argsByLang` 은 기본값 있는 필드 추가라 기존 호출자(관리자 발송·배치) 무변경.

## Project Structure

### Documentation (this feature)

```text
specs/kb-470-helpful-push/
├── spec.md
├── plan.md                    # 이 파일
├── research.md                # Phase 0 — 발송 주체·@Async+AFTER_COMMIT·발행 조건·묶음 보류·언어별 인자·채널·관측·위치
├── data-model.md              # Phase 1 — 흐름별 읽기/쓰기·이벤트·값 타입 변경·data JSON
├── quickstart.md              # Phase 1 — TDD 순서·로컬 검증
├── contracts/helpful-push.md  # 트리거 조건표·Expo 메시지·알림함 data·실패/관측·내부 계약 변경
├── checklists/requirements.md
└── tasks.md                   # /speckit-tasks
```

### Source Code (repository root)

```text
common/src/main/kotlin/com/kbap/common/domain/notification/
├── model/NotificationType.kt          # channelId: HELPFUL·REVIEW_REMINDER → "activity", 광고성 → "news" ("default" 제거)
├── PushRequest.kt                     # + argsByLang: Map<LanguageCode, Map<String, String>> = emptyMap()
└── PushDispatchService.kt             # prepare: renderer.render(type, lang, request.argsByLang[lang] ?: request.args, slot)

api/src/main/kotlin/com/kbap/api/
├── review/
│   ├── ReviewLiked.kt                 # 신규: data class ReviewLiked(reviewId, authorMemberId, foodId)
│   └── ReviewService.kt               # likeReview: findById → 활성 좋아요 없음 && 비작성자 → upsert 후 publishEvent
└── notification/
    └── HelpfulPushListener.kt         # 신규: @Async @TransactionalEventListener(AFTER_COMMIT) — 음식 이름 10개 언어 → PushHandler.send → try/catch 로그

common/src/test/kotlin/com/kbap/common/domain/notification/
└── PushDispatchServiceTest.kt         # 채널 단언 activity 로 교체 + given("언어별 인자")

api/src/test/kotlin/com/kbap/api/review/
└── ReviewLikeControllerTest.kt        # + given("좋아요 알림") — 발송/제외 시나리오(eventually·continually), 리스너 실패 격리
```

**Structure Decision**: 리뷰 도메인은 "좋아요가 생겼다" 만 말하고(`ReviewLiked`), 알림 기능이 "누구에게 어떻게" 를 소유한다(`HelpfulPushListener`). 발송의 "어떻게" 는 KB-471 이 세운 `common.port.push` + `common.infra.push` 그대로 — 이 기능은 호출자 하나를 더할 뿐이다. 유형→채널 매핑은 `NotificationType` 한 곳(Jira 권고).

## Complexity Tracking

없음 — 헌법 위반 없음. 단순화 선택(묶음 보류·Jira 아웃박스 기각)은 research 에 근거를 남겼다.
