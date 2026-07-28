package com.kbap.api.admin

import com.kbap.api.core.auth.JwtAuthenticationFilter
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.model.MemberRole
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor

class AdminAuthorizationInterceptor : HandlerInterceptor {
    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        val role = request.getAttribute(JwtAuthenticationFilter.ROLE_ATTRIBUTE) as? String
        if (role != MemberRole.ADMIN.name) {
            throw BusinessException(ErrorCode.ADMIN_FORBIDDEN)
        }
        return true
    }
}
