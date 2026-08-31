package com.kbap.api.core.auth

import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.logging.RequestLoggingFilter
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
) : OncePerRequestFilter() {
    data class GuestExemption(val method: String, val path: Regex)

    private val objectMapper = jacksonObjectMapper()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        CorsUtils.isPreFlightRequest(request) ||
            guestExemptions.any { request.method == it.method && it.path.matches(request.requestURI) }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val token = bearerToken(request)
            val parsed = tokenParser.parseAccessToken(token)
            request.setAttribute(MEMBER_ID_ATTRIBUTE, parsed.memberId)
            request.setAttribute(ROLE_ATTRIBUTE, parsed.roleName)
            // 정리는 바깥 RequestLoggingFilter 의 MDC.clear() 가 일괄 담당한다.
            MDC.put(RequestLoggingFilter.MEMBER_ID_KEY, parsed.memberId.toString())
            filterChain.doFilter(request, response)
        } catch (e: BusinessException) {
            writeUnauthorized(response, e)
        }
    }

    private fun bearerToken(request: HttpServletRequest): String {
        val header = request.getHeader(AUTHORIZATION_HEADER)
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw BusinessException(ErrorCode.INVALID_ACCESS_TOKEN)
        }
        return header.removePrefix(BEARER_PREFIX)
    }

    private fun writeUnauthorized(response: HttpServletResponse, e: BusinessException) {
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
