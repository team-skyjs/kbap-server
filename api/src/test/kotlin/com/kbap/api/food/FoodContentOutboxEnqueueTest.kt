package com.kbap.api.food

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodContentOutboxEnqueueTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodService: FoodService

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

        fun pending(): List<FoodContentOutbox> =
            outboxRepository.findByOutboxStatusOrderByIdAsc(FoodContentOutboxStatus.PENDING)

        given("미보유 음식 등록") {
            `when`("새 음식이 등록되면") {
                then("음식마다 수집 요청이 함께 쌓인다") {
                    clear()

                    val registered = foodService.createIncomplete(
                        mapOf("들깨칼국수" to "들깨 칼국수", "콩국수" to "콩국수"),
                    )

                    registered.size shouldBe 2
                    pending().map { it.displayName }.sorted() shouldBe listOf("들깨 칼국수", "콩국수")
                    pending().map { it.foodId }.sorted() shouldBe registered.values.map { it.id }.sorted()
                }
            }

            `when`("이미 대기 요청이 있는 음식이 다시 등록되면") {
                then("요청을 중복해서 쌓지 않는다") {
                    clear()
                    val first = foodService.createIncomplete(mapOf("들깨칼국수" to "들깨 칼국수"))

                    foodService.createIncomplete(mapOf("들깨칼국수" to "들깨 칼국수"))

                    pending().map { it.foodId } shouldBe listOf(first.getValue("들깨칼국수").id)
                }
            }

            `when`("등록 트랜잭션이 롤백되면") {
                then("음식도 수집 요청도 남지 않는다") {
                    clear()

                    runCatching {
                        transactionTemplate.executeWithoutResult {
                            foodService.createIncomplete(mapOf("들깨칼국수" to "들깨 칼국수"))
                            throw IllegalStateException("스캔 처리 실패")
                        }
                    }

                    foodRepository.findByKoreanNameIn(setOf("들깨칼국수")) shouldBe emptyList()
                    pending() shouldBe emptyList()
                }
            }
        }
    }
}
