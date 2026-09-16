# Implementation Plan: 알림함 응답에 알림 유형·foodId 추가

**Branch**: `kb-575-notification-inbox-fields` | **Date**: 2026-09-16 | **Spec**: [spec.md](spec.md) | **Jira**: KB-575

## 요약

알림 저장 행(`Notification` 엔티티)에 이미 있는 `type`·`data` 를 알림함 항목 응답(`NotificationResponse`)에 `type`·`foodId` 두 필드로 노출한다. 스키마·마이그레이션·엔드포인트·버전 변경 없음.

## 기술 결정

- **foodId 추출은 엔티티 도메인 메서드** `Notification.foodIdOrNull()` — 유형이 REVIEW_REMINDER 일 때만 `data["foodId"]` 를 정수(Int·Long) 또는 정수 문자열에서 `Long` 으로 해석, 그 외는 null. JSON 컬럼(`Map<String, Any>`)은 Jackson 이 작은 정수를 `Int` 로, 발송 경로에 따라 문자열(`"7"`, `PushDispatchServiceTest` 선례)로도 넣을 수 있어 둘 다 받는다. 규칙이 도메인 값(유형·data)에만 의존하므로 응답 DTO 가 아니라 엔티티가 소유한다(엔티티 = 도메인 모델).
- **`type` 은 `NotificationType.name` 문자열**. 엔티티가 `@Enumerated(STRING)` 이라 폐기 유형 행은 오늘도 로드 시 실패하며, NOTICE 는 KB-468 마이그레이션으로 NEWS 로 전환됐다. Jira 의 "폐기 문자열 그대로 응답해도 된다" 는 허용 조항이라 enum→String 전환 같은 확장은 하지 않는다.
- **API 버전 불변** — 필드 추가만이라 기존 매핑에 그대로 반영. `X-API-Version` 매핑·`WebConfig` 변경 없음.
- **Swagger** — `NotificationResponse` 에 `@Schema` 필드 설명·예시, `NotificationApi` 목록·읽음 처리 description 에 두 필드와 응답 예시 문장 추가. `ExampleObject` 는 쓰지 않는다(DTO 예시로 충분).

## 재사용 vs 신규 대조

| 부품 | 상태 | 비고 |
|------|------|------|
| `Notification.type`·`data` (common.domain.notification.model) | 재사용 | KB-467 저장 행 그대로 읽음 |
| `NotificationResponse.from` (api.notification) | 재사용·확장 | 필드 2개 추가, 호출부 변경 없음 |
| `NotificationService.getRecentNotifications`·`markRead` | 재사용 | 수정 없음 — 같은 `from` 을 거쳐 두 응답이 함께 바뀜 |
| `NotificationController`·`WebConfig` 보호 경로 | 재사용 | 수정 없음 |
| `Notification.foodIdOrNull()` + `DATA_FOOD_ID` | 신규 | 유일한 신규 로직(도메인 메서드 1개) |
| `@Schema` 설명·`NotificationApi` description | 확장 | 문서만 |
| `NotificationInboxTest` | 확장 | 기존 `type` 부재 단언 뒤집기 + 시나리오 3개 |
| `NotificationTest` (common, Spring-free) | 신규 | foodId 해석 경계값 |

## 헌법 게이트

- Test-First: 단위(`NotificationTest`)·통합(`NotificationInboxTest`) 선작성 → 컴파일 실패(Red) 확인 → 구현.
- 모듈 경계: common(엔티티 메서드) ← api(DTO·문서). 역방향 없음. 배치 미관여.
- 컨벤션: Kotlin 주석 없음, 서비스 시그니처 불변, BehaviorSpec 한국어 설명.

## 변경 파일

- `common/src/main/kotlin/com/kbap/common/domain/notification/model/Notification.kt`
- `common/src/test/kotlin/com/kbap/common/domain/notification/model/NotificationTest.kt` (신규)
- `api/src/main/kotlin/com/kbap/api/notification/NotificationResponse.kt`
- `api/src/main/kotlin/com/kbap/api/notification/NotificationApi.kt`
- `api/src/test/kotlin/com/kbap/api/notification/NotificationInboxTest.kt`
