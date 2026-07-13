package com.kbap.app.api.auth

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.domain.member.SocialAccountDeleter
import com.kbap.application.auth.social.SocialTokenVerifier
import com.kbap.domain.member.SocialIdentity
import com.kbap.domain.member.SocialProvider
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.core.testsupport.RedisContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import javax.sql.DataSource

@SpringBootTest
@AutoConfigureMockMvc
@Import(MySqlContainerConfig::class, RedisContainerConfig::class, FakeSocialTokenVerifierConfig::class)
class AuthControllerTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var verifier: FakeSocialTokenVerifier

    @Autowired
    private lateinit var dataSource: DataSource

    @Autowired
    private lateinit var accountDeleter: FakeSocialAccountDeleter

    init {
        val objectMapper = jacksonObjectMapper()

        fun clearMembers() {
            dataSource.connection.use { c -> c.createStatement().use { it.execute("DELETE FROM member") } }
        }

        fun countMembers(): Int =
            dataSource.connection.use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("SELECT COUNT(*) FROM member").use { rs -> rs.next(); rs.getInt(1) }
                }
            }

        fun suspendAll() {
            dataSource.connection.use { c ->
                c.createStatement().use { it.execute("UPDATE member SET member_status = 'SUSPENDED'") }
            }
        }

        fun memberColumn(providerUid: String, column: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT $column FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun login(idToken: String = "valid-token") =
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to idToken))
            }

        fun bodyToken(response: MockHttpServletResponse, field: String): String =
            objectMapper.readTree(response.contentAsString).path("payload").path(field).asText()

        fun refresh(refreshToken: String?) =
            mockMvc.post("/api/v1/auth/refresh") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))
            }

        fun logout(refreshToken: String?) =
            mockMvc.post("/api/v1/auth/logout") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("refreshToken" to refreshToken))
            }

        fun withdraw(token: String?) =
            mockMvc.patch("/api/v1/auth/withdraw") {
                if (token != null) header("Authorization", "Bearer $token")
            }

        fun loginAccessToken(): String = bodyToken(login().andReturn().response, "accessToken")

        fun getMyProfile(token: String?) =
            mockMvc.get("/api/v1/members/me/profile") {
                if (token != null) header("Authorization", "Bearer $token")
            }

        fun submitOnboarding(token: String, body: Map<String, Any?>) =
            mockMvc.post("/api/v1/members/me/onboarding") {
                header("Authorization", "Bearer $token")
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }

        fun validBody() = mapOf(
            "nickname" to "길동이",
            "avoidanceSubstanceCodes" to listOf("EGG", "MILK"),
            "countryCode" to "US",
            "appLanguage" to "en",
        )

        fun columnById(id: Long, column: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT $column FROM member WHERE id = ?").use { ps ->
                    ps.setLong(1, id)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun memberIdOf(providerUid: String): Long =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT id FROM member WHERE provider_uid = ?").use { ps ->
                    ps.setString(1, providerUid)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else error("회원 없음: $providerUid") }
                }
            }

        beforeContainer {
            clearMembers()
            verifier.reset()
            accountDeleter.reset()
        }

        given("유효한 소셜 토큰의 미가입 사용자") {
            `when`("로그인하면") {
                then("가입되고 신규 회원 플래그와 함께 access·refresh 토큰이 응답 본문으로 내려온다") {
                    val result = login().andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"success\":true"
                    result.contentAsString shouldContain "\"newMember\":true"

                    val payload = objectMapper.readTree(result.contentAsString).path("payload")
                    payload.path("accessToken").asText().isNotBlank() shouldBe true
                    payload.path("refreshToken").asText().isNotBlank() shouldBe true
                    payload.has("memberId") shouldBe false
                    result.getHeaders("Set-Cookie").isEmpty() shouldBe true
                }
            }

            `when`("로그인하면") {
                then("회원이 신원과 함께 저장되고 온보딩 대기·활성 상태로 가입된다") {
                    login().andReturn()

                    countMembers() shouldBe 1
                    memberColumn("google-sub-fixed", "provider") shouldBe "GOOGLE"
                    memberColumn("google-sub-fixed", "email") shouldBe "user@gmail.com"
                    memberColumn("google-sub-fixed", "onboarding_completed") shouldBe "0"
                    memberColumn("google-sub-fixed", "member_status") shouldBe "ACTIVE"
                    memberColumn("google-sub-fixed", "status") shouldBe "ACTIVE"
                }
            }
        }

        given("이미 가입된 회원") {
            `when`("같은 소셜 계정으로 다시 로그인하면") {
                then("신규 회원이 아니며 회원이 중복 생성되지 않는다") {
                    login().andReturn()

                    val result = login().andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"newMember\":false"
                    countMembers() shouldBe 1
                }
            }
        }

        given("검증에 실패하는 소셜 토큰") {
            `when`("로그인하면") {
                then("401 로 거절되고 회원이 생성되지 않는다") {
                    verifier.failWith(ErrorCode.INVALID_SOCIAL_TOKEN)

                    val result = login("forged-token").andReturn().response

                    result.status shouldBe 401
                    result.contentAsString shouldContain "\"success\":false"
                    countMembers() shouldBe 0
                }
            }
        }

        given("지원하지 않는 provider 토큰") {
            `when`("로그인하면") {
                then("401 로 거절된다") {
                    verifier.failWith(ErrorCode.UNSUPPORTED_PROVIDER)

                    login("kakao-token").andReturn().response.status shouldBe 401
                }
            }
        }

        given("idToken 이 비어 있는 요청") {
            `when`("로그인하면") {
                then("400 으로 거절된다") {
                    login("").andReturn().response.status shouldBe 400
                }
            }
        }

        given("정지된 회원") {
            `when`("같은 소셜 계정으로 로그인하면") {
                then("로그인이 거부되고 신규 회원이 생성되지 않는다") {
                    login().andReturn()
                    suspendAll()

                    val result = login().andReturn().response

                    (result.status >= 400) shouldBe true
                    result.contentAsString shouldContain "\"success\":false"
                    countMembers() shouldBe 1
                }
            }
        }

        given("발급된 access 토큰") {
            `when`("본문을 디코딩하면") {
                then("개인정보 클레임이 담기지 않는다") {
                    val token = bodyToken(login().andReturn().response, "accessToken")
                    token.shouldNotBeNull()

                    val payload = String(java.util.Base64.getUrlDecoder().decode(token.split(".")[1]))
                    payload.contains("gmail.com") shouldBe false
                }
            }
        }

        given("로그인으로 받은 refresh 토큰") {
            `when`("재발급하면") {
                then("access·refresh 가 모두 새 값으로 응답 본문에 내려온다(rotation)") {
                    val loginResponse = login().andReturn().response
                    val oldRefresh = bodyToken(loginResponse, "refreshToken")

                    val response = refresh(oldRefresh).andReturn().response

                    response.status shouldBe 200
                    val newAccess = bodyToken(response, "accessToken")
                    val newRefresh = bodyToken(response, "refreshToken")
                    (newRefresh != oldRefresh) shouldBe true
                    newAccess.isNotBlank() shouldBe true
                }
            }

            `when`("재발급 후 이전 refresh 토큰을 다시 쓰면") {
                then("401 로 거절된다(rotation 으로 폐기됨)") {
                    val oldRefresh = bodyToken(login().andReturn().response, "refreshToken")
                    refresh(oldRefresh).andReturn()

                    refresh(oldRefresh).andReturn().response.status shouldBe 401
                }
            }
        }

        given("refresh 토큰 없는 재발급 요청") {
            `when`("재발급하면") {
                then("400 으로 거절된다") {
                    refresh(null).andReturn().response.status shouldBe 400
                }
            }
        }

        given("조작된 refresh 토큰") {
            `when`("재발급하면") {
                then("401 로 거절된다") {
                    refresh("forged.refresh.token").andReturn().response.status shouldBe 401
                }
            }
        }

        given("로그인된 회원") {
            `when`("로그아웃하면") {
                then("세션이 폐기되어 그 refresh 로는 재발급할 수 없다") {
                    val refreshToken = bodyToken(login().andReturn().response, "refreshToken")

                    logout(refreshToken).andReturn().response.status shouldBe 200

                    refresh(refreshToken).andReturn().response.status shouldBe 401
                }
            }

            `when`("refresh 토큰 없이 로그아웃하면") {
                then("200 으로 멱등 처리된다") {
                    logout(null).andReturn().response.status shouldBe 200
                }
            }

            `when`("이미 로그아웃한 refresh 토큰으로 다시 로그아웃하면") {
                then("200 으로 멱등 처리된다") {
                    val refreshToken = bodyToken(login().andReturn().response, "refreshToken")
                    logout(refreshToken).andReturn().response.status shouldBe 200

                    logout(refreshToken).andReturn().response.status shouldBe 200
                }
            }
        }

        given("회원 탈퇴") {
            `when`("로그인한 회원이 탈퇴하면") {
                then("200 으로 응답하고 인증 제공자 계정 삭제 후 회원 행이 소프트 삭제된다") {
                    val token = loginAccessToken()
                    val id = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)

                    val result = withdraw(token).andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"success\":true"
                    accountDeleter.deleted shouldBe
                        listOf(SocialProvider.GOOGLE to FakeSocialTokenVerifier.DEFAULT_SUB)
                    columnById(id, "provider_uid") shouldBe "DELETED:$id"
                    columnById(id, "status") shouldBe "DELETED"
                }
            }

            `when`("탈퇴 후 같은 access 토큰으로 프로필을 조회하면") {
                then("400 으로 거절된다") {
                    val token = loginAccessToken()
                    withdraw(token).andReturn()

                    val result = getMyProfile(token).andReturn().response

                    result.status shouldBe 400
                    result.contentAsString shouldContain "해당 회원을 찾을 수 없습니다"
                }
            }

            `when`("이미 탈퇴한 회원이 다시 탈퇴를 요청하면") {
                then("400 으로 거절된다") {
                    val token = loginAccessToken()
                    withdraw(token).andReturn()

                    withdraw(token).andReturn().response.status shouldBe 400
                }
            }

            `when`("인증 없이 탈퇴를 요청하면") {
                then("401 로 거절되고 인증 제공자 계정도 지워지지 않는다") {
                    loginAccessToken()

                    withdraw(null).andReturn().response.status shouldBe 401

                    accountDeleter.deleted.isEmpty() shouldBe true
                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "status") shouldBe "ACTIVE"
                }
            }

            `when`("인증 제공자 계정 삭제가 실패하면") {
                then("500 으로 거절되고 회원 행은 활성 상태로 남는다") {
                    val token = loginAccessToken()
                    accountDeleter.fail()

                    withdraw(token).andReturn().response.status shouldBe 500

                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "status") shouldBe "ACTIVE"
                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "member_status") shouldBe "ACTIVE"
                }
            }
        }

        given("탈퇴 후 같은 소셜 계정 재가입") {
            `when`("탈퇴 직후 같은 소셜 계정으로 다시 로그인하면") {
                then("신규 회원으로 가입되고 이전 프로필을 승계하지 않는다") {
                    val token = loginAccessToken()
                    submitOnboarding(token, validBody()).andReturn()
                    val previousId = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)
                    withdraw(token).andReturn()

                    val response = login().andReturn().response

                    response.status shouldBe 200
                    response.contentAsString shouldContain "\"newMember\":true"
                    val newId = memberIdOf(FakeSocialTokenVerifier.DEFAULT_SUB)
                    (newId != previousId) shouldBe true
                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "onboarding_completed") shouldBe "0"
                    memberColumn(FakeSocialTokenVerifier.DEFAULT_SUB, "nickname") shouldBe null
                }
            }

            `when`("가입과 탈퇴를 두 번 반복한 뒤 다시 로그인하면") {
                then("삭제 표식이 회원마다 달라 유니크 충돌 없이 가입된다") {
                    withdraw(loginAccessToken()).andReturn()
                    withdraw(loginAccessToken()).andReturn()

                    val response = login().andReturn().response

                    response.status shouldBe 200
                    response.contentAsString shouldContain "\"newMember\":true"
                    getMyProfile(
                        objectMapper.readTree(response.contentAsString)
                            .path("payload").path("accessToken").asText(),
                    ).andReturn().response.status shouldBe 200
                }
            }
        }

        given("탈퇴한 회원이 들고 있던 refresh 토큰") {
            `when`("재발급하면") {
                then("401 로 거절된다") {
                    val loginResponse = login().andReturn().response
                    val refreshToken = bodyToken(loginResponse, "refreshToken")

                    withdraw(bodyToken(loginResponse, "accessToken")).andReturn().response.status shouldBe 200

                    refresh(refreshToken).andReturn().response.status shouldBe 401
                }
            }
        }
    }
}

class FakeSocialTokenVerifier : SocialTokenVerifier {
    private var failure: ErrorCode? = null

    override fun verify(idToken: String): SocialIdentity {
        failure?.let { throw KbapException(it) }
        return SocialIdentity(SocialProvider.GOOGLE, DEFAULT_SUB, "user@gmail.com")
    }

    fun failWith(errorCode: ErrorCode) {
        failure = errorCode
    }

    fun reset() {
        failure = null
    }

    companion object {
        const val DEFAULT_SUB: String = "google-sub-fixed"
    }
}

class FakeSocialAccountDeleter : SocialAccountDeleter {
    val deleted: MutableList<Pair<SocialProvider, String>> = mutableListOf()
    private var failing = false

    override fun delete(provider: SocialProvider, providerUserId: String) {
        if (failing) {
            throw IllegalStateException("인증 제공자 계정 삭제 실패 시뮬레이션")
        }
        deleted += provider to providerUserId
    }

    fun fail() {
        failing = true
    }

    fun reset() {
        deleted.clear()
        failing = false
    }
}

@TestConfiguration
class FakeSocialTokenVerifierConfig {
    @Bean
    @Primary
    fun fakeSocialTokenVerifier(): FakeSocialTokenVerifier = FakeSocialTokenVerifier()

    @Bean
    @Primary
    fun fakeSocialAccountDeleter(): FakeSocialAccountDeleter = FakeSocialAccountDeleter()
}
