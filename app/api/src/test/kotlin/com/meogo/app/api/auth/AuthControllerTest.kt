package com.meogo.app.api.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.application.client.auth.AuthErrorCode
import com.meogo.application.client.auth.AuthException
import com.meogo.application.client.auth.SocialTokenVerifier
import com.meogo.core.member.SocialIdentity
import com.meogo.core.member.SocialProvider
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import com.meogo.infra.persistence.testsupport.RedisContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import jakarta.servlet.http.Cookie
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

        fun memberColumn(memberId: Long, column: String): String? =
            dataSource.connection.use { c ->
                c.prepareStatement("SELECT $column FROM member WHERE id = ?").use { ps ->
                    ps.setLong(1, memberId)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
                }
            }

        fun memberIdOf(response: MockHttpServletResponse): Long =
            objectMapper.readTree(response.contentAsString).path("payload").path("memberId").asLong()

        fun login(idToken: String = "valid-token") =
            mockMvc.post("/api/v1/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("idToken" to idToken))
            }

        beforeContainer {
            clearMembers()
            verifier.reset()
        }

        given("유효한 소셜 토큰의 미가입 사용자") {
            `when`("로그인하면") {
                then("가입되고 신규 회원 플래그와 함께 access·refresh 쿠키가 내려온다") {
                    val result = login().andReturn().response

                    result.status shouldBe 200
                    result.contentAsString shouldContain "\"success\":true"
                    result.contentAsString shouldContain "\"newMember\":true"

                    val cookies = result.getHeaders("Set-Cookie")
                    val access = cookies.first { it.startsWith("access_token=") }
                    val refresh = cookies.first { it.startsWith("refresh_token=") }

                    access shouldContain "HttpOnly"
                    access shouldContain "Secure"
                    access shouldContain "Path=/"
                    access shouldContain "SameSite=Lax"
                    refresh shouldContain "HttpOnly"
                    refresh shouldContain "Secure"
                    refresh shouldContain "Path=/api/v1/auth"
                    refresh shouldContain "Max-Age=1209600"
                }
            }

            `when`("로그인하면") {
                then("회원이 신원과 함께 저장되고 온보딩 대기·활성 상태로 가입된다") {
                    val response = login().andReturn().response
                    val memberId = memberIdOf(response)

                    countMembers() shouldBe 1
                    memberColumn(memberId, "provider") shouldBe "GOOGLE"
                    memberColumn(memberId, "provider_uid") shouldBe "google-sub-fixed"
                    memberColumn(memberId, "email") shouldBe "user@gmail.com"
                    memberColumn(memberId, "onboarding_status") shouldBe "0"
                    memberColumn(memberId, "member_status") shouldBe "ACTIVE"
                    memberColumn(memberId, "status") shouldBe "ACTIVE"
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
                    verifier.failWith(AuthErrorCode.INVALID_SOCIAL_TOKEN)

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
                    verifier.failWith(AuthErrorCode.UNSUPPORTED_PROVIDER)

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
                    val cookies = login().andReturn().response.getHeaders("Set-Cookie")
                    val token = cookies.first { it.startsWith("access_token=") }
                        .substringAfter("access_token=")
                        .substringBefore(";")
                    token.shouldNotBeNull()

                    val payload = String(java.util.Base64.getUrlDecoder().decode(token.split(".")[1]))
                    payload.contains("gmail.com") shouldBe false
                }
            }
        }

        fun cookieValue(response: MockHttpServletResponse, name: String): String =
            response.getHeaders("Set-Cookie")
                .first { it.startsWith("$name=") }
                .substringAfter("$name=")
                .substringBefore(";")

        fun refresh(refreshToken: String?) =
            mockMvc.post("/api/v1/auth/refresh") {
                refreshToken?.let { cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN, it)) }
            }

        fun logout(refreshToken: String?) =
            mockMvc.post("/api/v1/auth/logout") {
                refreshToken?.let { cookie(Cookie(AuthCookieFactory.REFRESH_TOKEN, it)) }
            }

        given("로그인으로 받은 refresh 토큰") {
            `when`("재발급하면") {
                then("access·refresh 가 모두 새 값으로 갱신되고 refresh 수명이 다시 설정된다") {
                    val loginResponse = login().andReturn().response
                    val oldAccess = cookieValue(loginResponse, AuthCookieFactory.ACCESS_TOKEN)
                    val oldRefresh = cookieValue(loginResponse, AuthCookieFactory.REFRESH_TOKEN)

                    val response = refresh(oldRefresh).andReturn().response

                    response.status shouldBe 200
                    val newAccess = cookieValue(response, AuthCookieFactory.ACCESS_TOKEN)
                    val newRefresh = cookieValue(response, AuthCookieFactory.REFRESH_TOKEN)
                    (newRefresh != oldRefresh) shouldBe true
                    newAccess.isNotBlank() shouldBe true
                    response.getHeaders("Set-Cookie")
                        .first { it.startsWith("refresh_token=") } shouldContain "Max-Age=1209600"
                }
            }

            `when`("재발급 후 이전 refresh 토큰을 다시 쓰면") {
                then("401 로 거절된다(rotation 으로 폐기됨)") {
                    val oldRefresh = cookieValue(login().andReturn().response, AuthCookieFactory.REFRESH_TOKEN)
                    refresh(oldRefresh).andReturn()

                    refresh(oldRefresh).andReturn().response.status shouldBe 401
                }
            }
        }

        given("refresh 쿠키 없는 재발급 요청") {
            `when`("재발급하면") {
                then("401 로 거절된다") {
                    refresh(null).andReturn().response.status shouldBe 401
                }
            }
        }

        given("조작된 refresh 토큰") {
            `when`("재발급하면") {
                then("401 로 거절되고 쿠키가 만료된다") {
                    val response = refresh("forged.refresh.token").andReturn().response

                    response.status shouldBe 401
                    response.getHeaders("Set-Cookie").any { it.contains("Max-Age=0") } shouldBe true
                }
            }
        }

        given("로그인된 회원") {
            `when`("로그아웃하면") {
                then("쿠키 2종이 만료되고 그 refresh 로는 재발급할 수 없다") {
                    val refreshToken = cookieValue(login().andReturn().response, AuthCookieFactory.REFRESH_TOKEN)

                    val logoutResponse = logout(refreshToken).andReturn().response

                    logoutResponse.status shouldBe 200
                    val expired = logoutResponse.getHeaders("Set-Cookie").filter { it.contains("Max-Age=0") }
                    expired.size shouldBe 2

                    refresh(refreshToken).andReturn().response.status shouldBe 401
                }
            }

            `when`("쿠키 없이 로그아웃하면") {
                then("200 으로 멱등 처리된다") {
                    logout(null).andReturn().response.status shouldBe 200
                }
            }
        }
    }
}

class FakeSocialTokenVerifier : SocialTokenVerifier {
    private var failure: AuthErrorCode? = null

    override fun verify(idToken: String): SocialIdentity {
        failure?.let { throw AuthException(it) }
        return SocialIdentity(SocialProvider.GOOGLE, "google-sub-fixed", "user@gmail.com")
    }

    fun failWith(errorCode: AuthErrorCode) {
        failure = errorCode
    }

    fun reset() {
        failure = null
    }
}

@TestConfiguration
class FakeSocialTokenVerifierConfig {
    @Bean
    @Primary
    fun fakeSocialTokenVerifier(): FakeSocialTokenVerifier = FakeSocialTokenVerifier()
}
