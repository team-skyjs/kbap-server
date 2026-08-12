package com.kbap.api.admin

import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentOutbox
import com.kbap.common.domain.food.model.FoodContentOutboxStatus
import com.kbap.common.domain.food.model.FoodContentStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest
@Import(MySqlContainerConfig::class)
class AdminFoodRecollectTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adminFoodService: AdminFoodService

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var outboxRepository: FoodContentOutboxJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        val namePrefix = "재수집-"

        fun clearFoods(): Unit =
            dataSource.connection.use { c ->
                c.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM image_batch_item")
                    it.execute("DELETE FROM image_batch")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food")
                }
            }

        fun saveFood(rawName: String, contentStatus: FoodContentStatus = FoodContentStatus.READY): Food =
            foodJpaRepository.save(
                Food(
                    koreanName = namePrefix + rawName,
                    displayName = namePrefix + rawName,
                    description = "구수한 $rawName",
                    spiciness = 1,
                    contentStatus = contentStatus,
                ),
            )

        fun pendingFoodIds(): List<Long> =
            outboxRepository.findByOutboxStatusOrderByIdAsc(FoodContentOutboxStatus.PENDING).map { it.foodId }

        given("조건 일괄 재수집") {
            `when`("검색어에 걸린 음식이 있으면") {
                then("대상마다 대기 요청이 쌓인다") {
                    clearFoods()
                    val first = saveFood("칼국수")
                    val second = saveFood("콩국수")
                    saveFood("비빔밥")

                    val result = adminFoodService.requestRecollect(query = "국수", status = null)

                    result.requested shouldBe 2
                    result.created shouldBe 2
                    result.skipped shouldBe 0
                    pendingFoodIds() shouldBe listOf(first.id, second.id)
                }
            }

            `when`("상태 조건을 함께 주면") {
                then("그 상태의 음식만 대상이 된다") {
                    clearFoods()
                    val failed = saveFood("칼국수", FoodContentStatus.FAILED)
                    saveFood("콩국수", FoodContentStatus.READY)

                    val result = adminFoodService.requestRecollect(query = null, status = FoodContentStatus.FAILED)

                    result.created shouldBe 1
                    pendingFoodIds() shouldBe listOf(failed.id)
                }
            }

            `when`("이미 대기 중인 요청이 있는 음식이면") {
                then("중복 요청을 쌓지 않는다") {
                    clearFoods()
                    val food = saveFood("칼국수")
                    outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))

                    val result = adminFoodService.requestRecollect(query = "칼국수", status = null)

                    result.requested shouldBe 1
                    result.created shouldBe 0
                    result.skipped shouldBe 1
                    pendingFoodIds() shouldBe listOf(food.id)
                }
            }

            `when`("이전 요청이 이미 발행 완료됐으면") {
                then("새 요청을 만든다") {
                    clearFoods()
                    val food = saveFood("칼국수")
                    val sent = outboxRepository.save(FoodContentOutbox.pending(food.id, food.displayName))
                    sent.markSent()
                    outboxRepository.save(sent)

                    val result = adminFoodService.requestRecollect(query = "칼국수", status = null)

                    result.created shouldBe 1
                    pendingFoodIds() shouldBe listOf(food.id)
                }
            }

            `when`("조건에 걸린 음식이 없으면") {
                then("아무 요청도 쌓지 않는다") {
                    clearFoods()
                    saveFood("비빔밥")

                    val result = adminFoodService.requestRecollect(query = "국수", status = null)

                    result.requested shouldBe 0
                    result.created shouldBe 0
                    pendingFoodIds() shouldBe emptyList()
                }
            }

            `when`("대상이 1회 상한을 넘으면") {
                then("실행을 거부하고 아무 요청도 만들지 않는다") {
                    clearFoods()
                    saveFood("칼국수")
                    saveFood("콩국수")

                    val result = adminFoodService.requestRecollect(query = "국수", status = null, max = 1)

                    result.exceeded shouldBe true
                    result.requested shouldBe 2
                    result.created shouldBe 0
                    pendingFoodIds() shouldBe emptyList()
                }
            }
        }
    }
}
