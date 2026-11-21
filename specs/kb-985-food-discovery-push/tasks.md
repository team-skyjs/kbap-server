# Tasks: 음식 발견 푸시 (KB-985)

## Phase 1: Foundational (계약·스키마)
- [x] T001 [R-01] `NotificationType.FOOD_DISCOVERY(true)` + channelId news, `PushTargetResolver.toggledOn` news 분기
- [x] T002 [R-08] 마이그레이션 `notification.food_id` + 인덱스, `Notification.foodId`, `foodIdOrNull()` = 컬럼
- [x] T003 [R-08] `PushDispatchService.prepare` 가 `data.foodId` 를 `notification.food_id` 로 저장
- [x] T004 [R-02] 10개 로케일 `push.food_discovery.1.title/body`
- [x] T005 [R-06·R-07] `NotificationJpaRepository.findFoodIdsSince`, `BookmarkJpaRepository.findByMemberIdIn`
- [x] T006 [R-12] `NotificationResponse`·`NotificationApi` Swagger 설명

## Phase 2: US1·US2 후보 판정
- [x] T007 [R-03·R-04·R-05] `FoodDiscoveryCandidates`(적재·메모·pick) + 순수 테스트

## Phase 3: US3 잡
- [x] T008 [R-09] `FoodDiscoverySendWindow` + 테스트
- [x] T009 [R-09] `ScanSuggestionMemberIdReader` 파라미터화
- [x] T010 [R-09·R-10] `FoodDiscoveryPushWriter`, `FoodDiscoveryPushBatchConfig`, 스케줄러, `no_candidate` 메트릭
- [x] T011 [R-11] 마케팅 영수증 cron 09~20시 10분

## Phase 4: 테스트
- [x] T012 common 테스트: 렌더러(ko 렌더·10 로케일 키), 타깃 리졸버, 디스패치 food_id, 리포지토리(89/91일·soft delete·type·북마크)
- [x] T013 batch 잡 테스트: 발송·food_id·data / 재실행 0 / 21:30 0 / 후보 0 + 메트릭 / 음식별 묶음
- [x] T014 api `NotificationInboxTest`: FOOD_DISCOVERY foodId, REVIEW_REMINDER null
- [x] T015 `./gradlew build` 그린
