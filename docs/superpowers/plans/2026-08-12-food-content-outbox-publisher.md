# 음식 콘텐츠 아웃박스 발행 구현 계획

> **에이전트 작업자 필수 스킬:** 이 계획은 superpowers:executing-plans로 항목별 실행한다. 각 단계는 체크박스로 추적한다.

**목표:** PENDING 음식 콘텐츠 아웃박스를 Spring Batch에서 SQS로 발행하고, outboxId를 왕복한 콜백이 같은 요청을 한 번만 음식에 반영하도록 만든다.

**아키텍처:** common에 메시지 발행 포트와 아웃박스 원자 갱신 쿼리를 두고, 신규 infra:mq가 AWS SQS 배치 API를 구현한다. batch는 짧은 DB 트랜잭션 사이에서 외부 발행을 수행하며, api는 아웃박스 COMPLETE 전이와 음식 변경을 하나의 트랜잭션으로 묶는다.

**기술 스택:** Kotlin 2.3.21, Java 21, Spring Boot 4.1.0, Spring Batch, Spring Data JPA, MySQL 8, Flyway, AWS SDK for Java v2 2.48.0, Kotest 5.9.1

## 전역 제약

- Kotlin 소스에는 주석을 추가하지 않는다.
- 테스트를 먼저 작성하고 예상한 이유로 실패하는 것을 확인한 뒤 구현한다.
- 외부 SQS 호출을 DB 트랜잭션 안에서 실행하지 않는다.
- SQS 표준 큐와 SendMessageBatch 최대 10건 제한을 사용한다.
- 메시지 본문은 outboxId, foodId, scannedName 세 필드만 포함한다.
- 콜백 성공·실패 본문 모두 양수 outboxId와 foodId를 필수로 받는다.
- PENDING -> COMPLETE와 SENT -> COMPLETE를 모두 허용한다.
- 중복 완료는 HTTP 409와 안정 코드 FOOD-004를 반환한다.
- 아웃박스 완료와 음식 변경은 하나의 트랜잭션으로 커밋하거나 롤백한다.
- 랭체인 코드는 이 작업에서 수정하지 않는다.
- 문서와 커밋 메시지는 한국어로 작성한다.

---

### 작업 1: 아웃박스 COMPLETE 상태와 원자 갱신 영속성

**파일:**

- 수정: common/src/main/kotlin/com/kbap/common/domain/food/model/FoodContentOutboxStatus.kt
- 수정: common/src/main/kotlin/com/kbap/common/domain/food/model/FoodContentOutbox.kt
- 수정: common/src/main/kotlin/com/kbap/common/domain/food/FoodContentOutboxJpaRepository.kt
- 수정: common/src/test/kotlin/com/kbap/common/domain/food/FoodContentOutboxJpaRepositoryTest.kt
- 생성: api/src/main/resources/db/migration/V2026.08.12.00.00.00__food_content_outbox_complete.sql

**제공 인터페이스:**

~~~kotlin
fun findPendingAfterId(afterId: Long, limit: Int): List<FoodContentOutbox>
fun completeIfProcessable(outboxId: Long, foodId: Long): Int
fun existsByIdAndFoodIdAndOutboxStatus(
    id: Long,
    foodId: Long,
    outboxStatus: FoodContentOutboxStatus,
): Boolean
fun recordPublishSucceeded(ids: Collection<Long>, sentAt: LocalDateTime): Int
fun recordPublishFailed(ids: Collection<Long>): Int
~~~

- [ ] **1.1 실패하는 저장소 테스트 작성**

FoodContentOutboxJpaRepositoryTest에 다음 경계를 추가한다.

- PENDING과 SENT는 completeIfProcessable 결과 1, 저장 상태 COMPLETE
- 같은 outboxId를 두 번째 완료하면 결과 0
- foodId가 다르면 결과 0
- 커서 조회는 afterId보다 큰 PENDING만 ID 오름차순으로 limit개 반환
- 성공 발행 기록은 attempts 증가, 최초 sentAt 기록, PENDING만 SENT 전환
- 이미 COMPLETE인 성공 발행 기록은 attempts와 sentAt만 변경하고 COMPLETE 유지
- 실패 발행 기록은 attempts만 증가하고 상태·sentAt 불변
- 두 스레드가 같은 행을 동시에 완료하면 결과가 정확히 1과 0

- [ ] **1.2 Red 확인**

~~~bash
./gradlew :common:test --tests '*FoodContentOutboxJpaRepositoryTest'
~~~

예상: COMPLETE enum과 신규 저장소 메서드가 없어 컴파일 실패.

- [ ] **1.3 마이그레이션과 엔티티 상태 구현**

~~~sql
ALTER TABLE food_content_outbox
    MODIFY COLUMN outbox_status ENUM ('PENDING','SENT','COMPLETE') NOT NULL DEFAULT 'PENDING';
~~~

FoodContentOutboxStatus와 엔티티 columnDefinition도 같은 세 값으로 맞춘다.

- [ ] **1.4 저장소 원자 쿼리 구현**

완료 게이트는 활성 행과 두 식별자가 모두 일치할 때만 갱신한다.

~~~sql
UPDATE food_content_outbox
SET outbox_status = 'COMPLETE', updated_at = NOW(6)
WHERE id = :outboxId
  AND food_id = :foodId
  AND outbox_status IN ('PENDING', 'SENT')
  AND status = 'ACTIVE'
~~~

성공 발행 기록은 완료 상태를 덮지 않는다.

~~~sql
UPDATE food_content_outbox
SET attempts = attempts + 1,
    sent_at = COALESCE(sent_at, :sentAt),
    outbox_status = CASE WHEN outbox_status = 'PENDING' THEN 'SENT' ELSE outbox_status END,
    updated_at = NOW(6)
WHERE id IN (:ids)
  AND status = 'ACTIVE'
~~~

실패 기록은 상태를 바꾸지 않고 attempts만 증가시킨다. 호출자는 빈 ID 집합일 때 갱신 메서드를 부르지 않는다.

- [ ] **1.5 Green 확인**

~~~bash
./gradlew :common:test --tests '*FoodContentOutboxJpaRepositoryTest' --tests '*FoodContentOutboxTest'
~~~

- [ ] **1.6 커밋**

~~~bash
git add common/src/main/kotlin/com/kbap/common/domain/food common/src/test/kotlin/com/kbap/common/domain/food api/src/main/resources/db/migration
git commit -m "feat: 음식 콘텐츠 아웃박스 완료 상태 추가"
~~~

### 작업 2: 콜백 COMPLETE 게이트와 FOOD-004 계약

**파일:**

- 수정: common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt
- 수정: api/src/main/kotlin/com/kbap/api/admin/AdminFoodContentIngestRequest.kt
- 수정: api/src/main/kotlin/com/kbap/api/admin/AdminFoodContentIngestController.kt
- 수정: api/src/main/kotlin/com/kbap/api/admin/AdminFoodContentIngestService.kt
- 수정: api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentIngestTestSupport.kt
- 수정: api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentIngestControllerTest.kt
- 수정: api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentIngestFailureTest.kt
- 수정: api/src/test/kotlin/com/kbap/api/admin/AdminFoodContentIngestValidationTest.kt

**인터페이스:**

~~~kotlin
@field:NotNull
@field:Positive
val outboxId: Long? = null

FOOD_CONTENT_REQUEST_ALREADY_COMPLETED(
    "FOOD-004",
    409,
    "이미 처리된 음식 콘텐츠 수집 요청입니다",
)
~~~

- [ ] **2.1 실패하는 API 테스트 작성**

passedBody와 failedBody를 각각 (outboxId, foodId, 나머지 필드) 시그니처로 바꾼다. 모든 기존 적재 테스트는 음식과 함께 FoodContentOutbox.pending을 저장하고 해당 ID를 본문에 넣는다.

신규 경계:

- 첫 콜백 200, 같은 본문의 두 번째 콜백 409과 FOOD-004
- outboxId 누락·0은 400 COMMON-002
- 없는 outboxId와 outboxId/foodId 불일치는 400 COMMON-002
- 유효 아웃박스의 음식이 소프트삭제되어 조회되지 않으면 FOOD-001이 발생하고 아웃박스 상태가 PENDING 또는 SENT로 롤백
- 중복 요청은 첫 요청이 만든 음식 값을 변경하지 않음

- [ ] **2.2 Red 확인**

~~~bash
./gradlew :api:test --tests '*AdminFoodContentIngest*'
~~~

예상: outboxId 요청 필드와 완료 게이트가 없어 신규 테스트 실패.

- [ ] **2.3 요청 검증·오류·서비스 게이트 구현**

성공·실패 서비스 메서드에 outboxId를 추가하고 음식 조회 전에 다음 로직을 실행한다.

~~~kotlin
private fun completeOutbox(outboxId: Long, foodId: Long) {
    if (outboxRepository.completeIfProcessable(outboxId, foodId) == 1) return
    if (outboxRepository.existsByIdAndFoodIdAndOutboxStatus(
            outboxId,
            foodId,
            FoodContentOutboxStatus.COMPLETE,
        )
    ) {
        throw BusinessException(ErrorCode.FOOD_CONTENT_REQUEST_ALREADY_COMPLETED)
    }
    throw BusinessException(ErrorCode.INVALID_REQUEST)
}
~~~

기존 @Transactional 경계 안에서 완료 게이트와 음식 변경을 실행해 어느 한쪽이 실패하면 모두 롤백한다.

- [ ] **2.4 Green 확인**

~~~bash
./gradlew :api:test --tests '*AdminFoodContentIngest*'
~~~

- [ ] **2.5 커밋**

~~~bash
git add common/src/main/kotlin/com/kbap/common/core/error/ErrorCode.kt api/src/main/kotlin/com/kbap/api/admin api/src/test/kotlin/com/kbap/api/admin
git commit -m "feat: 음식 콘텐츠 콜백 중복 완료 차단"
~~~

### 작업 3: 공통 발행 포트와 SQS 어댑터

**파일:**

- 수정: settings.gradle.kts
- 수정: gradle/libs.versions.toml
- 생성: common/src/main/kotlin/com/kbap/common/port/mq/FoodContentEvent.kt
- 생성: common/src/main/kotlin/com/kbap/common/port/mq/FoodContentEventPublisher.kt
- 생성: infra/mq/build.gradle.kts
- 생성: infra/mq/src/main/kotlin/com/kbap/infra/mq/SqsFoodContentEventPublisher.kt
- 생성: infra/mq/src/test/kotlin/com/kbap/infra/mq/SqsFoodContentEventPublisherTest.kt

**인터페이스:**

~~~kotlin
data class FoodContentEvent(
    val outboxId: Long,
    val foodId: Long,
    val scannedName: String,
)

data class FoodContentPublishResult(
    val succeededOutboxIds: Set<Long>,
    val failedOutboxIds: Set<Long>,
)

interface FoodContentEventPublisher {
    fun publish(events: List<FoodContentEvent>): FoodContentPublishResult
}
~~~

- [ ] **3.1 테스트를 실행할 모듈 골격과 포트 작성**

settings와 버전 카탈로그, infra/mq/build.gradle.kts를 먼저 연결하고 세 공통 포트 타입만 작성한다. 어댑터 구현은 아직 만들지 않는다.

- [ ] **3.2 실패하는 어댑터 테스트 작성**

JDK 동적 프록시 SqsClient로 요청을 수집하고 다음을 검증한다.

- 23건 입력은 10·10·3건 요청
- 본문 JSON은 outboxId, foodId, scannedName만 보유
- 엔트리 ID는 outboxId 문자열
- AWS 부분 성공·실패 응답이 정확한 ID 집합으로 변환
- 응답에서 누락된 엔트리는 실패
- 전송 예외가 난 청크는 전부 실패로 기록하고 다음 청크는 계속 처리

- [ ] **3.3 Red 확인**

~~~bash
./gradlew :infra:mq:test
~~~

예상: SqsFoodContentEventPublisher가 없어 테스트 컴파일 실패.

- [ ] **3.4 SQS 어댑터 구현**

모듈은 common, AWS BOM, SQS, Jackson Kotlin, Spring Context, SLF4J를 의존한다.

SqsFoodContentEventPublisher는 events.chunked(10), Jackson 직렬화, 부분 결과 합산을 수행한다. create(queueUrl)는 SqsClient.create()를 사용해 기본 자격 증명·리전 체인을 따른다.

- [ ] **3.5 Green 확인**

~~~bash
./gradlew :infra:mq:test
~~~

- [ ] **3.6 커밋**

~~~bash
git add settings.gradle.kts gradle/libs.versions.toml common/src/main/kotlin/com/kbap/common/port/mq infra/mq
git commit -m "feat: 음식 콘텐츠 SQS 발행 어댑터 추가"
~~~

### 작업 4: Spring Batch 아웃박스 발행 잡

**파일:**

- 수정: batch/build.gradle.kts
- 수정: batch/src/main/resources/application.yml
- 생성: batch/src/main/kotlin/com/kbap/batch/content/FoodContentOutboxPublisher.kt
- 생성: batch/src/main/kotlin/com/kbap/batch/content/FoodContentOutboxPublishJobConfig.kt
- 생성: batch/src/test/kotlin/com/kbap/batch/content/FoodContentOutboxPublisherTest.kt
- 생성: batch/src/test/kotlin/com/kbap/batch/content/FoodContentOutboxPublishJobTest.kt

**인터페이스:**

~~~kotlin
class FoodContentOutboxPublisher(
    private val outboxRepository: FoodContentOutboxJpaRepository,
    private val eventPublisher: FoodContentEventPublisher,
    transactionManager: PlatformTransactionManager,
    private val pageSize: Int,
) {
    fun publishAll()
}
~~~

- [ ] **4.1 실패하는 발행 서비스 테스트 작성**

MySQL Testcontainer와 가짜 FoodContentEventPublisher를 사용해 검증한다.

- PENDING 23건, 페이지 크기 10이면 10·10·3건 호출
- SENT와 COMPLETE는 발행하지 않음
- 이벤트 scannedName은 아웃박스 displayName 스냅샷
- 성공 행 SENT, 실패 행 PENDING, 두 종류 모두 attempts 증가
- 실패 행은 같은 publishAll 실행에서 한 번만 시도
- 가짜 publisher가 반환 전에 행을 COMPLETE로 만들면 결과 저장 뒤에도 COMPLETE 유지

- [ ] **4.2 Red 확인**

~~~bash
./gradlew :batch:test --tests '*FoodContentOutboxPublisherTest'
~~~

- [ ] **4.3 발행 서비스 구현**

읽기 TransactionTemplate은 readOnly=true로, 결과 저장은 별도 쓰기 템플릿으로 구성한다. 커서는 0에서 시작해 페이지 마지막 ID로 전진한다. 포트 결과에서 누락된 요청 ID는 실패 집합에 합친다. SQS 호출은 두 트랜잭션 사이에서 실행한다.

- [ ] **4.4 실패하는 잡 구성 테스트 작성**

spring.batch.job.enabled=true, 가짜 큐 URL, 페이지 크기 10으로 컨텍스트를 띄워 잡 이름 foodContentOutboxPublishJob과 COMPLETED 종료를 확인한다. 빈 큐 URL은 구성 실패를 단언한다.

- [ ] **4.5 잡과 설정 구현**

FoodContentOutboxPublishJobConfig는 spring.batch.job.enabled가 true일 때만 활성화하고 다음을 조립한다. 기본 false인 컨텍스트 로드에서는 큐 URL과 AWS 클라이언트를 요구하지 않는다.

- SqsFoodContentEventPublisher.create(queueUrl)
- FoodContentOutboxPublisher
- ResourcelessTransactionManager를 쓰는 tasklet step
- RunIdIncrementer를 쓰는 foodContentOutboxPublishJob

application.yml에는 FOOD_CONTENT_QUEUE_URL 환경변수와 기본 페이지 크기 100을 연결한다. 큐 URL은 잡 조립 시 blank를 거절한다.

- [ ] **4.6 Green 확인**

~~~bash
./gradlew :batch:test --tests '*FoodContentOutboxPublisherTest' --tests '*FoodContentOutboxPublishJobTest'
~~~

- [ ] **4.7 커밋**

~~~bash
git add batch
git commit -m "feat: 음식 콘텐츠 아웃박스 발행 배치 추가"
~~~

### 작업 5: 저장소 계약 문서와 전체 검증

**파일:**

- 수정: specs/kb-302-langchain-food-ingest/contracts/mq-message.md
- 수정: specs/kb-302-langchain-food-ingest/contracts/ingest-api.md
- 수정: specs/kb-302-langchain-food-ingest/data-model.md
- 수정: specs/kb-302-langchain-food-ingest/research.md
- 수정: specs/kb-302-langchain-food-ingest/quickstart.md
- 수정: docs/superpowers/specs/2026-08-12-food-content-outbox-publisher-design.md
- 생성: docs/superpowers/plans/2026-08-12-food-content-outbox-publisher.md

- [ ] **5.1 폐기된 계약 교체**

다음 오래된 결정을 모두 새 계약으로 바꾼다.

- outboxId를 SQS 본문에 넣지 않는다는 결정
- SQS 메시지 필드명이 displayName이라는 계약
- 중복 콜백도 200이라는 계약
- 아웃박스 상태가 PENDING·SENT 두 개뿐이라는 설명

새 문서는 SQS 세 필드, 콜백 outboxId, COMPLETE, FOOD-004, 랭체인 ACK 예외를 모두 한국어로 설명한다.

- [ ] **5.2 문서 일관성 검사**

~~~bash
rg -n '본문에는 없음|outboxId.*넣지|"displayName".*메시지' specs/kb-302-langchain-food-ingest docs/superpowers
git diff --check
~~~

- [ ] **5.3 대상 테스트와 전체 빌드**

~~~bash
./gradlew :common:test :infra:mq:test :batch:test :api:test
./gradlew clean build
~~~

- [ ] **5.4 수동 API 시나리오**

로컬 MySQL에 음식과 PENDING 아웃박스 한 건을 준비하고 같은 콜백을 두 번 보낸다.

- 첫 요청: HTTP 200, outbox COMPLETE, 음식 변경
- 두 번째 요청: HTTP 409, code FOOD-004, 음식 불변

- [ ] **5.5 커밋**

~~~bash
git add specs/kb-302-langchain-food-ingest docs/superpowers
git commit -m "docs: 음식 콘텐츠 발행과 콜백 계약 갱신"
~~~

### 작업 6: agenthub 공식 계약 문서와 PR

**파일:**

- 수정: kbap-agenthub/wiki/langchain-food-ingest-contract.md
- 수정: kbap-agenthub/wiki/food-content-pipeline.md
- 확인: kbap-agenthub/wiki/INDEX.md

- [ ] **6.1 별도 워크트리와 브랜치 생성**

kbap-agenthub 최신 기본 브랜치에서 codex/food-content-outbox-contract 브랜치를 별도 워크트리로 만든다. 기존 사용자 변경은 건드리지 않는다.

- [ ] **6.2 공식 계약 문서 갱신**

두 문서에 SQS 입력 필드, 콜백 outboxId, COMPLETE 게이트, 409 FOOD-004, 랭체인의 FOOD-004 ACK, 다른 오류 재시도, 중복 적재와 중복 LLM 비용의 차이를 한국어로 반영한다.

- [ ] **6.3 문서 검증과 커밋**

~~~bash
rg -n 'outboxId|scannedName|COMPLETE|FOOD-004|batchItemFailures' wiki/langchain-food-ingest-contract.md wiki/food-content-pipeline.md
git diff --check
git add wiki/langchain-food-ingest-contract.md wiki/food-content-pipeline.md wiki/INDEX.md
git commit -m "docs: 음식 콘텐츠 아웃박스 완료 계약 추가"
~~~

- [ ] **6.4 두 저장소 PR 생성**

kbap와 kbap-agenthub 브랜치를 각각 push하고 별도 PR을 연다. PR 본문에는 상대 저장소 PR 링크, 계약 변경 요약, 실행한 테스트, 랭체인 별도 구현 필요 사항을 기록한다.
