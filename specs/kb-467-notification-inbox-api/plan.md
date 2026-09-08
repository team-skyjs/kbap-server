# Implementation Plan: 회원 알림함 API — 최근 7일 알림 목록·읽음 처리

**Branch**: `kb-467-notification-inbox-api` | **Date**: 2026-09-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/kb-467-notification-inbox-api/spec.md`

## Summary

KB-464 의 `notification` 테이블 위에 **회원 전용** 엔드포인트 2개: `GET /api/notifications`(조회 시각 기준 168시간 이내, id 역순, 전부), `PATCH /api/notifications/{id}/read`(멱등, 타인·부재는 404 `NOTIFICATION-002`). 페이징·모두 읽기·읽음 취소·미읽음 수 없음(2026-09-07 결정). 새 파일은 `com.kbap.api.notification` 에 Api·컨트롤러·서비스·응답 DTO 4개 + 통합 테스트 1개, 변경은 ErrorCode 1줄·리포지토리 파생 쿼리 2개·WebConfig 보호 경로. 스키마 변경 없음.

## Technical Context

**Language/Version**: Kotlin 2.3 / JDK 21 / Spring Boot 4.1 (기존)

**Primary Dependencies**: `NotificationJpaRepository`(파생 쿼리 2개 추가), `Notification.markRead(now)`(멱등 — readAt 있으면 no-op, 기존), `@AuthMemberId`, `BaseResponse`, `MemberService.getMember`.

**Storage**: MySQL `notification` 그대로. 인덱스 `(member_id, id)` 가 `member_id = ? and created_at > ? order by id desc` 를 member_id 로 좁힌 뒤 범위 스캔 — 7일치라 행 수가 작아 충분. 마이그레이션 없음.

**Testing**: `@IntegrationTest` + BehaviorSpec 1클래스(`NotificationInboxControllerTest`). 알림 시드는 리포지토리 `save(Notification.forMember(...))`. 7일 경계 시나리오는 저장 후 `created_at` 을 JDBC 로 8일 전으로 갱신(BaseEntity 가 createdAt 을 자동 채우므로 생성 시 지정 불가).

**Target Platform**: api bootJar.

**Project Type**: web-service 기능 추가.

**Performance Goals**: 목록 < 1초 (SC-001).

**Constraints**: 비회원 알림은 기획상 제거 예정(2026-09-07) — 회원 알림에만 집중한다. 기존 게스트 토큰 등록 테스트가 깨지지 않게 필터 게스트 예외 한 줄만 두고, 게스트 제거 후속에서 함께 삭제(연구 §2). 모두 읽기·미읽음 수 엔드포인트 금지. Kotlin 주석 금지.

**Scale/Scope**: 새 파일 5 + 변경 3. 엔드포인트 2.

## Constitution Check

| 원칙 | 판정 | 근거 |
|------|------|------|
| I. Test-First | PASS | `NotificationInboxControllerTest` 를 먼저 쓰고(목록 6·읽음 6 시나리오) Red → 구현 → Green. 기존 컨텍스트 재사용. |
| II. Bounded Contexts | PASS | 도메인 변경은 리포지토리 파생 쿼리 2개. 조합은 `api.notification`. |
| III. Layered Dependency | PASS | api → common 단방향. 새 port 없음. |
| IV. Persistence Ownership | PASS | 엔티티 변경 없음. 소유 검증은 쿼리 조건(`findByIdAndMemberId`)으로, 멱등은 엔티티 `markRead` 가 보장. 서비스 `@Transactional` 명시, dirty checking(`save()` 없음). |
| V. Language Policy | N/A | 저장값 그대로. |
| Additional Constraints | PASS | `NotificationResponse` 로 매핑, 엔티티 미노출. |

**Post-design re-check**: 유지.

## Project Structure

### Documentation (this feature)

```text
specs/kb-467-notification-inbox-api/
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/notification-inbox-api.md
└── tasks.md             # /speckit-tasks
```

### Source Code (repository root)

```text
api/src/main/kotlin/com/kbap/api/notification/
├── NotificationInboxApi.kt              # swagger 인터페이스 (신규)
├── NotificationInboxController.kt       # GET /notifications, PATCH /{id}/read (신규)
├── NotificationInboxService.kt          # getRecentNotifications·markRead (신규)
└── NotificationResponse.kt              # id·title·body·createdAt·read (신규)

api/src/main/kotlin/com/kbap/api/core/config/WebConfig.kt      # /api/notifications, /api/notifications/* 보호 + 토큰 등록 게스트 예외
common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt  # NOTIFICATION_NOT_FOUND("NOTIFICATION-002", 404)
common/src/main/kotlin/com/kbap/common/domain/notification/NotificationJpaRepository.kt
    # findByMemberIdAndCreatedAtAfterOrderByIdDesc, findByIdAndMemberId

api/src/test/kotlin/com/kbap/api/notification/
└── NotificationInboxControllerTest.kt   # (신규)
```

**Structure Decision**: 기존 `api.notification` 기능 패키지에 합류(설정·토큰 컨트롤러와 같은 베이스 경로). "Inbox" 는 Setting·Token 과 구분하는 기능명.

## Complexity Tracking

위반 없음.
