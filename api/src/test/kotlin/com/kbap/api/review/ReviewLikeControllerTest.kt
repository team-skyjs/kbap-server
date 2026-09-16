package com.kbap.api.review

import com.kbap.api.IntegrationTest
import com.kbap.api.notification.FakePushSender
import com.kbap.api.notification.HelpfulPushListener
import com.kbap.api.review.ReviewLiked
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationSetting
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

@IntegrationTest
class ReviewLikeControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var fakePushSender: FakePushSender

    @Autowired
    private lateinit var deviceRepository: NotificationDeviceJpaRepository

    @Autowired
    private lateinit var settingRepository: NotificationSettingJpaRepository

    @Autowired
    private lateinit var helpfulPushListener: HelpfulPushListener

    init {
        val reviewIdSeq = AtomicLong(9000L)

        beforeSpec {
            fakePushSender.reset()
            dataSource.connection.use { c ->
                c.createStatement().use { st ->
                    st.execute("DELETE FROM notification_dispatch")
                    st.execute("DELETE FROM notification")
                    st.execute("DELETE FROM notification_setting")
                    st.execute("DELETE FROM notification_device")
                }
            }
        }

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
                                      description_translations, ingredients, content_status, status,
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
            mockMvc.post("/api/reviews/$reviewId/like") {
                token?.let { header("Authorization", "Bearer $it") }
                param("liked", "true")
            }

        fun unlike(reviewId: Long, token: String): ResultActionsDsl =
            mockMvc.post("/api/reviews/$reviewId/like") {
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
                    mockMvc.post("/api/reviews/$reviewId/like") {
                        header("Authorization", "Bearer ${accessToken(8008L)}")
                    }.andExpect { status { isBadRequest() } }
                }
            }
        }

        fun device(memberId: Long, lang: String = "en"): NotificationDevice {
            seedMember(memberId)
            return deviceRepository.save(
                NotificationDevice.register("inst-$memberId-$lang", "ExponentPushToken[$memberId-$lang]", DevicePlatform.IOS, lang, memberId),
            )
        }

        fun activity(device: NotificationDevice, enabled: Boolean = true) {
            settingRepository.save(NotificationSetting(memberId = device.memberId!!, installationId = device.installationId, activity = enabled))
        }

        fun helpfulRows(authorId: Long): Int =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT COUNT(*) FROM notification WHERE member_id = ? AND type = 'HELPFUL' AND status = 'ACTIVE'").use { ps ->
                    ps.setLong(1, authorId)
                    ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun dispatchStatuses(authorId: Long): List<String> =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    SELECT d.dispatch_status FROM notification_dispatch d
                    JOIN notification n ON n.id = d.notification_id
                    WHERE n.member_id = ? AND n.type = 'HELPFUL' ORDER BY d.id
                    """,
                ).use { ps ->
                    ps.setLong(1, authorId)
                    ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
                }
            }

        fun ageLikeRow(reviewId: Long, memberId: Long, minutes: Int) {
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "UPDATE review_like SET updated_at = DATE_SUB(updated_at, INTERVAL ? MINUTE) WHERE review_id = ? AND member_id = ?",
                ).use { ps ->
                    ps.setInt(1, minutes)
                    ps.setLong(2, reviewId)
                    ps.setLong(3, memberId)
                    ps.executeUpdate()
                }
            }
        }

        suspend fun sentEventually(count: Int) = eventually(5.seconds) { fakePushSender.sent shouldHaveSize count }
        suspend fun sentStays(count: Int) = continually(1.seconds) { fakePushSender.sent shouldHaveSize count }

        given("좋아요 알림") {
            `when`("활동 알림을 켠 작성자의 리뷰에 다른 회원이 좋아요를 등록하면") {
                val author = 8201L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author, "en"))
                fakePushSender.reset()
                then("작성자 기기로 activity 채널 HELPFUL 푸시가 가고 알림함·발송 이력이 남는다") {
                    like(reviewId, accessToken(8202L)).andExpect { status { isOk() } }
                    sentEventually(1)
                    val message = fakePushSender.sent.single()
                    message.to shouldBe "ExponentPushToken[$author-en]"
                    message.channelId shouldBe "activity"
                    message.data["type"] shouldBe "HELPFUL"
                    message.data["reviewId"] shouldBe reviewId
                    message.data["notificationId"].shouldNotBeNull()
                    message.body shouldContain "좋아요테스트음식9000"
                    helpfulRows(author) shouldBe 1
                    dispatchStatuses(author) shouldBe listOf("SENT")
                }
            }
            `when`("작성자가 활동 알림을 켠 기기 두 대를 쓰면") {
                val author = 8203L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author, "ko"))
                activity(device(author, "en"))
                fakePushSender.reset()
                then("기기마다 한 건씩 간다") {
                    like(reviewId, accessToken(8204L)).andExpect { status { isOk() } }
                    sentEventually(2)
                    fakePushSender.sent.map { it.to }.toSet() shouldHaveSize 2
                    helpfulRows(author) shouldBe 2
                }
            }
            `when`("존재하지 않는 리뷰에 등록해 요청이 실패하면") {
                fakePushSender.reset()
                then("알림은 만들어지지 않는다") {
                    like(999998L, accessToken(8205L)).andExpect { status { isBadRequest() } }
                    sentStays(0)
                }
            }
            `when`("작성자 본인이 자기 리뷰에 좋아요를 등록하면") {
                val author = 8211L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author))
                fakePushSender.reset()
                then("알림은 만들어지지 않는다") {
                    like(reviewId, accessToken(author)).andExpect { status { isOk() } }
                    sentStays(0)
                    helpfulRows(author) shouldBe 0
                }
            }
            `when`("좋아요를 취소하면") {
                val author = 8212L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author))
                fakePushSender.reset()
                then("알림은 만들어지지 않는다") {
                    unlike(reviewId, accessToken(8213L)).andExpect { status { isOk() } }
                    sentStays(0)
                }
            }
            `when`("이미 좋아요한 리뷰에 다시 등록하면") {
                val author = 8214L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author))
                fakePushSender.reset()
                then("첫 등록에만 알림이 가고 재호출엔 가지 않는다") {
                    val token = accessToken(8215L)
                    like(reviewId, token).andExpect { status { isOk() } }
                    sentEventually(1)
                    like(reviewId, token).andExpect { status { isOk() } }
                    sentStays(1)
                }
            }
            `when`("같은 리뷰에 다른 회원이 잇달아 좋아요를 등록하면") {
                val author = 8221L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author))
                fakePushSender.reset()
                then("묶지 않고 각각 알림이 간다") {
                    like(reviewId, accessToken(8222L)).andExpect { status { isOk() } }
                    sentEventually(1)
                    like(reviewId, accessToken(8223L)).andExpect { status { isOk() } }
                    sentEventually(2)
                    helpfulRows(author) shouldBe 2
                }
            }
            `when`("작성자 기기의 활동 알림이 꺼져 있으면") {
                val author = 8216L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author), enabled = false)
                fakePushSender.reset()
                then("알림은 만들어지지 않는다") {
                    like(reviewId, accessToken(8217L)).andExpect { status { isOk() } }
                    sentStays(0)
                    helpfulRows(author) shouldBe 0
                }
            }
            `when`("작성자에게 기기가 없으면") {
                val author = 8218L
                val reviewId = seedReview(authorMemberId = author)
                fakePushSender.reset()
                then("알림함 행도 만들어지지 않는다") {
                    like(reviewId, accessToken(8219L)).andExpect { status { isOk() } }
                    sentStays(0)
                    helpfulRows(author) shouldBe 0
                }
            }
        }

        given("같은 회원의 취소·재좋아요 반복") {
            `when`("5분 안에 좋아요·취소·좋아요를 반복하면") {
                val author = 8224L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author))
                fakePushSender.reset()
                then("첫 등록에만 알림이 가고 재등록엔 가지 않는다") {
                    val token = accessToken(8225L)
                    like(reviewId, token).andExpect { status { isOk() } }
                    sentEventually(1)
                    unlike(reviewId, token).andExpect { status { isOk() } }
                    like(reviewId, token).andExpect { status { isOk() } }
                    sentStays(1)
                    unlike(reviewId, token).andExpect { status { isOk() } }
                    like(reviewId, token).andExpect { status { isOk() } }
                    sentStays(1)
                    helpfulRows(author) shouldBe 1
                }
            }
            `when`("취소한 지 5분이 지나 다시 좋아요하면") {
                val author = 8226L
                val reviewId = seedReview(authorMemberId = author)
                activity(device(author))
                fakePushSender.reset()
                then("다시 알림이 간다") {
                    val liker = 8227L
                    val token = accessToken(liker)
                    like(reviewId, token).andExpect { status { isOk() } }
                    sentEventually(1)
                    unlike(reviewId, token).andExpect { status { isOk() } }
                    ageLikeRow(reviewId, liker, minutes = 6)
                    like(reviewId, token).andExpect { status { isOk() } }
                    sentEventually(2)
                }
            }
        }

        given("좋아요 알림 리스너 실패 격리") {
            `when`("존재하지 않는 음식의 이벤트를 직접 처리하면") {
                fakePushSender.reset()
                then("예외가 전파되지 않고 발송도 없다") {
                    shouldNotThrowAny { helpfulPushListener.handle(ReviewLiked(reviewId = 1L, authorMemberId = 8299L, foodId = 999999L)) }
                    fakePushSender.sent shouldHaveSize 0
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
