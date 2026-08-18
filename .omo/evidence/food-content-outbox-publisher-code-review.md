# 음식 콘텐츠 아웃박스 코드 품질 재검토

- 검토 대상: `kbap` `65cbacd29ec9624b9296cfb21b1ac0575d234ba4..500041395719d0f737daf97a00d1d660d2e85435`, `kbap-agenthub` `880a6ff4a226a820eb101c89c1f28e4439be6ace`
- 이전 검토 대상 이후 delta: `d661ac2d636aab8351820e8c50ead3c7ade4990e..500041395719d0f737daf97a00d1d660d2e85435`
- 결론: **CLEAR / APPROVE**
- skill-perspective check: 실행함. `omo:remove-ai-slops`와 `omo:programming`을 다시 직접 읽고 production/test delta에 적용했다. 삭제 전용·tautology·프롬프트 고정·구현 상수 미러링·불필요한 parsing/normalization·untyped escape hatch·불필요한 추상화는 발견하지 못했다. `SqsClient` mock은 외부 SDK 경계의 10건 전송/응답 해석을 검사하는 좁은 mock이라 허위 보장이 아니다.

## CRITICAL

없음.

## HIGH

없음.

## MEDIUM

없음.

## LOW

없음.

## 검증 메모

- `common/src/main/kotlin/com/kbap/common/domain/food/FoodContentOutboxJpaRepository.kt:65-93`의 두 결과 기록 UPDATE는 `PENDING`, `SENT`, callback-first `COMPLETE`에서 모두 실제 시도의 `attempts`를 증가시킨다. 성공 결과는 실제 SQS 성공을 반영해 최초 `sent_at`을 기록하고, `CASE`가 SENT/COMPLETE 상태를 그대로 보존한다. 실패 결과는 `sent_at`을 건드리지 않고 상태를 보존한다. 이는 이번 검토에서 명시된 계약과 일치한다.
- `common/src/test/kotlin/com/kbap/common/domain/food/FoodContentOutboxJpaRepositoryTest.kt:170-238`은 callback-first 성공/실패와, 다른 발행자가 이미 SENT로 만든 행의 늦은 성공/실패 결과를 각각 검증한다. 후자는 attempts=2, 상태 SENT 유지, 최초 sentAt 불변을 검사하므로 `WHERE`에서 SENT가 빠지거나 `COALESCE`가 사라지는 회귀를 구별한다. 관찰 가능한 경쟁 결과를 고정하며 tautology가 아니다.
- `infra/mq/src/main/kotlin/com/kbap/infra/mq/SqsFoodContentEventPublisher.kt:17-19`는 활성 잡의 빈 구성 경계에서 빈 queue URL을 즉시 실패시킨다. `SqsFoodContentEventPublisherTest.kt:33-39`가 이를 검증한다. 실제 `@Value` 키는 `FoodContentOutboxBatchConfig.kt:44`, runtime YAML은 `batch/src/main/resources/application.yml:43-46`, 설계 문서는 `docs/superpowers/specs/2026-08-12-food-content-outbox-publisher-design.md:142-148`로 일치한다.
- `AdminFoodContentIngestControllerTest.kt:184-208`은 두 번째 콜백에 다른 description을 보내고 FOOD-004 뒤 최초 description 유지까지 확인한다. `AdminFoodContentIngestService.kt:47-60`의 COMPLETE 게이트가 음식 적용보다 선행하므로 중복 callback은 food를 덮지 않는다.
- 조건부 `(outboxId, foodId)` 완료 UPDATE, PENDING/SENT→COMPLETE, 단일 `@Transactional` 롤백 경계, DB 트랜잭션 밖 SQS 호출, 10건 분할·부분 실패·예외 후 진행·단조 cursor, 기본 비활성/활성 Job 구성, MySQL ENUM migration을 정적으로 대조했다. 새 Kotlin 주석은 없다.
- 요청에 따라 테스트는 실행하지 않았다.

## 승인 전 차단 사항

없음.
