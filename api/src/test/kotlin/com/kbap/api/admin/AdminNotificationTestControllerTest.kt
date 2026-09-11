package com.kbap.api.admin

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.IntegrationTest
import com.kbap.api.TestTables
import com.kbap.api.auth.FakeSocialTokenVerifier
import com.kbap.api.notification.FakePushSender
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.notification.NotificationConsentJpaRepository
import com.kbap.common.domain.notification.NotificationDeviceJpaRepository
import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.model.DevicePlatform
import com.kbap.common.domain.notification.model.NotificationConsent
import com.kbap.common.domain.notification.model.NotificationConsentType
import com.kbap.common.domain.notification.model.NotificationDevice
import com.kbap.common.domain.notification.model.NotificationDispatchStatus
import com.kbap.common.port.auth.TokenIssuer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime
import javax.sql.DataSource

@IntegrationTest
class AdminNotificationTestControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var tokenIssuer: TokenIssuer

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var fakePushSender: FakePushSender

    @Autowired
    private lateinit var verifier: FakeSocialTokenVerifier

    @Autowired
    private lateinit var deviceRepository: NotificationDeviceJpaRepository

    @Autowired
    private lateinit var dispatchRepository: NotificationDispatchJpaRepository

    @Autowired
    private lateinit var consentRepository: NotificationConsentJpaRepository

    init {
        val objectMapper = jacksonObjectMapper()
        val path = "/api/admin/notifications/test-push"

        fun tokenOf(role: MemberRole): String = tokenIssuer.issueAccessToken(0, role)

        fun memberIdOf(providerUid: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else error("회원 없음: $providerUid") }
                }
            }

        fun signUp(sub: String): Long {
            mockMvc.post("/api/auth/login") {
                header("X-API-Version", "1.1")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to sub))
            }.andReturn().response.status shouldBe 200
            return memberIdOf(sub)
        }

        fun newsConsent(memberId: Long) {
            NotificationConsentType.entries.forEach {
                consentRepository.save(NotificationConsent.grantForMember(memberId, null, it, 2, LocalDateTime.now()))
            }
        }

        fun device(memberId: Long, lang: String = "ja"): NotificationDevice =
            deviceRepository.save(
                NotificationDevice.register("inst-$memberId-$lang", "ExponentPushToken[$memberId-$lang]", DevicePlatform.IOS, lang, memberId),
            )

        fun send(memberId: Long, token: String? = tokenOf(MemberRole.ADMIN)): MockHttpServletResponse =
            mockMvc.post(path) {
                header("X-API-Version", "1.0")
                token?.let { header("Authorization", "Bearer $it") }
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("memberId" to memberId))
            }.andReturn().response

        fun payload(response: MockHttpServletResponse) = objectMapper.readTree(response.contentAsString).path("payload")

        beforeContainer {
            TestTables.clearAll(dataSource)
            fakePushSender.reset()
            verifier.reset()
        }

        given("관리자 테스트 푸시 발송 API") {
            `when`("소식 동의를 켠 회원의 등록 기기에 보내면") {
                val memberId = signUp("kb468-admin-push-1")
                val device = device(memberId)
                newsConsent(memberId)
                val response = send(memberId)

                then("NEWS 를 기기 언어로 1건 발송하고 dispatch 가 SENT 로 남는다") {
                    response.status shouldBe 200
                    payload(response).path("sent").asInt() shouldBe 1
                    payload(response).path("failed").asInt() shouldBe 0

                    fakePushSender.sent shouldHaveSize 1
                    val message = fakePushSender.sent.single()
                    message.to shouldBe device.expoToken
                    message.title shouldBe "(광고) K-Bap"
                    message.data["type"] shouldBe "NEWS"
                    message.data["notificationId"].shouldNotBeNull()

                    val dispatch = dispatchRepository.findAll().single()
                    dispatch.dispatchStatus shouldBe NotificationDispatchStatus.SENT
                    dispatch.ticketId.shouldNotBeNull()
                }
            }

            `when`("기기가 없는 회원에게 보내면") {
                val memberId = signUp("kb468-admin-push-2")
                newsConsent(memberId)
                val response = send(memberId)

                then("발송 없이 0/0 을 돌려준다") {
                    response.status shouldBe 200
                    payload(response).path("sent").asInt() shouldBe 0
                    payload(response).path("failed").asInt() shouldBe 0
                    fakePushSender.sent shouldHaveSize 0
                }
            }

            `when`("소식 동의가 없는 회원에게 보내면") {
                val memberId = signUp("kb468-admin-push-4")
                device(memberId)
                val response = send(memberId)

                then("광고성이라 발송하지 않고 0/0 을 돌려준다") {
                    response.status shouldBe 200
                    payload(response).path("sent").asInt() shouldBe 0
                    fakePushSender.sent shouldHaveSize 0
                }
            }

            `when`("Expo 가 DeviceNotRegistered 를 돌려주면") {
                val memberId = signUp("kb468-admin-push-3")
                val device = device(memberId)
                newsConsent(memberId)
                fakePushSender.errorFor = { "DeviceNotRegistered" }
                val response = send(memberId)

                then("dispatch 는 FAILED 로 사유가 남고 기기 토큰은 그대로다") {
                    response.status shouldBe 200
                    payload(response).path("failed").asInt() shouldBe 1
                    val dispatch = dispatchRepository.findAll().single()
                    dispatch.dispatchStatus shouldBe NotificationDispatchStatus.FAILED
                    dispatch.error shouldBe "DeviceNotRegistered"
                    deviceRepository.findById(device.id).get().tokenInvalidAt shouldBe null
                }
            }

            `when`("없는 회원 id 로 보내면") {
                val response = send(999_999L)

                then("400 MEMBER-003 이다") {
                    response.status shouldBe 400
                    objectMapper.readTree(response.contentAsString).path("code").asText() shouldBe "MEMBER-003"
                }
            }

            `when`("USER 토큰으로 보내면") {
                val response = send(1L, token = tokenOf(MemberRole.USER))

                then("403 AUTH-008 이다") {
                    response.status shouldBe 403
                    objectMapper.readTree(response.contentAsString).path("code").asText() shouldBe "AUTH-008"
                }
            }

            `when`("토큰 없이 보내면") {
                val response = send(1L, token = null)

                then("401 이다") {
                    response.status shouldBe 401
                }
            }
        }
    }
}
