package com.kbap.api.core.auth

import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.member.model.MemberRole
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
    ): Long {
        val role = webRequest.getAttribute(JwtAuthenticationFilter.ROLE_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? String
        if (role == MemberRole.ADMIN.name) throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        return webRequest.getAttribute(JwtAuthenticationFilter.MEMBER_ID_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST) as? Long
            ?: throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
    }
}
