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

@IntegrationTest
class AdminFoodContentReviewVectorOutboxTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var reviewService: AdminFoodContentReviewService

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var vectorOutboxRepository: FoodVectorOutboxJpaRepository

    init {
        val namePrefix = "벡터아웃박스검수-"

        fun clear() {
            vectorOutboxRepository.deleteAll()
            foodRepository.findByDisplayNameContainingOrderByIdAsc(namePrefix).forEach {
                foodRepository.delete(it)
            }
        }

        fun saveFood(rawName: String, contentStatus: FoodContentStatus): Food = foodRepository.save(
            Food(
                koreanName = namePrefix + rawName,
                displayName = namePrefix + rawName,
                imageRef = "images/food/$rawName.webp",
                description = "구수한 $rawName",
                longDescription = "$rawName 는 한국의 대표적인 국물 요리다",
                spiciness = 3,
                ingredients = listOf(FoodIngredient("SOYBEAN", 100)),
                contentStatus = contentStatus,
            ),
        )

        fun outboxesOf(food: Food): List<FoodVectorOutbox> =
            vectorOutboxRepository.findAll().filter { it.foodId == food.id }

        given("관리자 콘텐츠 검수 승인") {
            `when`("승인 대기 음식이 조회 가능으로 승인되면") {
                then("같은 트랜잭션에서 벡터 적재 대기 건이 하나 쌓인다") {
                    clear()
                    val target = saveFood("된장찌개", FoodContentStatus.PENDING_REVIEW)

                    reviewService.applyContentReviewResult(target.id, passed = true, reason = null)

                    val outboxes = outboxesOf(target)
                    outboxes.size shouldBe 1
                    outboxes.first().operation shouldBe FoodVectorOutboxOperation.UPSERT
                    outboxes.first().outboxStatus shouldBe FoodVectorOutboxStatus.PENDING
                }
            }

            `when`("이미 조회 가능한 음식에 승인이 다시 도착하면") {
                then("전이가 없으므로 적재 대기 건을 추가로 쌓지 않는다") {
                    clear()
                    val target = saveFood("김치찌개", FoodContentStatus.READY)

                    reviewService.applyContentReviewResult(target.id, passed = true, reason = null)

                    outboxesOf(target) shouldBe emptyList()
                }
            }

            `when`("승인 대기 음식이 반려되면") {
                then("적재 대기 건을 쌓지 않는다 — 조회 가능이 아닌 음식은 검색 후보가 아니다") {
                    clear()
                    val target = saveFood("순두부찌개", FoodContentStatus.PENDING_REVIEW)

                    reviewService.applyContentReviewResult(target.id, passed = false, reason = "설명이 부족함")

                    outboxesOf(target) shouldBe emptyList()
                }
            }
        }
    }
}
