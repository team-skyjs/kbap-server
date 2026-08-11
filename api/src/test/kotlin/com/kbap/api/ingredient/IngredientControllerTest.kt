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
    }
}
