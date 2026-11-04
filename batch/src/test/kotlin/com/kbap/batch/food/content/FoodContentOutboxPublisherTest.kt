package com.kbap.batch.food.content

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

        fun publishedIds(): List<Long> {
            val published = mutableListOf<Long>()
            FoodContentOutboxPublisher(
                outboxRepository,
                { events ->
                    published += events.map { it.outboxId }
                    FoodContentPublishResult(succeededOutboxIds = events.map { it.outboxId }.toSet(), failedOutboxIds = emptySet())
                },
                transactionManager,
                pageSize = 100,
            ).publishAll()
            return published
        }

        given("진행 중 요청만 발행") {
            `when`("같은 음식에 대기 요청이 옛 것과 새 것 둘이면") {
                then("새 요청만 발행한다 — 대체된 옛 요청은 결과가 버려질 유료 호출이다") {
                    clear()
                    val food = foodRepository.save(Food.failed("두번요청국수"))
                    val old = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    val newer = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                    publishedIds() shouldBe listOf(newer.id)

                    outboxRepository.findById(old.id).orElseThrow().outboxStatus shouldBe FoodContentOutboxStatus.PENDING
                }
            }

            `when`("대기 요청의 음식이 삭제됐으면") {
                then("발행하지 않는다 — 콜백이 음식을 못 찾는다") {
                    clear()
                    val food = foodRepository.save(Food.failed("삭제된국수"))
                    outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    foodRepository.save(food.apply { delete() })

                    publishedIds() shouldBe emptyList()
                }
            }
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

        given("반려 사유를 실은 재수집 요청 — S-964") {
            `when`("아웃박스에 반려 사유가 있으면") {
                then("발행 이벤트에 rejectionReason 으로 싣는다(없으면 null)") {
                    clear()
                    val food = foodRepository.save(Food.failed("사유국수"))
                    val withReason = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName, rejectionReason = "설명이 너무 짧음"))
                    val other = foodRepository.save(Food.failed("보통국수"))
                    val plain = outboxRepository.save(FoodContentOutbox.pending(other.id, other.displayName))
                    val published = mutableListOf<FoodContentEvent>()
                    val outboxPublisher = FoodContentOutboxPublisher(
                        outboxRepository,
                        { events ->
                            published += events
                            FoodContentPublishResult(succeededOutboxIds = events.map { it.outboxId }.toSet(), failedOutboxIds = emptySet())
                        },
                        transactionManager,
                        pageSize = 10,
                    )

                    outboxPublisher.publishAll()

                    published.associate { it.outboxId to it.rejectionReason } shouldBe mapOf(withReason.id to "설명이 너무 짧음", plain.id to null)
                }
            }
        }
    }
}
