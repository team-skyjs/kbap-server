package com.kbap.api.admin

import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenParser
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor

class AdminPageAuthInterceptor(
    private val tokenParser: TokenParser,
) : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (request.method == "POST" && originMismatched(request)) return redirectToLogin(response)

        val token = request.cookies?.firstOrNull { it.name == COOKIE_NAME }?.value
            ?: return redirectToLogin(response)
        val parsed = try {
            tokenParser.parseAccessToken(token)
        } catch (e: BusinessException) {
            return redirectToLogin(response)
        }
        if (parsed.role != MemberRole.ADMIN) return redirectToLogin(response)
        request.setAttribute(JwtAuthenticationFilter.ADMIN_ID_ATTRIBUTE, parsed.memberId)
        request.setAttribute(JwtAuthenticationFilter.ROLE_ATTRIBUTE, parsed.roleName)
        return true
    }

    private fun originMismatched(request: HttpServletRequest): Boolean {
        val origin = request.getHeader("Origin") ?: return false
        return origin != selfOrigin(request)
    }

    private fun selfOrigin(request: HttpServletRequest): String {
        val portPart = when {
            request.scheme == "http" && request.serverPort == 80 -> ""
            request.scheme == "https" && request.serverPort == 443 -> ""
            else -> ":${request.serverPort}"
        }
        return "${request.scheme}://${request.serverName}$portPart"
    }

    private fun redirectToLogin(response: HttpServletResponse): Boolean {
        response.sendRedirect(LOGIN_PATH)
        return false
    }

    companion object {
        const val COOKIE_NAME: String = "ADMIN_TOKEN"
        const val LOGIN_PATH: String = "/admin/login"
    }
}
