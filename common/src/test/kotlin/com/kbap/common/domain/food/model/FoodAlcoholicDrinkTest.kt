package com.kbap.common.domain.food.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodAlcoholicDrinkTest : BehaviorSpec({
    fun food(name: String, alcohol: Int?) = Food(koreanName = name, ingredients = alcohol?.let { listOf(FoodIngredient("ALCOHOL", it)) } ?: emptyList())

    given("술 공용 이미지 판정(S-972)") {
        `when`("이름이 술 이름으로 끝나고 ALCOHOL 재료가 60 이상이면") {
            then("술 음료다") {
                listOf("소주", "참이슬 소주", "생맥주", "레드와인", "막걸리", "복분자주", "하이볼").forEach { food(it, 95).isAlcoholicDrink() shouldBe true }
            }
        }
        `when`("이름이 술 브랜드명뿐이거나 브랜드명으로 끝나고 ALCOHOL 재료가 60 이상이면 — 예진 지정 브랜드") {
            then("술 음료다") {
                listOf("참이슬", "처음처럼", "테라", "카스", "진로이즈백", "생 칭따오").forEach { food(it, 95).isAlcoholicDrink() shouldBe true }
            }
        }
        `when`("술이 확실히 들어간 요리면 — Codex P2(#586): inclusionPercent 는 비율이 아니라 들어 있을 확률") {
            then("이름이 술 이름으로 끝나지 않으므로 술 음료가 아니다") {
                listOf("맥주수육", "와인삼겹살", "술빵", "와인찜닭", "맥주튀김").forEach { food(it, 95).isAlcoholicDrink() shouldBe false }
            }
        }
        `when`("술 이름이지만 ALCOHOL 이 60 미만이거나 없으면") {
            then("술 음료가 아니다(무알코올 맥주 등 재료 판정을 따른다)") {
                food("무알코올 맥주", 10).isAlcoholicDrink() shouldBe false
                food("소주", null).isAlcoholicDrink() shouldBe false
            }
        }
    }
})
