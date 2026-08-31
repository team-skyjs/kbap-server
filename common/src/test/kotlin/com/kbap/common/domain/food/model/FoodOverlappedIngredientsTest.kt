package com.kbap.common.domain.food.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

class FoodOverlappedIngredientsTest : BehaviorSpec({
    fun doenjangStew() = Food(
        koreanName = "된장찌개",
        description = "구수한 된장찌개",
        imageRef = "doenjang.png",
        spiciness = 3,
        ingredients = listOf(
            FoodIngredient(code = "SOY", inclusionPercent = 100),
            FoodIngredient(code = "WHEAT", inclusionPercent = 80),
            FoodIngredient(code = "CLAM", inclusionPercent = 5),
        ),
    )

    given("사용자 회피 성분과 음식 성분의 교집합을 구한다") {
        `when`("회피 성분 일부(SOY·CLAM)가 음식 성분과 겹치면") {
            then("겹친 성분을 위험도 판정 가능한 원본 성분으로 반환한다") {
                val overlapped = doenjangStew().overlappedIngredients(setOf("SOY", "CLAM", "MILK"))

                overlapped.map { it.code } shouldContainExactly listOf("SOY", "CLAM")
                overlapped.first { it.code == "SOY" }.riskLevel() shouldBe RiskLevel.DANGER
                overlapped.first { it.code == "CLAM" }.riskLevel() shouldBe RiskLevel.SAFE
            }
        }
        `when`("회피 성분이 음식 성분과 하나도 겹치지 않으면") {
            then("빈 목록이다") {
                doenjangStew().overlappedIngredients(setOf("MILK", "EGG")) shouldBe emptyList()
            }
        }
        `when`("회피 성분 목록이 비어 있으면") {
            then("빈 목록이다") {
                doenjangStew().overlappedIngredients(emptySet()) shouldBe emptyList()
            }
        }
    }

    given("READY 인데 성분이 미조사(null)인 비정상 상태") {
        `when`("겹침을 판정하면") {
            then("판정 불가라 빈 목록이다") {
                val unassessed = Food(
                    koreanName = "된장찌개",
                    description = "구수한 된장찌개",
                    imageRef = "doenjang.png",
                    spiciness = 3,
                    ingredients = null,
                    contentStatus = FoodContentStatus.READY,
                )

                unassessed.overlappedIngredients(setOf("SOY")) shouldBe emptyList()
            }
        }
    }

    given("아직 READY 가 아닌 음식") {
        `when`("겹침을 판정하면") {
            then("판정 불가라 빈 목록이다") {
                val incomplete = Food(
                    koreanName = "된장찌개",
                    description = "구수한 된장찌개",
                    spiciness = 3,
                    ingredients = listOf(FoodIngredient(code = "SOY", inclusionPercent = 100)),
                    contentStatus = FoodContentStatus.PENDING_REVIEW,
                )

                incomplete.overlappedIngredients(setOf("SOY")) shouldBe emptyList()
            }
        }
    }
})
