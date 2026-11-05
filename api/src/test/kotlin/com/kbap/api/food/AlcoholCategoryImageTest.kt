package com.kbap.api.food

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class AlcoholCategoryImageTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var foodRepository: FoodJpaRepository
    @Autowired private lateinit var foodService: FoodService

    private val mapper = jacksonObjectMapper()

    init {
        fun readyFood(name: String, ingredients: List<FoodIngredient>) = foodRepository.save(
            Food(
                koreanName = name,
                displayName = name,
                imageRef = "images/webp/food/$name.webp",
                description = "설명 $name",
                longDescription = "$name 긴 설명",
                spiciness = 0,
                ingredients = ingredients,
                contentStatus = FoodContentStatus.READY,
            ),
        )
        fun detail(id: Long) = mapper.readTree(
            mockMvc.get("/api/foods/$id?lang=en") { header("X-API-Version", "1.0") }
                .andExpect { status { isOk() } }.andReturn().response.getContentAsString(Charsets.UTF_8),
        ).path("payload")

        beforeEach { TestTables.clearAll(dataSource) }
        afterSpec { TestTables.clearAll(dataSource) }

        given("술 공용 이미지(S-972)") {
            `when`("ALCOHOL 60% 이상인 음식을 보면") {
                then("대표 이미지·갤러리·이미지 URL 결정 지점(스캔·목록이 쓰는 resolveImageUrl) 모두 공용 이미지다 — 음식 행의 image_ref 는 그대로") {
                    val soju = readyFood("공용술소주", listOf(FoodIngredient("ALCOHOL", 95)))

                    detail(soju.id).let {
                        it.path("imageRef").asText() shouldEndWith "images/webp/static/alcohol-category.webp"
                        it.path("images").map { image -> image.path("url").asText() } shouldBe listOf(it.path("imageRef").asText())
                    }
                    foodService.resolveImageUrlOrDefault(soju) shouldEndWith "images/webp/static/alcohol-category.webp"
                    foodRepository.findById(soju.id).orElseThrow().imageRef shouldBe "images/webp/food/공용술소주.webp"
                }
            }

            `when`("술이 확실히 들어간 요리면(맥주수육) — Codex P2(#586)") {
                then("술 음료가 아니므로 원래 이미지 그대로다") {
                    val dish = readyFood("공용술맥주수육", listOf(FoodIngredient("ALCOHOL", 95), FoodIngredient("PORK", 100)))

                    detail(dish.id).path("imageRef").asText() shouldEndWith "images/webp/food/공용술맥주수육.webp"
                }
            }

            `when`("술이 아닌 음식이면") {
                then("원래 이미지 그대로다") {
                    val stew = readyFood("공용술아님찌개", listOf(FoodIngredient("ALCOHOL", 20), FoodIngredient("PORK", 60)))

                    detail(stew.id).path("imageRef").asText() shouldEndWith "images/webp/food/공용술아님찌개.webp"
                }
            }
        }
    }
}
