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

@SpringBootTest(classes = [FoodTestApp::class])
@Import(MySqlContainerConfig::class)
class FoodContentOutboxJpaRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

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
        }
    }
}
