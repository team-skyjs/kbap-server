package com.kbap.api.report

import com.kbap.api.IntegrationTest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@IntegrationTest
class ReportControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer


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

        val DEFAULT_INSTALLATION = "report-test-install-0000"

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

        fun installationReportCountOf(installationId: String, targetId: Long): Int =
            dataSource.connection.use { c ->
                c.prepareStatement(
                    "SELECT COUNT(*) FROM report WHERE reporter_installation_id = ? AND target_type = 'REVIEW' AND target_id = ?",
                ).use { ps ->
                    ps.setString(1, installationId)
                    ps.setLong(2, targetId)
                    ps.executeQuery().use { rs -> rs.next().shouldBeTrue(); rs.getInt(1) }
                }
            }

        fun report(token: String?, body: String, installationId: String? = DEFAULT_INSTALLATION): ResultActionsDsl =
            mockMvc.post(path) {
                token?.let { header("Authorization", "Bearer $it") }
                installationId?.let { header("X-Installation-Id", it) }
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

            `when`("이미 신고한 리뷰를 같은 회원이 다시 신고하면") {
                then("재신고가 허용돼 2건이 쌓인다") {
                    seedReview(reviewId = 8109L, authorMemberId = 8151L, foodId = 8181L)
                    val token = accessToken(8110L)

                    report(token, body(targetId = 8109L)).andExpect { status { isOk() } }
                    report(token, body(targetId = 8109L, reason = "ABUSE")).andExpect { status { isOk() } }

                    reportCountOf(8110L, 8109L) shouldBe 2
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

                    report(token, body(targetType = "POST", targetId = 8101L)).andExpect {
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

            `when`("게스트가 installationId 와 함께 신고하면") {
                then("201(200) 로 접수되고 게스트 신고가 저장된다") {
                    seedReview(reviewId = 8120L, authorMemberId = 8151L, foodId = 8181L)

                    report(null, body(targetId = 8120L), installationId = "guest-aaaaaaaa-1111").andExpect {
                        status { isOk() }
                        jsonPath("$.success") { value(true) }
                    }
                    installationReportCountOf("guest-aaaaaaaa-1111", 8120L) shouldBe 1
                }
            }

            `when`("같은 게스트가 같은 대상을 다시 신고하면") {
                then("재신고가 허용돼 2건이 쌓인다") {
                    seedReview(reviewId = 8121L, authorMemberId = 8151L, foodId = 8181L)

                    report(null, body(targetId = 8121L), installationId = "guest-dup-2222").andExpect { status { isOk() } }
                    report(null, body(targetId = 8121L, reason = "ABUSE"), installationId = "guest-dup-2222").andExpect {
                        status { isOk() }
                    }
                    installationReportCountOf("guest-dup-2222", 8121L) shouldBe 2
                }
            }

            `when`("설치 ID 헤더 없이 신고하면") {
                then("회원·게스트 모두 400 REPORT-004 로 거절한다") {
                    seedReview(reviewId = 8122L, authorMemberId = 8151L, foodId = 8181L)
                    report(accessToken(8125L), body(targetId = 8122L), installationId = null).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("REPORT-004") }
                    }

                    report(null, body(targetId = 8122L), installationId = null).andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("REPORT-004") }
                    }
                }
            }

            `when`("Authorization 헤더가 아예 없으면") {
                then("게스트 신고로 접수된다") {
                    seedReview(reviewId = 8141L, authorMemberId = 8151L, foodId = 8181L)

                    report(null, body(targetId = 8141L), installationId = "auth-none-01").andExpect { status { isOk() } }
                    installationReportCountOf("auth-none-01", 8141L) shouldBe 1
                }
            }

            `when`("유효한 회원 토큰이 있으면") {
                then("회원 신고로 접수된다") {
                    seedReview(reviewId = 8142L, authorMemberId = 8151L, foodId = 8181L)

                    report(accessToken(8143L), body(targetId = 8142L), installationId = "auth-member-02").andExpect { status { isOk() } }
                    reportCountOf(8143L, 8142L) shouldBe 1
                }
            }

            `when`("Bearer 형식이지만 위조된 토큰이면") {
                then("401 로 거절하고 게스트로 전환하지 않는다") {
                    seedReview(reviewId = 8144L, authorMemberId = 8151L, foodId = 8181L)

                    mockMvc.post(path) {
                        header("Authorization", "Bearer not-a-real-token")
                        header("X-Installation-Id", "auth-forged-03")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(targetId = 8144L)
                    }.andExpect { status { isUnauthorized() } }

                    installationReportCountOf("auth-forged-03", 8144L) shouldBe 0
                }
            }

            `when`("Authorization 헤더 형식이 Bearer 가 아니면") {
                then("401 로 거절한다 — 게스트 신고로 저장되지 않는다") {
                    seedReview(reviewId = 8145L, authorMemberId = 8151L, foodId = 8181L)

                    mockMvc.post(path) {
                        header("Authorization", "Token abcdef")
                        header("X-Installation-Id", "auth-malformed-04")
                        contentType = MediaType.APPLICATION_JSON
                        content = body(targetId = 8145L)
                    }.andExpect {
                        status { isUnauthorized() }
                        jsonPath("$.code") { value("AUTH-003") }
                    }

                    installationReportCountOf("auth-malformed-04", 8145L) shouldBe 0
                }
            }

            `when`("설치 ID 헤더가 비어 있으면") {
                then("누락(REPORT-004)이 아니라 형식 위반(COMMON-002)으로 거절한다") {
                    seedReview(reviewId = 8124L, authorMemberId = 8151L, foodId = 8181L)

                    report(null, body(targetId = 8124L), installationId = "   ").andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("COMMON-002") }
                    }
                }
            }

            `when`("회원이 신고한 뒤 같은 설치에서 게스트로 같은 대상을 신고하면") {
                then("둘 다 접수된다") {
                    seedReview(reviewId = 8123L, authorMemberId = 8151L, foodId = 8181L)
                    val token = accessToken(8124L)

                    report(token, body(targetId = 8123L), installationId = "shared-device-3333").andExpect { status { isOk() } }
                    report(null, body(targetId = 8123L), installationId = "shared-device-3333").andExpect { status { isOk() } }

                    reportCountOf(8124L, 8123L) shouldBe 1
                    installationReportCountOf("shared-device-3333", 8123L) shouldBe 2
                }
            }

            `when`("회원이 신고한 뒤 다른 설치에서 게스트가 같은 대상을 신고하면") {
                then("서로 다른 기기라 둘 다 접수된다") {
                    seedReview(reviewId = 8126L, authorMemberId = 8151L, foodId = 8181L)
                    val token = accessToken(8127L)

                    report(token, body(targetId = 8126L), installationId = "member-device-4444").andExpect { status { isOk() } }
                    report(null, body(targetId = 8126L), installationId = "guest-device-5555").andExpect { status { isOk() } }

                    reportCountOf(8127L, 8126L) shouldBe 1
                    installationReportCountOf("guest-device-5555", 8126L) shouldBe 1
                }
            }

            `when`("공용 폰의 두 계정이 같은 대상을 각각 신고하면") {
                then("둘 다 접수된다") {
                    seedReview(reviewId = 8130L, authorMemberId = 8151L, foodId = 8181L)

                    report(accessToken(8131L), body(targetId = 8130L), installationId = "family-phone-6666").andExpect { status { isOk() } }
                    report(accessToken(8132L), body(targetId = 8130L), installationId = "family-phone-6666").andExpect { status { isOk() } }

                    reportCountOf(8131L, 8130L) shouldBe 1
                    reportCountOf(8132L, 8130L) shouldBe 1
                }
            }

            `when`("게스트로 신고한 뒤 같은 설치에서 로그인해 같은 대상을 신고하면") {
                then("2건이 접수된다(게스트 신고는 계정에 귀속되지 않는다)") {
                    seedReview(reviewId = 8136L, authorMemberId = 8151L, foodId = 8181L)

                    report(null, body(targetId = 8136L), installationId = "guest-then-login-77").andExpect { status { isOk() } }
                    report(accessToken(8137L), body(targetId = 8136L), installationId = "guest-then-login-77").andExpect { status { isOk() } }

                    installationReportCountOf("guest-then-login-77", 8136L) shouldBe 2
                    reportCountOf(8137L, 8136L) shouldBe 1
                }
            }

            `when`("다른 회원이 다른 설치에서 같은 대상을 신고하면") {
                then("둘 다 접수된다") {
                    seedReview(reviewId = 8133L, authorMemberId = 8151L, foodId = 8181L)

                    report(accessToken(8134L), body(targetId = 8133L), installationId = "solo-phone-a").andExpect { status { isOk() } }
                    report(accessToken(8135L), body(targetId = 8133L), installationId = "solo-phone-b").andExpect { status { isOk() } }

                    reportCountOf(8134L, 8133L) shouldBe 1
                    reportCountOf(8135L, 8133L) shouldBe 1
                }
            }

            `when`("신고자 식별자가 둘 다 없는 행을 직접 넣으면") {
                then("CHECK 제약이 거절한다") {
                    val failure = shouldThrow<Exception> {
                        dataSource.connection.use { c ->
                            c.createStatement().use {
                                it.executeUpdate(
                                    "INSERT INTO report (reporter_member_id, reporter_installation_id, target_type, target_id, " +
                                        "reason, status, created_at, updated_at) " +
                                        "VALUES (NULL, NULL, 'REVIEW', 8140, 'SPAM', 'ACTIVE', NOW(6), NOW(6))",
                                )
                            }
                        }
                    }
                    failure.message?.contains("ck_report_reporter_at_least_one") shouldBe true
                }
            }
        }

    }
}
