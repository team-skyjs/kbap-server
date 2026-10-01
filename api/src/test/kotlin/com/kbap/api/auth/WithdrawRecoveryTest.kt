package com.kbap.api.auth

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.image.UploadedImageService
import com.kbap.api.member.MemberService
import com.kbap.api.notification.NotificationTokenService
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.port.auth.RefreshTokenStore
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.auth.TokenParser
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.CannotAcquireLockException
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.time.Duration
import javax.sql.DataSource

@IntegrationTest
class WithdrawRecoveryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var dataSource: DataSource
    @Autowired private lateinit var accountDeleter: FakeSocialAccountDeleter
    @Autowired private lateinit var verifier: FakeSocialTokenVerifier
    @Autowired private lateinit var memberService: MemberService
    @Autowired private lateinit var memberRepository: MemberJpaRepository
    @Autowired private lateinit var uploadedImageService: UploadedImageService
    @Autowired private lateinit var orderRepository: OrderJpaRepository
    @Autowired private lateinit var tokenIssuer: TokenIssuer
    @Autowired private lateinit var tokenParser: TokenParser
    @Autowired private lateinit var refreshTokenStore: RefreshTokenStore
    @Autowired private lateinit var notificationTokenService: NotificationTokenService

    private val mapper = jacksonObjectMapper()

    init {
        beforeContainer {
            TestTables.clearAll(dataSource)
            verifier.reset()
            accountDeleter.reset()
        }

        fun login() = mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("idToken" to FakeSocialTokenVerifier.DEFAULT_SUB))
        }.andReturn().response

        fun tokenOf(response: org.springframework.mock.web.MockHttpServletResponse) =
            mapper.readTree(response.getContentAsString(Charsets.UTF_8)).path("payload").path("accessToken").asText()

        fun withdraw(token: String) = mockMvc.patch("/api/auth/withdraw") { header("Authorization", "Bearer $token") }.andReturn().response

        fun memberStatus(): Pair<String?, String?> = dataSource.connection.use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT status, provider_uid FROM member ORDER BY id DESC LIMIT 1").use { rs ->
                    if (rs.next()) rs.getString(1) to rs.getString(2) else null to null
                }
            }
        }

        fun countMembers(): Int = dataSource.connection.use { c ->
            c.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM member").use { rs -> rs.next(); rs.getInt(1) } }
        }

        fun authServiceWhoseDbStepFailsOnce(): AuthService {
            var failNext = true
            val flaky = object : MemberService(memberRepository, uploadedImageService, orderRepository, "", "") {
                override fun getMember(memberId: Long): Member = memberService.getMember(memberId)

                override fun withdraw(memberId: Long) {
                    if (failNext) {
                        failNext = false
                        throw CannotAcquireLockException("테스트 — 탈퇴 DB 단계 실패")
                    }
                    memberService.withdraw(memberId)
                }
            }
            return AuthService(verifier, flaky, tokenIssuer, tokenParser, refreshTokenStore, accountDeleter, notificationTokenService, Duration.ofDays(14))
        }

        given("소셜 계정 삭제는 성공했고 DB 탈퇴 단계가 실패한 회원") {
            `when`("같은 액세스 토큰으로 탈퇴를 다시 요청하면") {
                then("탈퇴가 끝난다 — 소셜 삭제는 다시 불려도 멱등하고, 회원은 그때까지 ACTIVE 라 토큰이 유효하다") {
                    val token = tokenOf(login())
                    val memberId = tokenParser.parseAccessToken(token).memberId
                    val flakyAuth = authServiceWhoseDbStepFailsOnce()

                    shouldThrow<CannotAcquireLockException> { flakyAuth.withdraw(memberId) }
                    accountDeleter.deleted.size shouldBe 1
                    memberStatus().first shouldBe "ACTIVE"

                    withdraw(token).status shouldBe 200

                    memberStatus().first shouldBe "DELETED"
                    accountDeleter.deleted shouldBe List(2) { SocialProvider.GOOGLE to FakeSocialTokenVerifier.DEFAULT_SUB }
                }
            }

            `when`("토큰을 잃어 같은 소셜 계정으로 다시 로그인한 뒤 탈퇴하면") {
                then("기존 회원으로 로그인되고(소셜 식별자가 그대로라) 탈퇴가 끝난다 — 고아 회원이 생기지 않는다") {
                    val first = tokenOf(login())
                    val memberId = tokenParser.parseAccessToken(first).memberId
                    shouldThrow<CannotAcquireLockException> { authServiceWhoseDbStepFailsOnce().withdraw(memberId) }

                    val relogin = login()
                    mapper.readTree(relogin.getContentAsString(Charsets.UTF_8)).path("payload").path("newMember").asBoolean() shouldBe false
                    tokenParser.parseAccessToken(tokenOf(relogin)).memberId shouldBe memberId

                    withdraw(tokenOf(relogin)).status shouldBe 200

                    countMembers() shouldBe 1
                    memberStatus() shouldBe ("DELETED" to "DELETED:$memberId")
                }
            }

            `when`("DB 단계가 실패하면") {
                then("소셜은 지워졌고 회원은 남았다는 오류 로그를 회원 id·제공자와 함께 남긴다 — 재시도가 없을 때 찾을 단서") {
                    val token = tokenOf(login())
                    val memberId = tokenParser.parseAccessToken(token).memberId
                    val logger = LoggerFactory.getLogger(AuthService::class.java) as ch.qos.logback.classic.Logger
                    val appender = ListAppender<ILoggingEvent>().apply { start() }
                    logger.addAppender(appender)
                    try {
                        shouldThrow<CannotAcquireLockException> { authServiceWhoseDbStepFailsOnce().withdraw(memberId) }
                    } finally {
                        logger.detachAppender(appender)
                    }

                    val event = appender.list.single { it.level == Level.ERROR }
                    event.formattedMessage.contains("memberId=$memberId") shouldBe true
                    event.formattedMessage.contains("GOOGLE") shouldBe true
                }
            }
        }

        given("소셜 계정 삭제가 실패한 회원") {
            `when`("제공자가 회복된 뒤 탈퇴를 다시 요청하면") {
                then("첫 요청은 500 AUTH-007 에 회원 무변, 재요청은 200 으로 탈퇴가 끝난다") {
                    val token = tokenOf(login())
                    accountDeleter.fail()

                    val failed = withdraw(token)
                    failed.status shouldBe 500
                    mapper.readTree(failed.getContentAsString(Charsets.UTF_8)).path("code").asText() shouldBe "AUTH-007"
                    memberStatus().first shouldBe "ACTIVE"

                    accountDeleter.reset()
                    withdraw(token).status shouldBe 200
                    memberStatus().first shouldBe "DELETED"
                }
            }
        }
    }
}
