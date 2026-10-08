package com.kbap.api.home

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.food.PopularFoodIdCache
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import javax.sql.DataSource

@IntegrationTest
class HomePopularCacheTest : BehaviorSpec() {
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
        fun payload(memberId: Long?, lang: String = "en") =
            mapper.readTree(
                mockMvc.get("/api/home?lang=$lang") {
                    memberId?.let {
                        header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(it, MemberRole.USER)}")
                    }
                }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload")

        fun popularIds(memberId: Long?) = payload(memberId).path("popularFoods").map { it.path("foodId").asLong() }

        fun riskOf(memberId: Long, foodId: Long) = payload(memberId).path("popularFoods")
            .single { it.path("foodId").asLong() == foodId }
            .path("overallRiskStatus").asText()

        fun execute(sql: String) = dataSource.connection.use { c -> c.createStatement().use { it.execute(sql) } }

        beforeContainer {
            HomeTestSeed.reset(dataSource)
            popularFoodIdCache.invalidateAll()
        }

        given("인기 음식 목록이 캐시된 뒤") {
            `when`("기피 성분이 다른 두 회원이 연속으로 조회하면") {
                then("인기 목록 id 는 같고 회원 11 의 음식 1 은 DANGER, 회원 12 의 음식 1 은 DANGER 가 아니다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 2)
                    HomeTestSeed.seedFoodSubstance(dataSource, foodId = 1L, code = "EGG", percent = 100)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = listOf("EGG"))
                    HomeTestSeed.seedMember(dataSource, memberId = 12L, codes = emptyList())

                    val idsOf11 = popularIds(11L)
                    val idsOf12 = popularIds(12L)

                    idsOf11 shouldBe idsOf12
                    idsOf11 shouldContainExactlyInAnyOrder listOf(1L, 2L)
                    riskOf(11L, 1L) shouldBe "DANGER"
                    riskOf(12L, 1L) shouldNotBe "DANGER"
                }
            }

            `when`("캐시된 목록의 음식이 삭제되면") {
                then("다음 홈 응답의 popularFoods 에 2 가 없고 1·3 은 남는다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 3)
                    popularIds(null) shouldContainExactlyInAnyOrder listOf(1L, 2L, 3L)

                    execute("UPDATE food SET status = 'DELETED' WHERE id = 2")

                    val ids = popularIds(null)
                    ids shouldNotContain 2L
                    ids shouldContainExactlyInAnyOrder listOf(1L, 3L)
                }
            }

            `when`("다른 언어로 조회하면") {
                then("같은 음식이 メニュー1 로 나온다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 1)
                    payload(null, lang = "ko").path("popularFoods").single().path("foodId").asLong() shouldBe 1L

                    payload(null, lang = "ja").path("popularFoods").single().path("name").asText() shouldBe "メニュー1"
                }
            }
        }
    }
}
