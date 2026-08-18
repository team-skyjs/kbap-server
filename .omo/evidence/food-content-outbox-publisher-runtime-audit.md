# 음식 콘텐츠 아웃박스 런타임 경계 감사

- 대상 SHA: `500041395719d0f737daf97a00d1d660d2e85435`
- 방식: 읽기 전용 정적 경로·회귀 테스트 대조. 요청에 따라 Gradle은 실행하지 않았다.
- 결론: **PASS (제한적 증거)**

## 가설별 결론

1. **SQS 호출 중 DB transaction이 열린다 — 기각.**
   `FoodContentOutboxPublisher.kt:28-30`의 조회 `TransactionTemplate.execute`가 반환된 뒤 `:36-44`에서 SQS 포트를 호출하고, 결과 기록은 별도 `:45-52` 트랜잭션이다. `FoodContentOutboxPublisherTest.kt:54-56`은 포트 호출 시 `TransactionSynchronizationManager.isActualTransactionActive() == false`를 검증한다.

2. **callback-first COMPLETE가 publish 결과로 SENT/PENDING으로 되돌아간다 — 기각.**
   `FoodContentOutboxJpaRepository.kt:65-75`는 PENDING일 때만 SENT로 전이하고 SENT/COMPLETE를 보존한다. `:83-90`의 실패 기록은 상태를 바꾸지 않는다. `FoodContentOutboxJpaRepositoryTest.kt:170-185`, `:188-203`은 COMPLETE의 늦은 성공/실패 결과 뒤에도 COMPLETE 보존을 검증한다.

3. **중복 callback이 food를 재덮어쓴다 — 기각.**
   `AdminFoodContentIngestService.kt:30-44`는 음식 변경 전에 완료 게이트를 통과시킨다. `:47-60`은 이미 COMPLETE이면 `FOOD-004`를 던져 음식 변경으로 진행하지 않는다. `AdminFoodContentIngestControllerTest.kt:184-208`은 두 번째 요청의 다른 description이 저장되지 않음을 검증한다.

4. **COMPLETE/SENT의 후속 발행이 attempts를 누락한다 — 기각.**
   성공과 실패 SQL 모두 `FoodContentOutboxJpaRepository.kt:73-75`, `:87-89`에서 PENDING/SENT/COMPLETE를 대상으로 attempts를 증가시킨다. `FoodContentOutboxJpaRepositoryTest.kt:206-237`은 선행 성공으로 SENT가 된 행에 후속 성공 및 실패를 기록해 각 attempts=2를 확인한다.

5. **성공 후 sentAt이 재작성된다 — 기각.**
   `FoodContentOutboxJpaRepository.kt:66-72`는 `COALESCE(sent_at, CURRENT_TIMESTAMP(6))`를 사용한다. `FoodContentOutboxJpaRepositoryTest.kt:220-237`은 선행 sentAt을 저장한 뒤 후속 성공/실패 결과 뒤에도 같은 시각임을 검증한다.

6. **활성 빈 queue URL이 늦게 실패한다 — 기각.**
   `FoodContentOutboxBatchConfig.kt:26-45`는 활성 조건에서 publisher를 조립하고, `SqsFoodContentEventPublisher.kt:17-19`의 생성자 `require`가 blank URL을 즉시 거절한다. `SqsFoodContentEventPublisherTest.kt:33-39`이 이 구성 단계 예외를 검증한다.

## 잔여 런타임 위험

- 실제 MySQL에서 두 HTTP 콜백 또는 두 batch 인스턴스를 동시에 경쟁시킨 E2E는 직접 실행되지 않았다. 조건부 UPDATE와 DB 행 잠금 의미에 의존한다.
- 전체 Gradle 통과는 부모가 전달한 보조 근거이며, 이 감사에 독립 실행 로그 artifact는 제공되지 않았다. 지시상 재실행하지 않았다.

## 품질 관점

`programming` 및 `remove-ai-slops` 관점을 적용했다. 새 테스트는 상태 전이·발행 이력의 관찰 가능한 계약을 검증하며, 삭제 전용·프롬프트·상수 미러링 테스트나 목적 없는 파싱/정규화는 발견하지 못했다.
