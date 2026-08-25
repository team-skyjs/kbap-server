package com.kbap.api.report

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.report.model.ReportReason
import com.kbap.common.domain.report.model.ReportTargetType
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
import org.springframework.test.web.servlet.post
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class)
class ReportControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var reportService: ReportService

    private val mapper: ObjectMapper = jacksonObjectMapper()

    init {
        val path = "/api/reports"

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
                    ps.setString(2, "report-test-$memberId")
                    ps.setString(3, "신고자$memberId")
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
                    ps.setString(2, "신고테스트음식$id")
                    ps.executeUpdate()
                }
            }

        fun seedReview(reviewId: Long, authorMemberId: Long, foodId: Long, status: String = "ACTIVE") {
            seedMember(authorMemberId)
            seedFood(foodId)
            dataSource.connection.use { c ->
                c.prepareStatement(
                    """
                    INSERT INTO food_review (id, member_id, food_id, rating, status, created_at, updated_at)
                    VALUES (?, ?, ?, 4, ?, NOW(6), NOW(6))
                    ON DUPLICATE KEY UPDATE status = VALUES(status)
                    """,
                ).use { ps ->
                    ps.setLong(1, reviewId)
                    ps.setLong(2, authorMemberId)
                    ps.setLong(3, foodId)
                    ps.setString(4, status)
                    ps.executeUpdate()
                }
            }
        }

        fun reportCountOf(reporterMemberId: Long, targetId: Long): Int =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM report WHERE reporter_member_id = ? AND target_type = 'REVIEW' AND target_id = ?",
                ).use { ps ->
                    ps.setLong(1, reporterMemberId)
                    ps.setLong(2, targetId)
                    ps.executeQuery().use { rs ->
                        rs.next().shouldBeTrue()
                        rs.getInt(1)
                    }
                }
            }

        fun body(
            targetType: String? = "REVIEW",
            targetId: Long? = null,
            reason: String? = "SPAM",
            detail: String? = null,
        ): String = mapper.writeValueAsString(
            buildMap {
                targetType?.let { put("targetType", it) }
                targetId?.let { put("targetId", it) }
                reason?.let { put("reason", it) }
                detail?.let { put("detail", it) }
            },
        )

        fun report(token: String?, body: String): ResultActionsDsl =
            mockMvc.post(path) {
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = body
            }

        given("신고 접수 API — POST /api/reports") {
            `when`("타인의 리뷰를 사유와 함께 신고하면") {
                then("200 을 반환하고 신고가 저장된다") {
                    seedReview(reviewId = 8101L, authorMemberId = 8151L, foodId = 8181L)
                    val token = accessToken(8102L)

                    report(token, body(targetId = 8101L, reason = "SPAM", detail = "광고 링크")).andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                    reportCountOf(8102L, 8101L) shouldBe 1
                }
            }

            `when`("상세 설명이 500자면") {
                then("허용된다") {
                    seedReview(reviewId = 8103L, authorMemberId = 8151L, foodId = 8181L)
                    val token = accessToken(8104L)

                    report(token, body(targetId = 8103L, detail = "가".repeat(500))).andExpect {
                        status { isOk() }
                    }
                }
            }

            `when`("상세 설명이 500자를 넘으면") {
                then("400 으로 거절한다") {
                    seedReview(reviewId = 8105L, authorMemberId = 8151L, foodId = 8181L)
                    val token = accessToken(8106L)

                    report(token, body(targetId = 8105L, detail = "가".repeat(501))).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.success") { value(false) }
                    }
                    reportCountOf(8106L, 8105L) shouldBe 0
                }
            }

            `when`("자기가 작성한 리뷰를 신고하면") {
                then("400 REPORT-001 로 거절한다") {
                    seedReview(reviewId = 8107L, authorMemberId = 8108L, foodId = 8181L)
                    val token = accessToken(8108L)

                    report(token, body(targetId = 8107L)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("REPORT-001") }
                    }
                    reportCountOf(8108L, 8107L) shouldBe 0
                }
            }

            `when`("이미 신고한 리뷰를 다시 신고하면") {
                then("409 REPORT-002 로 거절하고 신고는 1건을 유지한다") {
                    seedReview(reviewId = 8109L, authorMemberId = 8151L, foodId = 8181L)
                    val token = accessToken(8110L)

                    report(token, body(targetId = 8109L)).andExpect { status { isOk() } }
                    report(token, body(targetId = 8109L, reason = "ABUSE")).andExpect {
                        status { isConflict() }
                        jsonPath("$.code") { value("REPORT-002") }
                    }
                    reportCountOf(8110L, 8109L) shouldBe 1
                }
            }

            `when`("존재하지 않는 리뷰를 신고하면") {
                then("404 REPORT-003 으로 거절한다") {
                    val token = accessToken(8111L)

                    report(token, body(targetId = 999_999L)).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("REPORT-003") }
                    }
                }
            }

            `when`("삭제된 리뷰를 신고하면") {
                then("404 REPORT-003 으로 거절한다") {
                    seedReview(reviewId = 8112L, authorMemberId = 8151L, foodId = 8181L, status = "DELETED")
                    val token = accessToken(8113L)

                    report(token, body(targetId = 8112L)).andExpect {
                        status { isNotFound() }
                        jsonPath("$.code") { value("REPORT-003") }
                    }
                }
            }

            `when`("targetType 을 누락하면") {
                then("400 으로 거절한다") {
                    val token = accessToken(8114L)

                    report(token, body(targetType = null, targetId = 8101L)).andExpect {
                        status { isBadRequest() }
                    }
                }
            }

            `when`("targetId 를 누락하면") {
                then("400 으로 거절한다") {
                    val token = accessToken(8114L)

                    report(token, body(targetId = null)).andExpect {
                        status { isBadRequest() }
                    }
                }
            }

            `when`("reason 을 누락하면") {
                then("400 으로 거절한다") {
                    val token = accessToken(8114L)

                    report(token, body(targetId = 8101L, reason = null)).andExpect {
                        status { isBadRequest() }
                    }
                }
            }

            `when`("정의되지 않은 reason 값이면") {
                then("400 으로 거절한다") {
                    val token = accessToken(8114L)

                    report(token, body(targetId = 8101L, reason = "UNKNOWN_REASON")).andExpect {
                        status { isBadRequest() }
                    }
                }
            }

            `when`("정의되지 않은 targetType 값이면") {
                then("400 으로 거절한다") {
                    val token = accessToken(8114L)

                    report(token, body(targetType = "PLACE", targetId = 8101L)).andExpect {
                        status { isBadRequest() }
                    }
                }
            }

            `when`("탈퇴한 회원의 기존 토큰으로 신고하면") {
                then("400 MEMBER-003 으로 거절하고 신고는 저장되지 않는다") {
                    seedReview(reviewId = 8115L, authorMemberId = 8151L, foodId = 8181L)
                    val token = accessToken(8116L)
                    dataSource.connection.use { c ->
                        c.createStatement().use { it.execute("UPDATE member SET status = 'DELETED' WHERE id = 8116") }
                    }

                    report(token, body(targetId = 8115L)).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("MEMBER-003") }
                    }
                    reportCountOf(8116L, 8115L) shouldBe 0
                }
            }

            `when`("토큰 없이 신고하면") {
                then("401 로 거절한다 — 인증 필터에 /reports 가 등록돼 있어야 한다") {
                    report(null, body(targetId = 8101L)).andExpect {
                        status { isUnauthorized() }
                    }
                }
            }
        }

        given("동시 중복 신고 경합") {
            `when`("같은 회원이 같은 대상을 두 스레드에서 동시에 신고하면") {
                then("한 건만 저장되고 나머지 한 건은 REPORT-002 로 거절된다") {
                    seedReview(reviewId = 8117L, authorMemberId = 8151L, foodId = 8181L)
                    seedMember(8118L)

                    val start = CountDownLatch(1)
                    val executor = Executors.newFixedThreadPool(2)
                    val outcomes = (1..2).map {
                        executor.submit<Result<Unit>> {
                            start.await()
                            runCatching {
                                reportService.createReport(8118L, ReportTargetType.REVIEW, 8117L, ReportReason.SPAM, null)
                            }
                        }
                    }
                    start.countDown()
                    val results = outcomes.map { it.get() }
                    executor.shutdown()

                    results.count { it.isSuccess } shouldBe 1
                    val rejected = results.single { it.isFailure }.exceptionOrNull()
                    (rejected as BusinessException).errorCode shouldBe ErrorCode.REPORT_DUPLICATED
                    reportCountOf(8118L, 8117L) shouldBe 1
                }
            }
        }
    }
}
