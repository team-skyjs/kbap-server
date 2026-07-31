package com.kbap.api.food

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
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
class FoodDetailRatingTest : BehaviorSpec() {
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
                    INSERT INTO member (id, provider, provider_uid, profile, member_status,
                                        onboarding_completed, status, created_at, updated_at)
                    VALUES (?, 'GOOGLE', ?, ?, 'ACTIVE', 1, 'ACTIVE', NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE profile = VALUES(profile)
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, "food-rating-test-$memberId")
                    ps.setString(3, if (countryCode == null) "{}" else """{"countryCode":"$countryCode"}""")
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

        fun createReview(memberId: Long, countryCode: String?, foodId: Long, rating: Int): Long {
            val token = accessToken(memberId, countryCode)
            val response = mockMvc.post("/api/v1/reviews") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("foodId" to foodId, "rating" to rating))
            }.andReturn().response.getContentAsString(Charsets.UTF_8)
            return mapper.readTree(response).path("payload").path("reviewId").asLong()
        }

        fun deleteReview(memberId: Long, reviewId: Long) {
            mockMvc.delete("/api/v1/reviews/$reviewId") {
                header("Authorization", "Bearer ${accessToken(memberId)}")
            }.andExpect { status { isOk() } }
        }

        fun detail(foodId: Long, token: String? = null): ResultActionsDsl =
            mockMvc.get("/api/v1/foods/$foodId") {
                param("lang", "ko")
                token?.let { header("Authorization", "Bearer $it") }
            }

        given("음식 상세 평점 확장 — GET /api/v1/foods/{foodId}") {
            `when`("별점 4·5·2 리뷰가 있는 음식을 국적 미보유 회원이 조회하면") {
                then("전체 평균 3.7(소수 1자리 반올림)·리뷰 수 3·같은 국적 평점 null 을 준다") {
                    seedFood(900L, "평점김치찌개")
                    createReview(900L, "KR", 900L, 4)
                    createReview(901L, "KR", 900L, 5)
                    createReview(902L, "VN", 900L, 2)

                    detail(900L, accessToken(909L, null)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.averageRating") { value(3.7) }
                        jsonPath("$.payload.review.reviewCount") { value(3) }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(null) }
                    }
                }
            }
            `when`("KR 국적 회원이 같은 음식을 조회하면") {
                then("KR 스냅샷 리뷰(4·5)의 평균 4.5 를 같은 국적 평점으로 준다") {
                    detail(900L, accessToken(903L, "KR")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.averageRating") { value(3.7) }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(4.5) }
                    }
                }
            }
            `when`("해당 국적 리뷰가 없는 회원이 조회하면") {
                then("같은 국적 평점은 null 이다") {
                    detail(900L, accessToken(905L, "JP")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(null) }
                    }
                }
            }
            `when`("리뷰가 없는 음식을 회원이 조회하면") {
                then("평균 null·리뷰 수 0 을 준다") {
                    seedFood(910L, "평점순두부")
                    detail(910L, accessToken(904L, null)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.averageRating") { value(null) }
                        jsonPath("$.payload.review.reviewCount") { value(0) }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(null) }
                    }
                }
            }
            `when`("리뷰를 삭제하면") {
                then("평균·리뷰 수 집계에서 즉시 빠진다") {
                    seedFood(911L, "평점비빔밥")
                    createReview(906L, "KR", 911L, 5)
                    val deleted = createReview(906L, "KR", 911L, 1)
                    deleteReview(906L, deleted)

                    detail(911L, accessToken(904L, null)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.averageRating") { value(5.0) }
                        jsonPath("$.payload.review.reviewCount") { value(1) }
                    }
                }
            }
            `when`("작성 후 국적을 바꾼 회원의 과거 리뷰가 있으면") {
                then("과거 리뷰는 작성 시점 국적 평점에 그대로 포함된다") {
                    seedFood(912L, "평점불고기")
                    createReview(907L, "TH", 912L, 3)
                    seedMember(907L, "US")

                    detail(912L, accessToken(908L, "TH")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(3.0) }
                    }
                }
            }
        }
    }
}
