package com.kbap.api.admin

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.ParsedAccessToken
import com.kbap.common.port.auth.ParsedRefreshToken
import com.kbap.common.port.auth.TokenParser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import jakarta.servlet.http.Cookie
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class AdminPageAuthInterceptorTest : BehaviorSpec() {
    init {
        fun parserReturning(parsed: ParsedAccessToken?): TokenParser = object : TokenParser {
            override fun parseAccessToken(token: String): ParsedAccessToken =
                parsed ?: throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)

            override fun parseRefreshToken(token: String): ParsedRefreshToken = error("unused")

            override fun refreshTokenJtiOrNull(token: String): String? = error("unused")
        }

        fun request(
            method: String = "GET",
            cookie: String? = "valid-token",
            origin: String? = null,
        ): MockHttpServletRequest = MockHttpServletRequest(method, "/admin").apply {
            cookie?.let { setCookies(Cookie(AdminPageAuthInterceptor.COOKIE_NAME, it)) }
            origin?.let { addHeader("Origin", it) }
        }

        fun preHandle(parser: TokenParser, req: MockHttpServletRequest): Pair<Boolean, MockHttpServletResponse> {
            val response = MockHttpServletResponse()
            val passed = AdminPageAuthInterceptor(parser).preHandle(req, response, Any())
            return passed to response
        }

        given("관리자 페이지 인증 인터셉터") {
            `when`("쿠키가 없으면") {
                then("로그인으로 리다이렉트한다") {
                    val (passed, response) = preHandle(parserReturning(null), request(cookie = null))
                    passed shouldBe false
                    response.redirectedUrl shouldBe "/admin/login"
                }
            }

            `when`("무효하거나 만료된 토큰이면") {
                then("로그인으로 리다이렉트한다") {
                    val (passed, response) = preHandle(parserReturning(null), request())
                    passed shouldBe false
                    response.redirectedUrl shouldBe "/admin/login"
                }
            }

            `when`("USER 역할 토큰이면") {
                then("로그인으로 리다이렉트한다") {
                    val (passed, response) =
                        preHandle(parserReturning(ParsedAccessToken(1, MemberRole.USER)), request())
                    passed shouldBe false
                    response.redirectedUrl shouldBe "/admin/login"
                }
            }

            `when`("ADMIN 역할 토큰이면") {
                then("통과한다") {
                    val (passed, _) =
                        preHandle(parserReturning(ParsedAccessToken(1, MemberRole.ADMIN)), request())
                    passed shouldBe true
                }
            }

            `when`("POST 인데 Origin 이 자기 오리진과 다르면") {
                then("ADMIN 토큰이라도 로그인으로 리다이렉트한다") {
                    val (passed, response) = preHandle(
                        parserReturning(ParsedAccessToken(1, MemberRole.ADMIN)),
                        request(method = "POST", origin = "https://evil.example"),
                    )
                    passed shouldBe false
                    response.redirectedUrl shouldBe "/admin/login"
                }
            }

            `when`("POST 이고 Origin 이 자기 오리진과 같으면") {
                then("통과한다") {
                    val req = request(method = "POST", origin = "http://localhost")
                    val (passed, _) = preHandle(parserReturning(ParsedAccessToken(1, MemberRole.ADMIN)), req)
                    passed shouldBe true
                }
            }
        }
    }
}
