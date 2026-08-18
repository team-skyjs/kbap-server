package com.kbap.api.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ReviewBlockFilterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun seedMember(memberId: Long): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, nickname, country_code, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, 'KR', 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "block-filter-test-$memberId")
                    ps.setString(3, "필터$memberId")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long, koreanName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (id, korean_name, description, spiciness, name_translations,
                                      description_translations, ingredients, content_status, status,
                                      created_at, updated_at)
                    VALUES (?, ?, '설명', 0, '{}', '{}', '[]', 'READY', 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE id = id
                    """,
                ).use { ps ->
                    ps.setLong(1, id)
                    ps.setString(2, koreanName)
                    ps.executeUpdate()
                }
            }

        fun createReview(token: String, foodId: Long, rating: Int): Long {
            val response = mockMvc.post("/api/reviews") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("foodId" to foodId, "rating" to rating))
            }.andReturn().response.getContentAsString(Charsets.UTF_8)
            return mapper.readTree(response).path("payload").path("reviewId").asLong()
        }

        fun blockMember(token: String, targetMemberId: Long) {
            mockMvc.post("/api/members/me/blocks") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("memberId" to targetMemberId))
            }.andExpect { status { isOk() } }
        }

        fun foodReviewItems(token: String, foodId: Long): JsonNode =
            mapper.readTree(
                mockMvc.get("/api/reviews") {
                    header("Authorization", "Bearer $token")
                    param("foodId", foodId.toString())
                    param("lang", "ko")
                }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload").path("items")

        fun ratingSummary(token: String, foodId: Long): JsonNode =
            mapper.readTree(
                mockMvc.get("/api/foods/$foodId") {
                    header("Authorization", "Bearer $token")
                    param("lang", "ko")
                }.andReturn().response.getContentAsString(Charsets.UTF_8),
            ).path("payload").path("reviewSummary").path("overall")

        given("차단과 음식 리뷰 목록 필터") {
            `when`("A 가 B 를 차단하면") {
                then("A 의 리뷰 목록에서 B 의 리뷰만 사라지고, B 의 화면과 집계는 그대로다") {
                    seedFood(9200L, "차단필터김치찌개")
                    val viewerToken = accessToken(9201L)
                    val blockedToken = accessToken(9202L)
                    val othersToken = accessToken(9203L)
                    val myReviewId = createReview(viewerToken, 9200L, 5)
                    val blockedReviewId = createReview(blockedToken, 9200L, 1)
                    val othersReviewId = createReview(othersToken, 9200L, 3)

                    val summaryBefore = ratingSummary(viewerToken, 9200L)
                    summaryBefore.path("reviewCount").asLong() shouldBe 3L

                    blockMember(viewerToken, 9202L)

                    foodReviewItems(viewerToken, 9200L).map { it.path("reviewId").asLong() }
                        .shouldBe(listOf(othersReviewId, myReviewId))

                    foodReviewItems(blockedToken, 9200L).map { it.path("reviewId").asLong() }
                        .shouldBe(listOf(othersReviewId, blockedReviewId, myReviewId))

                    val summaryAfter = ratingSummary(viewerToken, 9200L)
                    summaryAfter.path("reviewCount").asLong() shouldBe 3L
                    summaryAfter.path("averageRating").asDouble() shouldBe
                        (summaryBefore.path("averageRating").asDouble() plusOrMinus 0.0001)
                }
            }
            `when`("차단을 해제하면") {
                then("그 회원의 리뷰가 다시 보이고, 재차단하면 다시 사라진다") {
                    seedFood(9220L, "차단필터비빔밥")
                    val viewerToken = accessToken(9221L)
                    val writerToken = accessToken(9222L)
                    val reviewId = createReview(writerToken, 9220L, 4)

                    blockMember(viewerToken, 9222L)
                    foodReviewItems(viewerToken, 9220L).size() shouldBe 0

                    mockMvc.delete("/api/members/me/blocks/9222") {
                        header("Authorization", "Bearer $viewerToken")
                    }.andExpect { status { isOk() } }
                    foodReviewItems(viewerToken, 9220L).map { it.path("reviewId").asLong() }
                        .shouldBe(listOf(reviewId))

                    blockMember(viewerToken, 9222L)
                    foodReviewItems(viewerToken, 9220L).size() shouldBe 0
                }
            }
            `when`("차단하지 않은 회원이 조회하면") {
                then("전체 리뷰가 보인다") {
                    seedFood(9210L, "차단필터된장찌개")
                    val writerToken = accessToken(9211L)
                    val viewerToken = accessToken(9212L)
                    val reviewId = createReview(writerToken, 9210L, 4)

                    foodReviewItems(viewerToken, 9210L).map { it.path("reviewId").asLong() }
                        .shouldBe(listOf(reviewId))
                }
            }
        }
    }
}
