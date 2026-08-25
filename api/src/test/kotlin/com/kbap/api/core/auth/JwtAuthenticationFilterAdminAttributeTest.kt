package com.kbap.api.core.auth

import com.kbap.api.core.logging.RequestLoggingFilter
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.ParsedAccessToken
import com.kbap.common.port.auth.ParsedRefreshToken
import com.kbap.common.port.auth.TokenParser
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import jakarta.servlet.FilterChain
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class JwtAuthenticationFilterAdminAttributeTest : BehaviorSpec({
    val parser = object : TokenParser {
        override fun parseAccessToken(token: String): ParsedAccessToken = when (token) {
            "admin" -> ParsedAccessToken(memberId = 9L, role = MemberRole.ADMIN)
            else -> ParsedAccessToken(memberId = 3L, role = MemberRole.USER)
        }
        override fun parseRefreshToken(token: String): ParsedRefreshToken = error("unused")
        override fun refreshTokenJtiOrNull(token: String): String? = null
    }
    val filter = JwtAuthenticationFilter(parser)

    data class Captured(val memberId: Any?, val adminId: Any?, val role: Any?, val mdcMember: String?, val mdcAdmin: String?)

    fun run(token: String): Captured {
        val request = MockHttpServletRequest("GET", "/api/admin/dashboard").apply {
            addHeader("Authorization", "Bearer $token")
        }
        var captured: Captured? = null
        val chain = FilterChain { req, _ ->
            captured = Captured(
                memberId = req.getAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE),
                adminId = req.getAttribute(JwtAuthenticationFilter.ADMIN_ID_ATTRIBUTE),
                role = req.getAttribute(JwtAuthenticationFilter.ROLE_ATTRIBUTE),
                mdcMember = MDC.get(RequestLoggingFilter.MEMBER_ID_KEY),
                mdcAdmin = MDC.get(RequestLoggingFilter.ADMIN_ID_KEY),
            )
        }
        filter.doFilter(request, MockHttpServletResponse(), chain)
        MDC.clear()
        return captured!!
    }

    given("JWT 필터의 주체 속성") {
        `when`("ADMIN 토큰이면") {
            then("authAdminId 와 MDC adminId 만 심고 회원 속성은 심지 않는다") {
                val c = run("admin")

                c.adminId shouldBe 9L
                c.memberId.shouldBeNull()
                c.role shouldBe "ADMIN"
                c.mdcAdmin shouldBe "9"
                c.mdcMember.shouldBeNull()
            }
        }

        `when`("USER 토큰이면") {
            then("authMemberId 와 MDC memberId 만 심는다") {
                val c = run("user")

                c.memberId shouldBe 3L
                c.adminId.shouldBeNull()
                c.role shouldBe "USER"
                c.mdcMember shouldBe "3"
                c.mdcAdmin.shouldBeNull()
            }
        }
    }
})
