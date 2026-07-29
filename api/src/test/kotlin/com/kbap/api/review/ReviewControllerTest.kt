package com.kbap.api.review

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ReviewControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val path = "/api/v1/reviews"

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
                    ps.setString(2, "review-test-$memberId")
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

        fun seedVerifiedImage(memberId: Long, imagePath: String): Unit =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO uploaded_image (member_id, object_path, content_type, size_bytes,
                                                status, created_at, updated_at)
                    VALUES (?, ?, 'image/jpeg', 1024, 'ACTIVE', NOW(6), NOW(6))
                    """,
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.setString(2, imagePath)
                    ps.executeUpdate()
                }
            }

        fun rankingCounts(memberId: Long): Pair<Int, Int> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT review_count, unique_reviewed_food_count FROM member WHERE id = ?",
                ).use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeQuery().use { rs ->
                        rs.next().shouldBeTrue()
                        rs.getInt(1) to rs.getInt(2)
                    }
                }
            }

        fun createBody(
            foodId: Long?,
            rating: Int? = 4,
            content: String? = null,
            imagePaths: List<String>? = null,
        ): String = mapper.writeValueAsString(
            buildMap {
                foodId?.let { put("foodId", it) }
                rating?.let { put("rating", it) }
                content?.let { put("content", it) }
                imagePaths?.let { put("imagePaths", it) }
            },
        )

        fun create(token: String?, body: String): ResultActionsDsl =
            mockMvc.post(path) {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        fun update(token: String, reviewId: Long, body: String): ResultActionsDsl =
            mockMvc.patch("$path/$reviewId") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        fun remove(token: String, reviewId: Long): ResultActionsDsl =
            mockMvc.delete("$path/$reviewId") {
                header("Authorization", "Bearer $token")
            }

        fun reviewIdOf(result: ResultActionsDsl): Long =
            mapper.readTree(result.andReturn().response.getContentAsString(Charsets.UTF_8))
                .path("payload").path("reviewId").asLong()

        fun createReview(token: String, foodId: Long, rating: Int = 4): Long =
            reviewIdOf(create(token, createBody(foodId = foodId, rating = rating)))

        given("리뷰 작성 API — POST /api/v1/reviews") {
            seedFood(700L, "김치찌개")

            `when`("별점만으로 작성하면") {
                then("200 과 리뷰를 반환한다") {
                    val token = accessToken(700L)
                    create(token, createBody(foodId = 700L, rating = 5)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                        jsonPath("$.payload.rating") { value(5) }
                        jsonPath("$.payload.foodId") { value(700) }
                        jsonPath("$.payload.content") { value(null) }
                        jsonPath("$.payload.imageUrls.length()") { value(0) }
                    }
                }
            }
            `when`("본문과 본인 업로드 사진 2장으로 작성하면") {
                then("200 과 사진 URL 2건을 반환한다") {
                    val token = accessToken(701L)
                    seedVerifiedImage(701L, "images/review/2026/07/701_a.jpg")
                    seedVerifiedImage(701L, "images/review/2026/07/701_b.jpg")
                    create(
                        token,
                        createBody(
                            foodId = 700L,
                            rating = 4,
                            content = "맛있어요",
                            imagePaths = listOf("images/review/2026/07/701_a.jpg", "images/review/2026/07/701_b.jpg"),
                        ),
                    ).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.content") { value("맛있어요") }
                        jsonPath("$.payload.imageUrls.length()") { value(2) }
                    }
                }
            }
            `when`("국적 보유 회원이 작성하면") {
                then("작성 시점 국적 스냅샷이 담긴다") {
                    val token = accessToken(702L, countryCode = "VN")
                    create(token, createBody(foodId = 700L)).andExpect {
                        jsonPath("$.payload.authorCountryCode") { value("VN") }
                    }
                }
            }
            `when`("국적 미보유 회원이 작성하면") {
                then("국적 스냅샷이 null 이다") {
                    val token = accessToken(703L, countryCode = null)
                    create(token, createBody(foodId = 700L)).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.authorCountryCode") { value(null) }
                    }
                }
            }
            `when`("rating 이 0 또는 6 이면") {
                then("400 을 반환한다") {
                    val token = accessToken(704L)
                    create(token, createBody(foodId = 700L, rating = 0)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                    }
                    create(token, createBody(foodId = 700L, rating = 6)).andExpect { status { isBadRequest() } }
                }
            }
            `when`("content 가 1001자이면") {
                then("400 을 반환한다") {
                    val token = accessToken(704L)
                    create(token, createBody(foodId = 700L, content = "가".repeat(1001)))
                        .andExpect { status { isBadRequest() } }
                }
            }
            `when`("imagePaths 가 4장이면") {
                then("400 을 반환한다") {
                    val token = accessToken(704L)
                    create(token, createBody(foodId = 700L, imagePaths = listOf("a", "b", "c", "d")))
                        .andExpect { status { isBadRequest() } }
                }
            }
            `when`("foodId 를 누락하면") {
                then("400 을 반환한다") {
                    val token = accessToken(704L)
                    create(token, createBody(foodId = null)).andExpect { status { isBadRequest() } }
                }
            }
            `when`("토큰 없이 작성하면") {
                then("401 을 반환한다") {
                    create(null, createBody(foodId = 700L)).andExpect { status { isUnauthorized() } }
                }
            }
            `when`("존재하지 않는 음식에 작성하면") {
                then("400 FOOD-001 을 반환한다") {
                    val token = accessToken(704L)
                    create(token, createBody(foodId = 799L)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("FOOD-001") }
                    }
                }
            }
            `when`("타인이 업로드한 사진 경로로 작성하면") {
                then("400 REVIEW-003 을 반환한다") {
                    val token = accessToken(705L)
                    seedVerifiedImage(704L, "images/review/2026/07/704_other.jpg")
                    create(token, createBody(foodId = 700L, imagePaths = listOf("images/review/2026/07/704_other.jpg")))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("REVIEW-003") }
                        }
                }
            }
        }

        given("리뷰 수정 API — PATCH /api/v1/reviews/{reviewId}") {
            seedFood(710L, "된장찌개")

            `when`("본인 리뷰의 별점·본문을 바꾸면") {
                then("값이 반영되고 국적 스냅샷은 불변이다") {
                    val token = accessToken(710L, countryCode = "JP")
                    val reviewId = createReview(token, 710L, rating = 3)
                    update(token, reviewId, createBody(foodId = null, rating = 5, content = "수정했어요")).andExpect {
                        status { isOk() }
                        jsonPath("$.payload.rating") { value(5) }
                        jsonPath("$.payload.content") { value("수정했어요") }
                        jsonPath("$.payload.authorCountryCode") { value("JP") }
                    }
                }
            }
            `when`("타인 리뷰를 수정하면") {
                then("403 REVIEW-002 를 반환한다") {
                    val owner = accessToken(711L)
                    val other = accessToken(712L)
                    val reviewId = createReview(owner, 710L)
                    update(other, reviewId, createBody(foodId = null, rating = 5)).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("REVIEW-002") }
                    }
                }
            }
            `when`("존재하지 않는 리뷰를 수정하면") {
                then("400 REVIEW-001 을 반환한다") {
                    val token = accessToken(711L)
                    update(token, Long.MAX_VALUE, createBody(foodId = null, rating = 5)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("REVIEW-001") }
                    }
                }
            }
            `when`("타인이 업로드한 사진 경로로 수정하면") {
                then("400 REVIEW-003 을 반환한다") {
                    val token = accessToken(713L)
                    seedVerifiedImage(711L, "images/review/2026/07/711_other.jpg")
                    val reviewId = createReview(token, 710L)
                    update(token, reviewId, createBody(foodId = null, rating = 4, imagePaths = listOf("images/review/2026/07/711_other.jpg")))
                        .andExpect {
                            status { isBadRequest() }
                            jsonPath("$.code") { value("REVIEW-003") }
                        }
                }
            }
            `when`("삭제된 리뷰를 수정하면") {
                then("400 REVIEW-001 을 반환한다") {
                    val token = accessToken(714L)
                    val reviewId = createReview(token, 710L)
                    remove(token, reviewId).andExpect { status { isOk() } }
                    update(token, reviewId, createBody(foodId = null, rating = 5)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("REVIEW-001") }
                    }
                }
            }
        }

        given("리뷰 삭제 API — DELETE /api/v1/reviews/{reviewId}") {
            seedFood(720L, "비빔밥")

            `when`("본인 리뷰를 삭제하면") {
                then("200 success=true 를 반환한다") {
                    val token = accessToken(720L)
                    val reviewId = createReview(token, 720L)
                    remove(token, reviewId).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                }
            }
            `when`("타인 리뷰를 삭제하면") {
                then("403 REVIEW-002 를 반환한다") {
                    val owner = accessToken(721L)
                    val other = accessToken(722L)
                    val reviewId = createReview(owner, 720L)
                    remove(other, reviewId).andExpect {
                        status { isForbidden() }
                        jsonPath("$.code") { value("REVIEW-002") }
                    }
                }
            }
        }

        given("리뷰 랭킹 카운트 연동") {
            seedFood(730L, "불고기")
            seedFood(731L, "잡채")

            `when`("첫 리뷰를 작성하면") {
                then("리뷰 수 1·고유 음식 수 1 이 된다") {
                    val token = accessToken(730L)
                    createReview(token, 730L)
                    rankingCounts(730L) shouldBe (1 to 1)
                }
            }
            `when`("같은 음식에 두 번째 리뷰를 작성하면") {
                then("리뷰 수 2·고유 음식 수 1 이 된다") {
                    val token = accessToken(731L)
                    createReview(token, 730L)
                    createReview(token, 730L)
                    rankingCounts(731L) shouldBe (2 to 1)
                }
            }
            `when`("다른 음식에도 작성하면") {
                then("고유 음식 수가 2 가 된다") {
                    val token = accessToken(732L)
                    createReview(token, 730L)
                    createReview(token, 731L)
                    rankingCounts(732L) shouldBe (2 to 2)
                }
            }
            `when`("같은 음식 2건 중 1건을 삭제하면") {
                then("리뷰 수 1·고유 음식 수 1 이 유지된다") {
                    val token = accessToken(733L)
                    val first = createReview(token, 730L)
                    createReview(token, 730L)
                    remove(token, first).andExpect { status { isOk() } }
                    rankingCounts(733L) shouldBe (1 to 1)
                }
            }
            `when`("마지막 남은 리뷰를 삭제하면") {
                then("리뷰 수 0·고유 음식 수 0 이 된다") {
                    val token = accessToken(734L)
                    val reviewId = createReview(token, 730L)
                    remove(token, reviewId).andExpect { status { isOk() } }
                    rankingCounts(734L) shouldBe (0 to 0)
                }
            }
        }
    }
}
