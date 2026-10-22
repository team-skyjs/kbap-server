package com.kbap.api.processedfood

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ProcessedFoodTypeTest : BehaviorSpec({
    given("식품유형 → 유형 칩(KB-867)") {
        `when`("한 글자 키워드에 걸리던 유형이면 — 공부 #458") {
            then("엉뚱한 칩에 들어가지 않는다") {
                ProcessedFoodType.of("분말결정포도당") shouldBe ProcessedFoodType.SAUCES
                ProcessedFoodType.of("농축육수") shouldBe ProcessedFoodType.SAUCES
                ProcessedFoodType.of("식육추출가공품") shouldBe ProcessedFoodType.SAUCES
                ProcessedFoodType.of("땅콩버터") shouldBe ProcessedFoodType.SAUCES
                ProcessedFoodType.of("포장육") shouldBe ProcessedFoodType.MEALS
                ProcessedFoodType.of("건조저장육류") shouldBe ProcessedFoodType.MEALS
                ProcessedFoodType.of("기타가공식품, 빵가루") shouldBe ProcessedFoodType.SAUCES
            }
        }
        `when`("null 이던 유형이면") {
            then("맞는 칩에 들어간다") {
                ProcessedFoodType.of("다류(액상차)") shouldBe ProcessedFoodType.DRINKS
                ProcessedFoodType.of("유크림") shouldBe ProcessedFoodType.DAIRY
            }
        }
        `when`("대표 유형이면") {
            then("기대 칩이다") {
                mapOf(
                    "과자" to ProcessedFoodType.SNACKS, "조미김" to ProcessedFoodType.SNACKS, "유탕면류(봉지라면)" to ProcessedFoodType.RAMEN,
                    "빵류(케이크)" to ProcessedFoodType.BREAD, "혼합음료" to ProcessedFoodType.DRINKS, "침출차" to ProcessedFoodType.DRINKS,
                    "가공유" to ProcessedFoodType.DAIRY, "버터" to ProcessedFoodType.DAIRY, "빙과류" to ProcessedFoodType.ICE_CREAM,
                    "양념육" to ProcessedFoodType.MEALS, "조미건어포류" to ProcessedFoodType.MEALS, "어묵" to ProcessedFoodType.MEALS, "프레스햄" to ProcessedFoodType.MEALS,
                    "소스류" to ProcessedFoodType.SAUCES, "고추장" to ProcessedFoodType.SAUCES, "잼" to ProcessedFoodType.SAUCES,
                ).forEach { (foodType, chip) -> ProcessedFoodType.of(foodType) shouldBe chip }
                listOf("알수없음", "기타가공품", "곡류가공품", null, "").forEach { ProcessedFoodType.of(it) shouldBe null }
            }
        }
    }
})
