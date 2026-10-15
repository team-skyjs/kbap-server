package com.kbap.api.home

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
class HomeTopRatedTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper = jacksonObjectMapper()

    init {
        val reviewer = 900L

        fun payload(version: String, memberId: Long? = null): JsonNode =
            mapper.readTree(
                mockMvc.get("/api/home?lang=en") {
                    header("X-API-Version", version)
                    memberId?.let { header("Authorization", "Bearer ${tokenIssuer.issueAccessToken(it, MemberRole.USER)}") }
                }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload")

        fun JsonNode.topRatedIds() = path("topRatedFoods").map { it.path("foodId").asLong() }

        fun review(foodId: Long, taste: Int, speed: Int = 0, kindness: Int = 0, status: String = "ACTIVE") =
            HomeTestSeed.seedRatedReview(dataSource, foodId, reviewer, taste, speed, kindness, status)

        beforeContainer {
            HomeTestSeed.reset(dataSource)
            HomeTestSeed.seedPlainMember(dataSource, reviewer)
        }

        given("홈 평점 높은 음식 — 버전 분기") {
            `when`("1.2 로 조회하면") {
                then("topRatedFoods 키 자체가 없다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 1)
                    review(1L, 5)

                    payload("1.2").has("topRatedFoods") shouldBe false
                }
            }

            `when`("1.3 으로 조회하는데 리뷰가 없으면") {
                then("빈 배열이 내려간다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 2)

                    val node = payload("1.3").path("topRatedFoods")
                    node.isArray shouldBe true
                    node.size() shouldBe 0
                }
            }
        }

        given("홈 평점 높은 음식 — 정렬") {
            `when`("리뷰 수·총평점·id 가 섞여 있으면") {
                then("리뷰 수 내림차순 → 총평점 내림차순 → foodId 내림차순이다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 4)
                    review(1L, 3)
                    review(1L, 3)
                    review(1L, 3)
                    review(2L, 3)
                    review(2L, 3)
                    review(3L, 5)
                    review(3L, 5)
                    review(4L, 3)
                    review(4L, 3)

                    payload("1.3").topRatedIds() shouldContainExactly listOf(1L, 3L, 4L, 2L)
                }
            }

            `when`("후보가 21개면") {
                then("20개만 내려간다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 21)
                    (1L..21L).forEach { review(it, 4) }

                    payload("1.3").topRatedIds() shouldContainExactly (21L downTo 2L).toList()
                }
            }
        }

        given("홈 평점 높은 음식 — 값") {
            `when`("속도·친절도 입력이 없으면") {
                then("두 값은 null 이고 총평점은 맛과 같다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 1)
                    review(1L, 4)
                    review(1L, 3)

                    val item = payload("1.3").path("topRatedFoods").single()
                    item.path("taste").asDouble() shouldBe 3.5
                    item.path("servingSpeed").isNull shouldBe true
                    item.path("staffKindness").isNull shouldBe true
                    item.path("overallRating").asDouble() shouldBe 3.5
                    item.path("review").path("averageRating").asDouble() shouldBe 3.5
                    item.path("review").path("count").asLong() shouldBe 2L
                    item.path("name").asText() shouldBe "Menu1"
                    item.path("bookmarked").asBoolean() shouldBe false
                }
            }

            `when`("평균이 소수로 떨어지고 0 미입력이 섞이면") {
                then("0 은 평균에서 빠지고 반올림 전 값으로 총평점을 낸다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 1)
                    review(1L, 5, speed = 5, kindness = 5)
                    review(1L, 5, speed = 4, kindness = 5)
                    review(1L, 4, speed = 4, kindness = 5)
                    review(1L, 4, speed = 0, kindness = 0)

                    val item = payload("1.3").path("topRatedFoods").single()
                    item.path("taste").asDouble() shouldBe 4.5
                    item.path("servingSpeed").asDouble() shouldBe 4.3
                    item.path("staffKindness").asDouble() shouldBe 5.0
                    item.path("overallRating").asDouble() shouldBe 4.6
                }
            }

            `when`("맛 4.666…·속도 4.333…·친절 5.0 이면") {
                then("4.7·4.3·5.0·4.7 이다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 1)
                    review(1L, 5, speed = 5, kindness = 5)
                    review(1L, 5, speed = 4, kindness = 5)
                    review(1L, 4, speed = 4, kindness = 5)

                    val item = payload("1.3").path("topRatedFoods").single()
                    item.path("taste").asDouble() shouldBe 4.7
                    item.path("servingSpeed").asDouble() shouldBe 4.3
                    item.path("staffKindness").asDouble() shouldBe 5.0
                    item.path("overallRating").asDouble() shouldBe 4.7
                }
            }
        }

        given("홈 평점 높은 음식 — 제외 조건") {
            `when`("삭제 리뷰·비READY 음식·삭제 음식·리뷰 0건 음식이 있으면") {
                then("집계·목록에서 빠진다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 5)
                    review(1L, 5)
                    review(1L, 1, status = "DELETED")
                    review(2L, 5)
                    HomeTestSeed.updateFood(dataSource, 2L, "content_status = 'PENDING_REVIEW'")
                    review(3L, 5)
                    HomeTestSeed.updateFood(dataSource, 3L, "status = 'DELETED'")
                    review(4L, 2, status = "DELETED")

                    val items = payload("1.3").path("topRatedFoods")
                    items.map { it.path("foodId").asLong() } shouldContainExactly listOf(1L)
                    items.single().path("taste").asDouble() shouldBe 5.0
                    items.single().path("review").path("count").asLong() shouldBe 1L
                }
            }
        }

        given("홈 평점 높은 음식 — 회원·비회원") {
            `when`("북마크한 회원과 비회원이 조회하면") {
                then("같은 목록이고 회원만 bookmarked 가 true 다") {
                    HomeTestSeed.seedReadyFoods(dataSource, 2)
                    HomeTestSeed.seedMember(dataSource, memberId = 11L, codes = emptyList())
                    review(1L, 5)
                    review(2L, 4)
                    review(2L, 4)
                    HomeTestSeed.seedBookmark(dataSource, 11L, 1L)

                    val member = payload("1.3", 11L).path("topRatedFoods")
                    val guest = payload("1.3").path("topRatedFoods")
                    member.map { it.path("foodId").asLong() } shouldContainExactly listOf(2L, 1L)
                    guest.map { it.path("foodId").asLong() } shouldContainExactly listOf(2L, 1L)
                    member.map { it.path("bookmarked").asBoolean() } shouldContainExactly listOf(false, true)
                    guest.map { it.path("bookmarked").asBoolean() } shouldContainExactly listOf(false, false)
                }
            }
        }
    }
}
