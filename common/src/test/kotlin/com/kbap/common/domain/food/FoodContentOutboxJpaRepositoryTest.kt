package com.kbap.common.domain.food

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest(classes = [FoodTestApp::class])
@Import(MySqlContainerConfig::class)
class FoodContentOutboxJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var transactionTemplate: TransactionTemplate

    init {
        fun clear() {
            outboxRepository.deleteAll()
            foodRepository.deleteAll()
        }

        fun saveFood(koreanName: String): Food = foodRepository.save(Food.failed(koreanName))

        given("대기 요청 확인") {
            `when`("같은 음식에 대기 요청이 있으면") {
                clear()
                val food = saveFood("들깨칼국수")
                outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                then("존재로 판정한다") {
                    outboxRepository.existsByFoodIdAndOutboxStatus(food.id, FoodContentOutboxStatus.PENDING) shouldBe true
                }
            }

            `when`("대기 요청이 발행 완료로 바뀌면") {
                clear()
                val food = saveFood("콩국수")
                val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                outbox.markSent()
                outboxRepository.save(outbox)

                then("대기 요청은 없는 것으로 판정해 재수집을 막지 않는다") {
                    outboxRepository.existsByFoodIdAndOutboxStatus(food.id, FoodContentOutboxStatus.PENDING) shouldBe false
                }
            }
        }

        given("발행 대상 조회") {
            `when`("대기 요청과 발행 완료 요청이 섞여 있으면") {
                clear()
                val first = saveFood("들깨칼국수")
                val second = saveFood("콩국수")
                val third = saveFood("잔치국수")
                outboxRepository.save(FoodContentOutbox.pending(first.id, first.displayName))
                val sent = outboxRepository.save(FoodContentOutbox.pending(second.id, second.displayName))
                sent.markSent()
                outboxRepository.save(sent)
                outboxRepository.save(FoodContentOutbox.pending(third.id, third.displayName))

                then("대기 요청만 id 오름차순으로 나온다") {
                    val pending = outboxRepository.findByOutboxStatusOrderByIdAsc(FoodContentOutboxStatus.PENDING)

                    pending.map { it.foodId } shouldBe listOf(first.id, third.id)
                }
            }

            `when`("마지막으로 확인한 id와 조회 개수를 지정하면") {
                clear()
                val first = saveFood("들깨칼국수")
                val second = saveFood("콩국수")
                val third = saveFood("잔치국수")
                val firstOutbox = outboxRepository.save(FoodContentOutbox.pending(first.id, first.displayName))
                outboxRepository.save(FoodContentOutbox.pending(second.id, second.displayName))
                outboxRepository.save(FoodContentOutbox.pending(third.id, third.displayName))

                then("커서 다음의 대기 요청만 제한된 개수만큼 나온다") {
                    val pending = outboxRepository.findPendingAfterId(firstOutbox.id, 1)

                    pending.map { it.foodId } shouldBe listOf(second.id)
                }
            }
        }

        given("콜백 처리 완료") {
            `when`("대기 요청을 처음 완료하면") {
                clear()
                val food = saveFood("들깨칼국수")
                val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                then("한 행만 완료 상태로 바뀐다") {
                    transactionTemplate.execute {
                        outboxRepository.completeIfProcessable(outbox.id, food.id)
                    } shouldBe 1
                    outboxRepository.findById(outbox.id).orElseThrow().outboxStatus shouldBe
                        FoodContentOutboxStatus.COMPLETE
                }
            }

            `when`("완료된 요청을 다시 완료하면") {
                clear()
                val food = saveFood("콩국수")
                val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                transactionTemplate.execute { outboxRepository.completeIfProcessable(outbox.id, food.id) }

                then("아무 행도 바뀌지 않고 완료 여부를 확인할 수 있다") {
                    transactionTemplate.execute {
                        outboxRepository.completeIfProcessable(outbox.id, food.id)
                    } shouldBe 0
                    outboxRepository.existsByIdAndFoodIdAndOutboxStatus(
                        outbox.id,
                        food.id,
                        FoodContentOutboxStatus.COMPLETE,
                    ) shouldBe true
                }
            }

            `when`("다른 음식 id로 완료하면") {
                clear()
                val food = saveFood("잔치국수")
                val other = saveFood("비빔국수")
                val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                then("아무 행도 바뀌지 않는다") {
                    transactionTemplate.execute {
                        outboxRepository.completeIfProcessable(outbox.id, other.id)
                    } shouldBe 0
                }
            }
        }

        given("발행 결과 일괄 기록") {
            `when`("일부 요청의 발행에 성공하면") {
                clear()
                val first = saveFood("들깨칼국수")
                val second = saveFood("콩국수")
                val firstOutbox = outboxRepository.save(FoodContentOutbox.pending(first.id, first.displayName))
                val secondOutbox = outboxRepository.save(FoodContentOutbox.pending(second.id, second.displayName))

                then("성공 요청만 시도 횟수와 발행 시각을 기록한다") {
                    transactionTemplate.execute {
                        outboxRepository.recordPublishSucceeded(setOf(firstOutbox.id))
                        outboxRepository.recordPublishFailed(setOf(secondOutbox.id))
                    }

                    val succeeded = outboxRepository.findById(firstOutbox.id).orElseThrow()
                    succeeded.outboxStatus shouldBe FoodContentOutboxStatus.SENT
                    succeeded.attempts shouldBe 1
                    (succeeded.sentAt != null) shouldBe true

                    val failed = outboxRepository.findById(secondOutbox.id).orElseThrow()
                    failed.outboxStatus shouldBe FoodContentOutboxStatus.PENDING
                    failed.attempts shouldBe 1
                    failed.sentAt shouldBe null
                }
            }

            `when`("콜백이 먼저 완료한 요청의 발행 성공을 뒤늦게 기록하면") {
                clear()
                val food = saveFood("칼국수")
                val outbox = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                transactionTemplate.execute { outboxRepository.completeIfProcessable(outbox.id, food.id) }

                then("완료 상태를 되돌리지 않는다") {
                    transactionTemplate.execute {
                        outboxRepository.recordPublishSucceeded(setOf(outbox.id))
                    }

                    val completed = outboxRepository.findById(outbox.id).orElseThrow()
                    completed.outboxStatus shouldBe FoodContentOutboxStatus.COMPLETE
                    completed.attempts shouldBe 1
                    (completed.sentAt != null) shouldBe true
                }
            }
        }
    }
}
