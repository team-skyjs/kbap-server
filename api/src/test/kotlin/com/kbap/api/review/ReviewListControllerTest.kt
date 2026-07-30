package com.kbap.api.review

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ReviewListControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        fun seedMember(memberId: Long, countryCode: String? = "KR"): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO member (id, provider, provider_uid, nickname, profile, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE profile = VALUES(profile)
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "review-list-test-$memberId")
                    ps.setString(3, "리뷰어$memberId")
                    ps.setString(4, if (countryCode == null) "{}" else """{"countryCode":"$countryCode"}""")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long, countryCode: String? = "KR"): String {
            seedMember(memberId, countryCode)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long, koreanName: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food (id, korean_name, description, spiciness, name_translations,
                                      description_translations, avoidance_substances, content_status, status,
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

        fun createReview(token: String, foodId: Long, rating: Int = 4): Long {
            val response = mockMvc.post("/api/v1/reviews") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("foodId" to foodId, "rating" to rating))
            }.andReturn().response.getContentAsString(Charsets.UTF_8)
            return mapper.readTree(response).path("payload").path("reviewId").asLong()
        }

        fun deleteReview(token: String, reviewId: Long) {
            mockMvc.delete("/api/v1/reviews/$reviewId") {
                header("Authorization", "Bearer $token")
            }.andExpect { status { isOk() } }
        }

        fun foodReviews(
            token: String?,
            foodId: Long,
            cursor: String? = null,
            countryCode: String? = null,
        ): ResultActionsDsl =
            mockMvc.get("/api/v1/foods/$foodId/reviews") {
                token?.let { header("Authorization", "Bearer $it") }
                cursor?.let { param("cursor", it) }
                countryCode?.let { param("countryCode", it) }
            }

        fun myReviews(token: String?, cursor: String? = null): ResultActionsDsl =
            mockMvc.get("/api/v1/members/me/reviews") {
                token?.let { header("Authorization", "Bearer $it") }
                cursor?.let { param("cursor", it) }
            }

        fun payloadOf(result: ResultActionsDsl): JsonNode =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8)).path("payload")

        given("음식별 리뷰 목록 — GET /api/v1/foods/{foodId}/reviews") {
            seedFood(800L, "목록김치찌개")

            `when`("리뷰 25건에서 첫 페이지를 조회하면") {
                then("최신순 20건과 다음 커서를 주고, 커서로 나머지 5건을 잇는다") {
                    val token = accessToken(800L)
                    val reviewIds = (1..25).map { createReview(token, 800L) }

                    val first = payloadOf(foodReviews(token, 800L))
                    first.path("items").size() shouldBe 20
                    first.path("items").first().path("reviewId").asLong() shouldBe reviewIds.last()
                    first.path("hasNext").asBoolean().shouldBeTrue()
                    val nextCursor = first.path("nextCursor").asLong()

                    val second = payloadOf(foodReviews(token, 800L, cursor = nextCursor.toString()))
                    second.path("items").size() shouldBe 5
                    second.path("hasNext").asBoolean().shouldBeFalse()
                    second.path("nextCursor").isNull.shouldBeTrue()
                }
            }
            `when`("countryCode 필터로 조회하면") {
                then("작성 시점 국적 스냅샷이 일치하는 리뷰만 준다") {
                    seedFood(801L, "목록된장찌개")
                    val kr = accessToken(801L, "KR")
                    val vn = accessToken(802L, "VN")
                    createReview(kr, 801L)
                    createReview(kr, 801L)
                    val vnReviewId = createReview(vn, 801L)

                    val filtered = payloadOf(foodReviews(kr, 801L, countryCode = "VN"))
                    filtered.path("items").size() shouldBe 1
                    filtered.path("items").first().path("reviewId").asLong() shouldBe vnReviewId

                    payloadOf(foodReviews(kr, 801L)).path("items").size() shouldBe 3
                }
            }
            `when`("리뷰가 없는 국적 코드로 필터하면") {
                then("빈 목록을 준다") {
                    val token = accessToken(800L)
                    payloadOf(foodReviews(token, 800L, countryCode = "ZZ")).path("items").size() shouldBe 0
                }
            }
            `when`("삭제된 리뷰가 있으면") {
                then("목록에서 제외된다") {
                    seedFood(803L, "목록비빔밥")
                    val token = accessToken(803L)
                    val kept = createReview(token, 803L)
                    val deleted = createReview(token, 803L)
                    deleteReview(token, deleted)

                    val items = payloadOf(foodReviews(token, 803L)).path("items")
                    items.size() shouldBe 1
                    items.first().path("reviewId").asLong() shouldBe kept
                }
            }
            `when`("토큰 없이 조회하면") {
                then("401 을 반환한다") {
                    foodReviews(null, 800L).andExpect { status { isUnauthorized() } }
                }
            }
            `when`("비정상 커서로 조회하면") {
                then("400 FOOD-002 를 반환한다") {
                    val token = accessToken(800L)
                    foodReviews(token, 800L, cursor = "abc").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-002") }
                    }
                }
            }
            `when`("존재하지 않는 음식으로 조회하면") {
                then("400 FOOD-001 을 반환한다") {
                    val token = accessToken(800L)
                    foodReviews(token, 899L).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }
        }

        given("내 리뷰 목록 — GET /api/v1/members/me/reviews") {
            seedFood(810L, "목록불고기")
            seedFood(811L, "목록잡채")

            `when`("여러 회원의 리뷰가 섞여 있으면") {
                then("본인 리뷰만 최신순으로 준다") {
                    val mine = accessToken(810L)
                    val other = accessToken(811L)
                    val first = createReview(mine, 810L)
                    val second = createReview(mine, 811L)
                    createReview(other, 810L)

                    val items = payloadOf(myReviews(mine)).path("items")
                    items.size() shouldBe 2
                    items.map { it.path("reviewId").asLong() } shouldBe listOf(second, first)
                    items.first().path("author").path("nickname").asText() shouldBe "리뷰어810"
                    items.first().path("author").path("countryCode").asText() shouldBe "KR"
                }
            }
            `when`("탈퇴한 회원의 리뷰가 목록에 있으면") {
                then("그 리뷰의 author 는 null 이다") {
                    seedFood(820L, "목록탈퇴음식")
                    val writer = accessToken(820L)
                    val viewer = accessToken(821L)
                    val reviewId = createReview(writer, 820L)
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE member SET status = 'DELETED' WHERE id = 820") }
                    }

                    val items = payloadOf(foodReviews(viewer, 820L)).path("items")
                    items.size() shouldBe 1
                    items.first().path("reviewId").asLong() shouldBe reviewId
                    items.first().path("author").isNull.shouldBeTrue()
                }
            }
            `when`("내 리뷰를 삭제하면") {
                then("목록에서 빠진다") {
                    val token = accessToken(812L)
                    val reviewId = createReview(token, 810L)
                    deleteReview(token, reviewId)
                    payloadOf(myReviews(token)).path("items").size() shouldBe 0
                }
            }
            `when`("토큰 없이 조회하면") {
                then("401 을 반환한다") {
                    myReviews(null).andExpect { status { isUnauthorized() } }
                }
            }
        }
    }
}
