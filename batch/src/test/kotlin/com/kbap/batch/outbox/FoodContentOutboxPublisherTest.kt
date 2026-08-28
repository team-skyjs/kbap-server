package com.kbap.batch.outbox

import com.kbap.batch.BatchIntegrationTest
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.port.mq.FoodContentEvent
import com.kbap.common.port.mq.FoodContentEventPublisher
import com.kbap.common.port.mq.FoodContentPublishResult
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager

@BatchIntegrationTest
class FoodContentOutboxPublisherTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        fun clear() {
            outboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        fun saveOutbox(name: String): FoodContentOutbox {
            val food = foodRepository.save(Food.failed(name))
            return outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
        }

        given("대기 아웃박스 발행") {
            `when`("페이지 일부가 실패해도 뒤 페이지가 있으면") {
                then("같은 실행에서는 실패 행을 되돌아가지 않고 다음 페이지까지 처리한다") {
                    clear()
                    val first = saveOutbox("첫국수")
                    val second = saveOutbox("둘국수")
                    val third = saveOutbox("셋국수")
                    val published = mutableListOf<List<FoodContentEvent>>()
                    val publisher = FoodContentEventPublisher { events ->
                        TransactionSynchronizationManager.isActualTransactionActive() shouldBe false
                        published += events
                        if (events.first().outboxId == first.id) {
                            FoodContentPublishResult(
                                succeededOutboxIds = setOf(second.id),
                                failedOutboxIds = setOf(first.id),
                            )
                        } else {
                            FoodContentPublishResult(
                                succeededOutboxIds = setOf(third.id),
                                failedOutboxIds = emptySet(),
                            )
                        }
                    }
                    val outboxPublisher = FoodContentOutboxPublisher(
                        outboxRepository,
                        publisher,
                        transactionManager,
                        pageSize = 2,
                    )

                    val summary = outboxPublisher.publishAll()

                    published.map { page -> page.map { it.outboxId } } shouldBe
                        listOf(listOf(first.id, second.id), listOf(third.id))
                    summary shouldBe FoodContentOutboxPublishSummary(attempted = 3, succeeded = 2, failed = 1)
                    outboxRepository.findById(first.id).orElseThrow().apply {
                        outboxStatus shouldBe FoodContentOutboxStatus.PENDING
                        attempts shouldBe 1
                    }
                    outboxRepository.findById(second.id).orElseThrow().outboxStatus shouldBe
                        FoodContentOutboxStatus.SENT
                    outboxRepository.findById(third.id).orElseThrow().outboxStatus shouldBe
                        FoodContentOutboxStatus.SENT
                }
            }

            `when`("대기 요청이 없으면") {
                then("외부 발행 없이 종료한다") {
                    clear()
                    var calls = 0
                    val outboxPublisher = FoodContentOutboxPublisher(
                        outboxRepository,
                        FoodContentEventPublisher {
                            calls++
                            FoodContentPublishResult(emptySet(), emptySet())
                        },
                        transactionManager,
                        pageSize = 2,
                    )

                    outboxPublisher.publishAll() shouldBe FoodContentOutboxPublishSummary(0, 0, 0)
                    calls shouldBe 0
                }
            }
        }
    }
}
