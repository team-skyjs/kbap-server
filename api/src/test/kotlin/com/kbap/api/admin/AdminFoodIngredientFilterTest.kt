package com.kbap.api.admin

import com.kbap.api.IntegrationTest
import com.kbap.api.ingredient.IngredientTestSeed
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import org.springframework.test.web.servlet.get

@IntegrationTest
class AdminFoodIngredientFilterTest : AdminFoodCatalogTestSupport() {
    private fun seedFoodWith(koreanName: String, code: String, percent: Int): Long {
        val food = saveFood(koreanName)
        putUpdate(
            food.id,
            updateBody(koreanName, 0) + mapOf("ingredients" to listOf(mapOf("code" to code, "inclusion_percent" to percent))),
        ).andExpect { status { isOk() } }
        return food.id
    }

    private fun idsOf(query: String): List<Long> =
        mapper.readTree(
            mockMvc.get("$path$query") { adminAuth(this) }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload").path("items").map { it.path("id").asLong() }

    init {
        beforeSpec { IngredientTestSeed.restoreCatalog(dataSource) }

        given("어드민 음식 목록의 재료 역조회") {
            `when`("ingredientCode 로 거르면") {
                then("그 재료가 든 음식만 내려온다") {
                    val shrimpPaste = seedFoodWith("새우젓찌개", "SALTED_SHRIMP", 50)
                    seedFoodWith("된장찌개", "SOY", 80)

                    idsOf("?ingredientCode=SALTED_SHRIMP") shouldContainExactlyInAnyOrder listOf(shrimpPaste)
                }
            }

            `when`("categoryCode 로 거르면") {
                then("그 분류에 속한 재료가 하나라도 든 음식이 내려온다") {
                    val shrimpPaste = seedFoodWith("새우젓국", "SALTED_SHRIMP", 50)
                    val crab = seedFoodWith("꽃게탕", "CRAB", 70)
                    seedFoodWith("콩국수", "SOY", 90)

                    idsOf("?categoryCode=CRUSTACEAN") shouldContainExactlyInAnyOrder listOf(shrimpPaste, crab)
                }
            }

            `when`("필터가 없으면") {
                then("기존처럼 전체가 내려온다") {
                    val a = seedFoodWith("전체음식가", "SOY", 10)
                    val b = seedFoodWith("전체음식나", "EGG", 20)

                    idsOf("") shouldContainExactlyInAnyOrder listOf(a, b)
                }
            }
        }
    }
}
