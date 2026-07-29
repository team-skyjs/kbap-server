package com.kbap.api.core.auth

import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenParser
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

class AuthMemberIdOrNullArgumentResolver(
    private val tokenParser: TokenParser,
) : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(AuthMemberIdOrNull::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Long? {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java) ?: return null
        val header = request.getHeader(AUTHORIZATION_HEADER) ?: return null
        if (!header.startsWith(BEARER_PREFIX)) return null
        val parsed = tokenParser.parseAccessToken(header.removePrefix(BEARER_PREFIX))
        // 관리자 토큰의 id claim 은 admin_account.id — member id 와 충돌하므로 게스트로 취급
        if (parsed.role == MemberRole.ADMIN) return null
        return parsed.memberId
    }

    companion object {
        private const val AUTHORIZATION_HEADER: String = "Authorization"
        private const val BEARER_PREFIX: String = "Bearer "
    }
}
