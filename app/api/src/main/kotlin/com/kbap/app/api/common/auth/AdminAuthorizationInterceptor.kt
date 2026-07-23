package com.kbap.app.api.common.auth

import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import com.kbap.domain.member.model.MemberRole
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
