package com.meogo.app.api.common.auth

import com.meogo.application.auth.AuthTokenProperties
import com.meogo.application.auth.TokenIssuer
import com.meogo.application.auth.TokenParser
import com.meogo.domain.member.MemberRole
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.time.Duration

class JwtAuthenticationFilterTest : BehaviorSpec({

    val properties = AuthTokenProperties(
        secret = "kb104-filter-test-secret-at-least-32-bytes!!",
        accessTtl = Duration.ofMinutes(30),
        refreshTtl = Duration.ofDays(14),
    )
    val issuer = TokenIssuer(properties)
    val filter = JwtAuthenticationFilter(TokenParser(properties))

    fun request(header: String?): MockHttpServletRequest {
        val request = MockHttpServletRequest()
        if (header != null) request.addHeader("Authorization", header)
        return request
    }

    given("유효한 Bearer 액세스 토큰") {
        `when`("필터를 통과하면") {
            then("회원 PK·role 이 request attribute 로 저장되고 체인이 진행된다") {
                val token = issuer.issueAccessToken(memberId = 1L, role = MemberRole.USER)
                val request = request("Bearer $token")
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()

                filter.doFilter(request, response, chain)

                request.getAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE) shouldBe 1L
                request.getAttribute(JwtAuthenticationFilter.ROLE_ATTRIBUTE) shouldBe MemberRole.USER.name
                chain.request.shouldNotBeNull()
                response.status shouldBe 200
            }
        }
    }

    given("Authorization 헤더가 없는 요청") {
        `when`("필터를 통과하면") {
            then("401 로 응답하고 체인이 진행되지 않는다") {
                val request = request(null)
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()

                filter.doFilter(request, response, chain)

                response.status shouldBe 401
                response.contentType shouldContain "application/json"
                response.contentAsString shouldContain "\"success\":false"
                response.contentAsString shouldContain "유효하지 않은 인증 토큰입니다"
                chain.request.shouldBeNull()
            }
        }
    }

    given("Bearer 접두가 아닌 Authorization 헤더") {
        `when`("필터를 통과하면") {
            then("401 로 거절한다") {
                val request = request("Basic dXNlcjpwYXNz")
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()

                filter.doFilter(request, response, chain)

                response.status shouldBe 401
                response.contentAsString shouldContain "유효하지 않은 인증 토큰입니다"
                chain.request.shouldBeNull()
            }
        }
    }

    given("다른 시크릿으로 서명한 위조 토큰") {
        `when`("필터를 통과하면") {
            then("401 로 거절한다") {
                val forgedIssuer = TokenIssuer(
                    properties.copy(secret = "another-kb104-forged-secret-at-least-32b!!"),
                )
                val forged = forgedIssuer.issueAccessToken(memberId = 1L, role = MemberRole.USER)
                val request = request("Bearer $forged")
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()

                filter.doFilter(request, response, chain)

                response.status shouldBe 401
                response.contentAsString shouldContain "유효하지 않은 인증 토큰입니다"
                chain.request.shouldBeNull()
            }
        }
    }

    given("만료된 액세스 토큰") {
        `when`("필터를 통과하면") {
            then("401 만료 메시지로 거절한다") {
                val expiredIssuer = TokenIssuer(
                    properties.copy(accessTtl = Duration.ofMinutes(-1)),
                )
                val expired = expiredIssuer.issueAccessToken(memberId = 1L, role = MemberRole.USER)
                val request = request("Bearer $expired")
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()

                filter.doFilter(request, response, chain)

                response.status shouldBe 401
                response.contentAsString shouldContain "만료된 인증 토큰입니다"
                chain.request.shouldBeNull()
            }
        }
    }

    given("액세스 토큰 자리에 제시된 refresh 토큰") {
        `when`("필터를 통과하면") {
            then("401 로 거절한다(token_type 불일치)") {
                val refresh = issuer.issueRefreshToken(memberId = 1L).token
                val request = request("Bearer $refresh")
                val response = MockHttpServletResponse()
                val chain = MockFilterChain()

                filter.doFilter(request, response, chain)

                response.status shouldBe 401
                chain.request.shouldBeNull()
            }
        }
    }
})
