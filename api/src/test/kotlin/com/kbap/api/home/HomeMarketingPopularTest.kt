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
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
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

        given("X-API-Version 2.1 홈 — 마케팅 인기 풀") {
            `when`("풀의 음식 1·2·3 만 READY 로 있으면") {
                then("popularFoods 는 1·2·3 전부이고 미존재 999 는 없다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    seedExtraViews(foodId = 2L, count = 2)

                    popularIds(null, "2.1") shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)
                }
            }

            `when`("풀에 READY 음식이 12개 있으면") {
                then("호출마다 그중 10개만 내려온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 12)

                    repeat(3) {
                        val ids = popularIds(null, "2.1")
                        ids.size shouldBe 10
                        ids.toSet().size shouldBe 10
                        ids.all { it in 1L..12L } shouldBe true
                    }
                }
            }

            `when`("풀의 음식 1 이 READY 가 아니면") {
                then("popularFoods 에 1 이 없고 빈자리를 채우지 않는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    execute("UPDATE food SET content_status = 'FAILED' WHERE id = 1")

                    popularIds(null, "2.1") shouldContainExactlyInAnyOrder listOf(2L, 3L)
                }
            }

            `when`("풀의 음식 1 이 삭제되면") {
                then("popularFoods 에 1 이 없다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    execute("UPDATE food SET status = 'DELETED' WHERE id = 1")

                    popularIds(null, "2.1") shouldNotContain 1L
                }
            }

            `when`("풀의 음식이 전부 READY 가 아니면") {
                then("popularFoods 는 빈 배열이고 조회 수 집계로 폴백하지 않는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    execute("UPDATE food SET content_status = 'FAILED' WHERE id IN (1, 2, 3)")

                    popularIds(null, "2.1").shouldBeEmpty()
                }
            }

            `when`("기피 성분이 EGG 인 회원이 조회하면") {
                then("음식 1 의 overallRiskStatus 는 DANGER 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    HomeTestSeed.seedFoodSubstance(dataSource, foodId = 1L, code = "EGG", percent = 100)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))

                    payload(11L, "2.1").path("popularFoods")
                        .single { it.path("foodId").asLong() == 1L }
                        .path("overallRiskStatus").asText() shouldBe "DANGER"
                }
            }

            `when`("ja 로 조회하면") {
                then("각 항목 이름이 メニュー{id} 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)

                    payload(null, "2.1", lang = "ja").path("popularFoods").forEach {
                        it.path("name").asText() shouldBe "メニュー${it.path("foodId").asLong()}"
                    }
                }
            }

            `when`("같은 회원이 1.0 과 2.1 로 조회하면") {
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

        given("X-API-Version 2.1 미만 홈") {
            `when`("1.0 으로 조회하면") {
                then("popularFoods 는 조회 수 집계 결과다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    seedExtraViews(foodId = 2L, count = 2)

                    popularIds(null, "1.0") shouldContainExactly listOf(2L, 3L, 1L)
                }
            }

            `when`("2.0 으로 조회하면") {
                then("popularFoods 는 조회 수 집계 결과다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    seedExtraViews(foodId = 2L, count = 2)

                    popularIds(null, "2.0") shouldContainExactly listOf(2L, 3L, 1L)
                }
            }
        }
    }
}
