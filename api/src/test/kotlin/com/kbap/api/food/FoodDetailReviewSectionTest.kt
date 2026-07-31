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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class FoodDetailReviewSectionTest : BehaviorSpec() {
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
                    ps.setString(2, "review-section-test-$memberId")
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

        fun createReview(memberId: Long, countryCode: String?, foodId: Long, rating: Int) {
            val token = accessToken(memberId, countryCode)
            mockMvc.post("/api/v1/reviews") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(mapOf("foodId" to foodId, "rating" to rating))
            }.andExpect { status { isOk() } }
        }

        fun detail(foodId: Long, token: String? = null): ResultActionsDsl =
            mockMvc.get("/api/v1/foods/$foodId") {
                param("lang", "ko")
                token?.let { header("Authorization", "Bearer $it") }
            }

        given("음식 상세 리뷰 섹션 — GET /api/v1/foods/{foodId}") {
            `when`("별점 4·5·2 리뷰가 있는 음식을 KR 회원이 조회하면") {
                then("리뷰 요약(전체 평균·개수·같은 국적 평균)이 review 묶음으로 내려간다") {
                    seedFood(920L, "리뷰섹션김치찌개")
                    createReview(920L, "KR", 920L, 4)
                    createReview(921L, "KR", 920L, 5)
                    createReview(922L, "VN", 920L, 2)

                    detail(920L, accessToken(923L, "KR")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.averageRating") { value(3.7) }
                        jsonPath("$.payload.review.reviewCount") { value(3) }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(4.5) }
                    }
                }
            }
            `when`("개편된 상세 응답을 조회하면") {
                then("리뷰 관련 최상위 평탄 필드는 존재하지 않는다") {
                    detail(920L, accessToken(923L, "KR")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.averageRating") { doesNotExist() }
                        jsonPath("$.payload.reviewCount") { doesNotExist() }
                        jsonPath("$.payload.sameCountryAverageRating") { doesNotExist() }
                    }
                }
            }
            `when`("리뷰가 있는 같은 음식을 비회원이 조회하면") {
                then("blur=true 이고 실수치 대신 기본값이 내려간다") {
                    detail(920L).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.blur") { value(true) }
                        jsonPath("$.payload.review.averageRating") { value(0.0) }
                        jsonPath("$.payload.review.reviewCount") { value(0) }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(0.0) }
                    }
                }
            }
            `when`("같은 음식을 회원이 조회하면") {
                then("blur=false 이고 실수치가 내려간다") {
                    detail(920L, accessToken(923L, "KR")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.blur") { value(false) }
                        jsonPath("$.payload.review.averageRating") { value(3.7) }
                        jsonPath("$.payload.review.reviewCount") { value(3) }
                    }
                }
            }
            `when`("활성 회원이 아닌 토큰(탈퇴 회원)으로 리뷰가 있는 음식을 조회하면") {
                then("비회원과 동일하게 blur=true 기본값이 내려간다") {
                    detail(920L, tokenIssuer.issueAccessToken(999L, MemberRole.USER)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.blur") { value(true) }
                        jsonPath("$.payload.review.averageRating") { value(0.0) }
                        jsonPath("$.payload.review.reviewCount") { value(0) }
                    }
                }
            }
            `when`("리뷰가 0건인 음식을 비회원이 조회하면") {
                then("리뷰 없음 상태에서도 blur=true 다") {
                    seedFood(931L, "리뷰섹션미역국")
                    detail(931L).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.blur") { value(true) }
                        jsonPath("$.payload.review.reviewCount") { value(0) }
                    }
                }
            }
            `when`("리뷰가 0건인 음식을 회원이 조회하면") {
                then("평균은 null 이 아니라 0.0·리뷰 수 0·같은 국적 평균 0.0 이 내려간다") {
                    seedFood(930L, "리뷰섹션순두부")
                    detail(930L, accessToken(923L, "KR")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.averageRating") { value(0.0) }
                        jsonPath("$.payload.review.reviewCount") { value(0) }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(0.0) }
                        jsonPath("$.payload.review.blur") { value(false) }
                    }
                }
            }
            `when`("전체 리뷰는 있으나 같은 국적 리뷰가 없는 회원이 조회하면") {
                then("같은 국적 평균만 0.0 이고 나머지는 실수치다") {
                    detail(920L, accessToken(924L, "JP")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.review.averageRating") { value(3.7) }
                        jsonPath("$.payload.review.reviewCount") { value(3) }
                        jsonPath("$.payload.review.sameCountryAverageRating") { value(0.0) }
                    }
                }
            }
        }
    }
}
