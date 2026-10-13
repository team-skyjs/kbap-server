package com.kbap.api.home

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.food.PopularFoodIdCache
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
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

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        fun seedExtraViews(foodId: Long, count: Int) = repeat(count) {
            execute(
                "INSERT INTO food_view_log (food_id, member_id, status, created_at, updated_at) " +
                    "VALUES ($foodId, NULL, 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))",
            )
        }

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

        given("1.0 홈 — 마케팅 기간 안") {
            `when`("1.0 헤더로 조회하면") {
                then("popularFoods 는 조회 수 집계 결과이고 설정 순서가 아니다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    seedExtraViews(foodId = 2L, count = 2)

                    popularIds(null, "1.0") shouldContainExactly listOf(2L, 3L, 1L)
                    popularIds(null, "2.1") shouldContainExactly listOf(3L, 1L, 2L)
                }
            }

            `when`("1.0 과 2.1 로 같은 회원이 조회하면") {
                then("popularFoods 를 뺀 payload 가 같다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    seedExtraViews(foodId = 2L, count = 2)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))

                    val v10 = payload(11L, "1.0") as ObjectNode
                    val v21 = payload(11L, "2.1") as ObjectNode
                    v10.remove("popularFoods")
                    v21.remove("popularFoods")

                    v21 shouldBe v10
                }
            }
        }

        given("2.1 홈 — 노출 불가 음식") {
            `when`("목록의 음식 1 이 READY 가 아니면") {
                then("popularFoods 는 3, 2 이고 길이 2 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    execute("UPDATE food SET content_status = 'FAILED' WHERE id = 1")

                    popularIds(null, "2.1") shouldContainExactly listOf(3L, 2L)
                }
            }

            `when`("목록의 음식 1 이 삭제되면") {
                then("popularFoods 는 3, 2 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    execute("UPDATE food SET status = 'DELETED' WHERE id = 1")

                    popularIds(null, "2.1") shouldContainExactly listOf(3L, 2L)
                }
            }

            `when`("목록의 음식이 전부 READY 가 아니면") {
                then("popularFoods 는 빈 배열이고 조회 수 집계로 폴백하지 않는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 4)
                    execute("UPDATE food SET content_status = 'FAILED' WHERE id IN (1, 2, 3)")

                    popularIds(null, "2.1").shouldBeEmpty()
                    popularIds(null, "1.0") shouldContainExactly listOf(4L)
                }
            }
        }
    }
}
