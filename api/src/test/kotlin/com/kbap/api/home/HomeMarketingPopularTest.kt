package com.kbap.api.home

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.food.PopularFoodIdCache
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class HomeMarketingPopularTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var popularFoodIdCache: PopularFoodIdCache

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun payload(memberId: Long?, version: String, lang: String = "en"): JsonNode =
            mapper.readTree(
                mockMvc.get("/api/home?lang=$lang") {
                    header("X-API-Version", version)
                    memberId?.let { header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(it, MemberRole.USER)}") }
                }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload")

        fun popularIds(memberId: Long?, version: String) =
            payload(memberId, version).path("popularFoods").map { it.path("foodId").asLong() }

        beforeContainer {
            HomeTestSeed.reset(dataSource)
            popularFoodIdCache.invalidateAll()
        }

        given("2.1 홈 — 마케팅 기간 안") {
            `when`("음식 1·2·3 이 READY 로 있으면") {
                then("popularFoods 는 설정 순서 3, 1, 2 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)

                    popularIds(null, "2.1") shouldContainExactly listOf(3L, 1L, 2L)
                }
            }

            `when`("기피 성분이 EGG 인 회원이 조회하면") {
                then("음식 1 의 overallRiskStatus 는 DANGER 이고 목록 순서는 그대로다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    HomeTestSeed.seedFoodSubstance(dataSource, foodId = 1L, code = "EGG", percent = 100)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))

                    val popular = payload(11L, "2.1").path("popularFoods")

                    popular.map { it.path("foodId").asLong() } shouldContainExactly listOf(3L, 1L, 2L)
                    popular.single { it.path("foodId").asLong() == 1L }.path("overallRiskStatus").asText() shouldBe "DANGER"
                }
            }

            `when`("ja 로 조회하면") {
                then("첫 항목 이름이 メニュー3 이다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)

                    payload(null, "2.1", lang = "ja").path("popularFoods").first().path("name").asText() shouldBe "メニュー3"
                }
            }

            `when`("비회원이 조회하면") {
                then("authenticated 는 false 이고 popularFoods 는 3, 1, 2 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)

                    val home = payload(null, "2.1")

                    home.path("authenticated").asBoolean() shouldBe false
                    home.path("popularFoods").map { it.path("foodId").asLong() } shouldContainExactly listOf(3L, 1L, 2L)
                }
            }
        }
    }
}
