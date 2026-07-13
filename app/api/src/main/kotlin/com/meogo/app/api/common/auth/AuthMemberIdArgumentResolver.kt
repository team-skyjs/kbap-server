package com.meogo.app.api.common.auth

import com.meogo.application.auth.AuthErrorCode
import com.meogo.application.auth.AuthException
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

class AuthMemberIdArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(AuthMemberId::class.java) &&
            parameter.parameterType == Long::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Long =
        webRequest.getAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? Long
            ?: throw AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN)
}
