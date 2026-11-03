package com.kbap.api.food

import com.kbap.common.domain.food.dto.FoodSearchName
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodSearchRankerRamyeonTest : BehaviorSpec({
    val names = listOf(
        FoodSearchName(1, "라면", "라면", mapOf("en" to "Korean Ramyeon")),
        FoodSearchName(2, "김치라면", "김치라면", mapOf("en" to "Kimchi Ramyeon")),
        FoodSearchName(3, "미소라멘", "미소라멘", mapOf("en" to "Miso Ramen")),
        FoodSearchName(4, "비빔밥", "비빔밥", mapOf("en" to "Bibimbap")),
    )

    given("음식 이름 검색의 라면 표기 동의어(KB-960)") {
        `when`("ramen·ramyun·Ramyeon 으로 검색하면") {
            then("셋 다 같은 표기로 보고 Ramyeon 으로 바뀐 한국 라면 음식을 찾는다") {
                listOf("ramen", "ramyun", "Ramyeon", "RAMEN").forEach { keyword ->
                    FoodSearchRanker.matchingIds(keyword, names) shouldBe setOf(1L, 2L, 3L)
                }
                FoodSearchRanker.matchingIds("kimchi ramen", names) shouldBe setOf(2L)
            }
        }
        `when`("일본식 라멘을 영어로 검색하면") {
            then("미소라멘을 그대로 찾는다") {
                FoodSearchRanker.matchingIds("miso ramen", names) shouldBe setOf(3L)
            }
        }
    }
})
