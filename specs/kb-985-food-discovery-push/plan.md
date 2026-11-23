# Implementation Plan: 음식 발견 푸시 (KB-985)

**Branch**: `feat/kb985-food-discovery-push` | **Date**: 2026-11-04 | **Spec**: [spec.md](spec.md)

## Summary

배치 잡 1개(`foodDiscoveryPushJob`, cron `0 0 9,12,15,18 * * *`)가 스캔 제안 리더를 파라미터화해 재사용하고, 라이터가 `open()` 에서 후보 풀을 1회 적재·회피 집합별로 메모이즈해 회원마다 음식 하나를 고른 뒤 음식별로 묶어 `PushHandler.send` 한다. 발송 알림 행엔 `food_id` 를 남기고 알림함 응답 `foodId` 로 노출한다.

## Technical Context

Kotlin 2.3 · Spring Boot 4.1 · Spring Batch · JPA/MySQL · Flyway · Micrometer · Kotest BehaviorSpec + Testcontainers.
batch 모듈은 common 만 의존 → 판정·조회는 common 엔티티·리포지토리로만.

## Constitution Check

- 모듈 경계: 신규 코드는 batch(`notification.discovery`)·common(domain.notification/bookmark) 에만. api 는 응답 설명·`foodIdOrNull` 의미만.
- Flyway timestamp 버전 신규 1개, 기존 파일 무수정. Kotlin 주석 없음. 격리수준 지정 없음.
- 계약 엔드포인트(알림함)·집계 쿼리(`findFoodIdsSince`)에 통합 테스트.

## Project Structure (변경)

```text
api/src/main/resources/db/migration/V2026.11.04.*__notification_food_id.sql
api/src/main/kotlin/com/kbap/api/notification/{NotificationResponse,NotificationApi}.kt
common/.../notification/model/{NotificationType,Notification}.kt
common/.../notification/{PushTargetResolver,PushDispatchService,NotificationJpaRepository}.kt
common/.../bookmark/BookmarkJpaRepository.kt
common/src/main/resources/messages/push_*.properties (10)
batch/.../notification/PushDispatchMetric.kt
batch/.../notification/suggestion/ScanSuggestionMemberIdReader.kt (파라미터화)
batch/.../notification/discovery/{FoodDiscoverySendWindow,FoodDiscoveryCandidates,FoodDiscoveryPushWriter,FoodDiscoveryPushBatchConfig}.kt
batch/.../notification/receipt/PushReceiptSyncBatchConfig.kt, trigger/scheduler/BatchJobScheduler.kt
```

## Design decisions

- 리더: `ScanSuggestionMemberIdReader(type, slotStart)` 기본값 인자 → 기존 호출 무변경.
- 라이터 `ItemStreamWriter`: `open()` 에서 풀·메모·야간 판정 리셋(잡 빈 재사용).
- 음식별 그룹 발송: `argsByLang`·`data` 가 음식에만 의존하므로 회원 묶음 1회 send.
- `no_candidate` 는 새 메트릭 이름(태그 `slot`). `kbap.push.dispatch` 태그 키 집합은 그대로.
