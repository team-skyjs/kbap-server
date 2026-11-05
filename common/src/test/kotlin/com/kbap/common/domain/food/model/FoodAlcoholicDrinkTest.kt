package com.kbap.common.domain.food.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodAlcoholicDrinkTest : BehaviorSpec({
    given("술 공용 이미지 판정(S-972)") {
        `when`("ALCOHOL 재료 함량이 60% 이상이면") {
            then("술 음료다") {
                Food(koreanName = "소주", ingredients = listOf(FoodIngredient("ALCOHOL", 60))).isAlcoholicDrink() shouldBe true
                Food(koreanName = "막걸리", ingredients = listOf(FoodIngredient("RICE", 30), FoodIngredient("ALCOHOL", 90))).isAlcoholicDrink() shouldBe true
            }
        }
        `when`("ALCOHOL 이 60% 미만이거나 없으면") {
            then("술 음료가 아니다 — 요리에 들어간 술·맛술은 대상이 아니다") {
                Food(koreanName = "술찜", ingredients = listOf(FoodIngredient("ALCOHOL", 59))).isAlcoholicDrink() shouldBe false
                Food(koreanName = "불고기", ingredients = listOf(FoodIngredient("MIRIN", 80))).isAlcoholicDrink() shouldBe false
                Food(koreanName = "재료 없음", ingredients = null).isAlcoholicDrink() shouldBe false
            }
        }
    }
})
