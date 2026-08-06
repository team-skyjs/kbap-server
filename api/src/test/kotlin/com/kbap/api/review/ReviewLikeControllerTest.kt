package com.kbap.api.review

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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ReviewLikeControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    init {
        val reviewIdSeq = AtomicLong(9000L)

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
                    ps.setString(2, "like-test-$memberId")
                    ps.setString(3, "좋아요$memberId")
                    ps.executeUpdate()
                }
            }

        fun accessToken(memberId: Long): String {
            seedMember(memberId)
            return tokenIssuer.issueAccessToken(memberId, MemberRole.USER)
        }

        fun seedFood(id: Long): Unit =
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
                    ps.setString(2, "좋아요테스트음식$id")
                    ps.executeUpdate()
                }
            }

        fun seedReview(authorMemberId: Long, foodId: Long = 9000L, status: String = "ACTIVE"): Long {
            seedMember(authorMemberId)
            seedFood(foodId)
            val reviewId = reviewIdSeq.incrementAndGet()
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food_review (id, member_id, food_id, rating, version, status, created_at, updated_at)
                    VALUES (?, ?, ?, 4, 0, ?, NOW(6), NOW(6))
                    """,
                ).use { ps ->
                    ps.setLong(1, reviewId)
                    ps.setLong(2, authorMemberId)
                    ps.setLong(3, foodId)
                    ps.setString(4, status)
                    ps.executeUpdate()
                }
            }
            return reviewId
        }

        fun likeRows(reviewId: Long, memberId: Long): Pair<Int, Int> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    SELECT COUNT(*), COALESCE(SUM(status = 'ACTIVE'), 0)
                    FROM review_like WHERE review_id = ? AND member_id = ?
                    """,
                ).use { ps ->
                    ps.setLong(1, reviewId)
                    ps.setLong(2, memberId)
                    ps.executeQuery().use { rs ->
                        rs.next().shouldBeTrue()
                        rs.getInt(1) to rs.getInt(2)
                    }
                }
            }

        fun like(reviewId: Long, token: String?): ResultActionsDsl =
            mockMvc.post("/api/v1/reviews/$reviewId/like") {
                token?.let { header("Authorization", "Bearer $it") }
                param("liked", "true")
            }

        fun unlike(reviewId: Long, token: String): ResultActionsDsl =
            mockMvc.post("/api/v1/reviews/$reviewId/like") {
                header("Authorization", "Bearer $token")
                param("liked", "false")
            }

        given("좋아요 등록") {
            `when`("처음 등록하면") {
                val memberId = 8001L
                val reviewId = seedReview(authorMemberId = 8101L)
                then("200 성공하고 ACTIVE 행 1건이 생긴다") {
                    like(reviewId, accessToken(memberId)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                    likeRows(reviewId, memberId) shouldBe (1 to 1)
                }
            }
            `when`("이미 좋아요한 리뷰에 다시 등록하면") {
                val memberId = 8002L
                val reviewId = seedReview(authorMemberId = 8102L)
                then("200 성공하되 행은 여전히 1건이다") {
                    val token = accessToken(memberId)
                    like(reviewId, token).andExpect { status { isOk() } }
                    like(reviewId, token).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                    likeRows(reviewId, memberId) shouldBe (1 to 1)
                }
            }
            `when`("존재하지 않는 리뷰에 등록하면") {
                then("400 REVIEW-001 을 준다") {
                    like(999999L, accessToken(8003L)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                        jsonPath("$.code") { value("REVIEW-001") }
                    }
                }
            }
            `when`("삭제된 리뷰에 등록하면") {
                val memberId = 8004L
                val reviewId = seedReview(authorMemberId = 8104L, status = "DELETED")
                then("400 REVIEW-001 을 준다") {
                    like(reviewId, accessToken(memberId)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("REVIEW-001") }
                    }
                }
            }
            `when`("토큰 없이 등록하면") {
                then("401 을 준다") {
                    like(1L, token = null).andExpect { status { isUnauthorized() } }
                }
            }
            `when`("liked 파라미터 없이 요청하면") {
                then("400 을 준다") {
                    val reviewId = seedReview(authorMemberId = 8108L)
                    mockMvc.post("/api/v1/reviews/$reviewId/like") {
                        header("Authorization", "Bearer ${accessToken(8008L)}")
                    }.andExpect { status { isBadRequest() } }
                }
            }
        }

        given("좋아요 취소") {
            `when`("좋아요한 리뷰를 취소하면") {
                val memberId = 8005L
                val reviewId = seedReview(authorMemberId = 8105L)
                then("200 성공하고 활성 행이 0건이 된다") {
                    val token = accessToken(memberId)
                    like(reviewId, token).andExpect { status { isOk() } }
                    unlike(reviewId, token).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                    likeRows(reviewId, memberId) shouldBe (1 to 0)
                }
            }
            `when`("좋아요하지 않은 리뷰를 취소하면") {
                val memberId = 8006L
                val reviewId = seedReview(authorMemberId = 8106L)
                then("200 성공 no-op 이다") {
                    unlike(reviewId, accessToken(memberId)).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                    likeRows(reviewId, memberId) shouldBe (0 to 0)
                }
            }
            `when`("취소한 리뷰에 다시 등록하면") {
                val memberId = 8007L
                val reviewId = seedReview(authorMemberId = 8107L)
                then("같은 행이 부활해 총 1건·활성 1건이다") {
                    val token = accessToken(memberId)
                    like(reviewId, token).andExpect { status { isOk() } }
                    unlike(reviewId, token).andExpect { status { isOk() } }
                    like(reviewId, token).andExpect { status { isOk() } }
                    likeRows(reviewId, memberId) shouldBe (1 to 1)
                }
            }
        }
    }
}
