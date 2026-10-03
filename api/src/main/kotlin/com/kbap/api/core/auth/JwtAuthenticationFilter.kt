package com.kbap.api.core.auth

import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.logging.RequestLoggingFilter
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenParser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.MediaType
import org.springframework.web.cors.CorsUtils
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val tokenParser: TokenParser,
    private val guestExemptions: List<GuestExemption> = emptyList(),
    private val isActiveMember: (Long) -> Boolean = { true },
    private val activeMemberCheckExempt: Regex? = null,
) : OncePerRequestFilter() {
    data class GuestExemption(
        val method: String,
        val path: Regex,
        val parseTokenIfPresent: Boolean = false,
    )

    private val objectMapper = jacksonObjectMapper()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (CorsUtils.isPreFlightRequest(request)) return true
        val exemption = guestExemptions.firstOrNull { request.method == it.method && it.path.matches(request.requestURI) }
            ?: return false
        return !exemption.parseTokenIfPresent || request.getHeader(AUTHORIZATION_HEADER) == null
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val token = bearerToken(request)
            val parsed = tokenParser.parseAccessToken(token)
            // 정리는 바깥 RequestLoggingFilter 의 MDC.clear() 가 일괄 담당한다.
            MDC.put(RequestLoggingFilter.MEMBER_ID_KEY, parsed.memberId.toString())
            if (requiresActiveMember(request, parsed.role) && !isActiveMember(parsed.memberId)) {
                throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
            }
            request.setAttribute(MEMBER_ID_ATTRIBUTE, parsed.memberId)
            request.setAttribute(ROLE_ATTRIBUTE, parsed.roleName)
            filterChain.doFilter(request, response)
        } catch (e: BusinessException) {
            writeFailure(response, e)
        }
    }

    private fun requiresActiveMember(request: HttpServletRequest, role: MemberRole): Boolean =
        role == MemberRole.USER && activeMemberCheckExempt?.matches(request.requestURI) != true

    private fun bearerToken(request: HttpServletRequest): String {
        val header = request.getHeader(AUTHORIZATION_HEADER)
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        }
        return header.removePrefix(BEARER_PREFIX)
    }

    private fun writeFailure(response: HttpServletResponse, e: BusinessException) {
        response.status = e.errorCode.status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(objectMapper.writeValueAsString(BaseResponse.fail(e.errorCode.code, e.errorCode.message)))
    }

    companion object {
        const val MEMBER_ID_ATTRIBUTE: String = "authMemberId"
        const val ROLE_ATTRIBUTE: String = "authMemberRole"
        private const val AUTHORIZATION_HEADER: String = "Authorization"
        private const val BEARER_PREFIX: String = "Bearer "
    }
}
