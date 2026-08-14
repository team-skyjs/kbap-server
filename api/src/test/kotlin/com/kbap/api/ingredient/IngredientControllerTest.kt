package com.kbap.api.ingredient

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class IngredientControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    private fun getIngredients(query: String) =
        mockMvc.get("/api/ingredients$query").andReturn().response.getContentAsString(Charsets.UTF_8)

    private fun getDiets(query: String) =
        mockMvc.get("/api/ingredients/diets$query").andReturn().response.getContentAsString(Charsets.UTF_8)

    init {
        beforeSpec {
            IngredientTestSeed.restoreCatalog(dataSource)
        }

        given("인증 헤더가 없는 요청") {
            `when`("lang=ko 로 재료 목록을 조회하면") {
                then("전 재료가 code·name·imageUrl 과 함께 내려온다") {
                    val json = mockMvc.get("/api/ingredients?lang=ko").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    val ingredients = mapper.readTree(json).path("payload").path("ingredients")
                    ingredients.size() shouldBe IngredientCode.entries.size
                    val first = ingredients.first()
                    first.path("code").asText() shouldBe "EGG"
                    first.path("name").asText() shouldBe "계란"
                    first.path("imageUrl").asText() shouldBe "https://cdn.test/images/webp/egg.webp"
                }
            }
        }

        given("무효 토큰을 지닌 요청") {
            `when`("재료 목록을 조회하면") {
                then("공개 API 이므로 거절 없이 동일하게 응답한다") {
                    mockMvc.get("/api/ingredients?lang=ko") {
                        header("Authorization", "Bearer garbage.token.value")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                }
            }
        }

        given("lang 을 보내지 않은 요청") {
            `when`("재료 목록을 조회하면") {
                then("400 COMMON-002 로 거절한다") {
                    mockMvc.get("/api/ingredients").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }
        }

        given("지원하는 lang 을 보낸 요청") {
            `when`("lang=en 으로 조회하면") {
                then("영어 번역명으로 응답한다") {
                    val json = getIngredients("?lang=en")

                    mapper.readTree(json).path("payload").path("ingredients")
                        .first().path("name").asText() shouldBe "Egg"
                }
            }
        }

        given("지원하지 않는 lang 을 보낸 요청") {
            `when`("lang=fr 로 조회하면") {
                then("400 이 아니라 영어로 응답한다") {
                    val json = getIngredients("?lang=fr")

                    mapper.readTree(json).path("payload").path("ingredients")
                        .first().path("name").asText() shouldBe "Egg"
                }
            }
        }

        given("diet 카테고리별 회피 재료 매핑 조회") {
            `when`("인증 헤더 없이 lang=ko 조회하면") {
                then("공개 API 이므로 15종 카테고리가 기획 순서로, 카테고리별 재료가 id·이름과 함께 내려온다") {
                    val json = getDiets("?lang=ko")

                    val diets = mapper.readTree(json).path("payload").path("diets")
                    diets.size() shouldBe 15
                    diets.first().path("code").asText() shouldBe "VEGAN"
                    diets.first().path("name").asText() shouldBe "비건"
                    diets.first().path("ingredients").size() shouldBe 41

                    val glutenFree = diets.first { it.path("code").asText() == "GLUTEN_FREE" }
                    glutenFree.path("ingredients").map { it.path("name").asText() } shouldBe
                        listOf("밀", "보리", "호밀", "귀리")
                    glutenFree.path("ingredients").map { it.path("code").asText() } shouldBe
                        listOf("WHEAT", "BARLEY", "RYE", "OAT")

                    val ingredientIds = glutenFree.path("ingredients").map { it.path("id").asLong() }
                    ingredientIds shouldBe ingredientIds.sorted()
                }
            }

            `when`("무효 토큰으로 조회하면") {
                then("공개 API 이므로 거절 없이 동일하게 응답한다") {
                    mockMvc.get("/api/ingredients/diets?lang=ko") {
                        header("Authorization", "Bearer garbage.token.value")
                    }.andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                }
            }

            `when`("lang 없이 조회하면") {
                then("400 COMMON-002 로 거절한다") {
                    mockMvc.get("/api/ingredients/diets").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("지원 언어 lang=en 으로 조회하면") {
                then("재료명이 영어 표시명으로 내려오고 카테고리명은 한국어를 유지한다") {
                    val json = getDiets("?lang=en")

                    val glutenFree = mapper.readTree(json).path("payload").path("diets")
                        .first { it.path("code").asText() == "GLUTEN_FREE" }
                    glutenFree.path("name").asText() shouldBe "글루텐 프리"
                    glutenFree.path("ingredients").first().path("name").asText() shouldBe "Wheat"
                }
            }

            `when`("지원하지 않는 lang=fr 로 조회하면") {
                then("400 이 아니라 영어 표시명으로 응답한다") {
                    val json = getDiets("?lang=fr")

                    mapper.readTree(json).path("payload").path("diets")
                        .first { it.path("code").asText() == "GLUTEN_FREE" }
                        .path("ingredients").first().path("name").asText() shouldBe "Wheat"
                }
            }
        }
    }
}
