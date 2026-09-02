package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import javax.sql.DataSource

@IntegrationTest
class AdminFoodServiceVectorOutboxTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var service: AdminFoodService

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    @Autowired
    private lateinit var dataSource: DataSource

    init {
        fun clear() {
            dataSource.connection.use { connection ->
                connection.createStatement().use {
                    it.execute("DELETE FROM food_content_outbox")
                    it.execute("DELETE FROM food_vector_outbox")
                    it.execute("DELETE FROM food")
                }
            }
        }

        fun saveFood(koreanName: String, contentStatus: FoodContentStatus): Food = foodRepository.save(
            Food(
                koreanName = koreanName,
                displayName = koreanName,
                imageRef = "images/food/$koreanName.webp",
                description = "구수한 $koreanName",
                longDescription = "$koreanName 는 한국의 대표적인 국물 요리다",
                spiciness = 3,
                ingredients = listOf(FoodIngredient("SOYBEAN", 100)),
                contentStatus = contentStatus,
            ),
        )

        fun updateCommand(food: Food, contentStatus: FoodContentStatus, description: String = "수정한 설명") =
            UpdateFoodCommand(
                koreanName = food.displayName,
                description = description,
                spiciness = food.spiciness,
                contentStatus = contentStatus,
                imageRef = food.imageRef.orEmpty(),
                nameTranslationsJson = "",
                descriptionTranslationsJson = "",
                ingredientsJson = "",
            )

        fun outboxesOf(food: Food): List<FoodVectorOutbox> =
            vectorOutboxRepository.findAll().filter { it.foodId == food.id }

        given("관리자 음식 수정") {
            `when`("수정 결과가 조회 가능이면") {
                then("벡터 적재 대기 건을 쌓는다") {
                    clear()
                    val food = saveFood("김치찌개", FoodContentStatus.READY)

                    service.updateFood(food.id, updateCommand(food, FoodContentStatus.READY)) shouldBe
                        AdminFoodUpdateResult.UPDATED

                    val outboxes = outboxesOf(food)
                    outboxes.size shouldBe 1
                    outboxes.first().operation shouldBe FoodVectorOutboxOperation.UPSERT
                    outboxes.first().outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                }
            }

            `when`("수정으로 조회 가능 승격을 시도하면") {
                then("거절되고 벡터 적재 대기를 쌓지 않는다 — 승격은 검수 승인 몫") {
                    clear()
                    val food = saveFood("갈비탕", FoodContentStatus.PENDING_REVIEW)

                    val result = service.updateFood(food.id, updateCommand(food, FoodContentStatus.READY))

                    result shouldBe AdminFoodUpdateResult.READY_NOT_ALLOWED
                    outboxesOf(food).size shouldBe 0
                }
            }

            `when`("조회 가능이던 음식이 조회 불가로 바뀌면") {
                then("벡터 제거 대기 건을 쌓는다 — 검색 후보에서 빠져야 한다") {
                    clear()
                    val food = saveFood("된장찌개", FoodContentStatus.READY)

                    service.updateFood(food.id, updateCommand(food, FoodContentStatus.PENDING_REVIEW))

                    val outboxes = outboxesOf(food)
                    outboxes.size shouldBe 1
                    outboxes.first().operation shouldBe FoodVectorOutboxOperation.DELETE
                }
            }

            `when`("조회 불가인 음식을 조회 불가인 채로 수정하면") {
                then("대기 건을 쌓지 않는다 — 벡터 저장소와 무관한 변경이다") {
                    clear()
                    val food = saveFood("순두부찌개", FoodContentStatus.PENDING_REVIEW)

                    service.updateFood(food.id, updateCommand(food, FoodContentStatus.PENDING_REVIEW))

                    outboxesOf(food) shouldBe emptyList()
                }
            }

            `when`("같은 작업의 대기 건이 이미 있으면") {
                then("중복해서 쌓지 않는다") {
                    clear()
                    val food = saveFood("부대찌개", FoodContentStatus.READY)
                    vectorOutboxRepository.save(FoodVectorOutbox.upsert(food.id))

                    service.updateFood(food.id, updateCommand(food, FoodContentStatus.READY))

                    outboxesOf(food).size shouldBe 1
                }
            }
        }

        given("관리자 음식 삭제") {
            `when`("음식을 삭제하면") {
                then("조회 가능 여부와 무관하게 벡터 제거 대기 건을 쌓는다") {
                    clear()
                    val food = saveFood("청국장", FoodContentStatus.READY)

                    service.deleteFood(food.id) shouldBe AdminFoodDeleteResult.DELETED

                    val outboxes = outboxesOf(food)
                    outboxes.size shouldBe 1
                    outboxes.first().operation shouldBe FoodVectorOutboxOperation.DELETE
                    outboxes.first().outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                }
            }
        }
    }
}
