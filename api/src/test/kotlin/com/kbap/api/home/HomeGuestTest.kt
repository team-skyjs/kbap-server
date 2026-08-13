package com.kbap.api.home

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
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
class HomeGuestTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        beforeContainer {
            HomeTestSeed.reset(dataSource)
        }

        given("인증 헤더가 없는 요청") {
            `when`("홈을 조회하면") {
                then("개인화 섹션은 빈 배열이고 인기 음식만 영어로 내려온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 7)

                    val json = mockMvc.get("/api/home?lang=en").andExpect {
                        status { isOk() }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)
                    val payload = mapper.readTree(json).path("payload")

                    payload.path("authenticated").asBoolean() shouldBe false
                    payload.path("avoidedSubstances").isNull shouldBe false
                    payload.path("avoidedSubstances").isEmpty shouldBe true
                    payload.path("recentScans").isNull shouldBe false
                    payload.path("recentScans").isEmpty shouldBe true
                    payload.path("popularFoods").size() shouldBe 5
                    payload.path("popularFoods").first().path("name").asText().startsWith("Menu") shouldBe true
                }
            }

            `when`("기피 성분이 없으므로") {
                then("위험도는 강조되지 않는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)
                    HomeTestSeed.seedFoodSubstance(dataSource, foodId = 1L, code = "EGG", percent = 100)

                    val json = mockMvc.get("/api/home?lang=en").andReturn().response.getContentAsString(Charsets.UTF_8)
                    val food = mapper.readTree(json).path("payload").path("popularFoods").single()

                    food.path("overallRiskStatus").asText() shouldBe "SAFE"
                }
            }
        }

        given("위조된 토큰을 지닌 요청") {
            `when`("홈을 조회하면") {
                then("비회원으로 폴백하지 않고 401 로 거절한다") {
                    mockMvc.get("/api/home?lang=en") {
                        header("Authorization", "Bearer forged.token.value")
                    }.andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.success") { value(false) }
                    }
                }
            }
        }

        given("lang 을 보내지 않은 요청") {
            `when`("홈을 조회하면") {
                then("400 COMMON-002 로 거절한다") {
                    mockMvc.get("/api/home").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }
        }

        given("지원하는 lang 을 보낸 비회원 요청") {
            `when`("lang=ja 로 조회하면") {
                then("일본어 번역명으로 응답한다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)

                    val json = mockMvc.get("/api/home?lang=ja").andReturn().response.getContentAsString(Charsets.UTF_8)

                    mapper.readTree(json).path("payload").path("popularFoods").single()
                        .path("name").asText() shouldBe "メニュー1"
                }
            }

            `when`("lang=ko 로 조회하면") {
                then("한국어 원문명으로 응답한다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)

                    val json = mockMvc.get("/api/home?lang=ko").andReturn().response.getContentAsString(Charsets.UTF_8)

                    mapper.readTree(json).path("payload").path("popularFoods").single()
                        .path("name").asText() shouldBe "메뉴1"
                }
            }
        }

        given("지원하지 않는 lang 을 보낸 요청") {
            `when`("lang=fr 로 조회하면") {
                then("400 이 아니라 영어로 응답한다") {
                    HomeTestSeed.seedReadyFoods(dataSource, count = 1)

                    val json = mockMvc.get("/api/home?lang=fr").andExpect {
                        status { isOk() }
                    }.andReturn().response.getContentAsString(Charsets.UTF_8)

                    mapper.readTree(json).path("payload").path("popularFoods").single()
                        .path("name").asText() shouldBe "Menu1"
                }
            }
        }
    }
}
