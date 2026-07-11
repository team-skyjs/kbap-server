package com.meogo.app.api.common.auth

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.meogo.app.api.common.BaseResponse
import com.meogo.application.client.auth.AuthErrorCode
import com.meogo.application.client.auth.AuthException
import com.meogo.application.client.auth.TokenParser
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

class JwtAuthenticationFilter(
    private val tokenParser: TokenParser,
) : OncePerRequestFilter() {
    private val objectMapper = jacksonObjectMapper()

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
            filterChain.doFilter(request, response)
        } catch (e: AuthException) {
            writeUnauthorized(response, e)
        }
    }

    private fun bearerToken(request: HttpServletRequest): String {
        val header = request.getHeader(AUTHORIZATION_HEADER)
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw AuthException(AuthErrorCode.INVALID_ACCESS_TOKEN)
        }
        return header.removePrefix(BEARER_PREFIX)
    }

    private fun writeUnauthorized(response: HttpServletResponse, e: AuthException) {
        response.status = e.errorCode.status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(objectMapper.writeValueAsString(BaseResponse.fail(e.errorCode.message)))
    }

    companion object {
        const val MEMBER_ID_ATTRIBUTE: String = "authMemberId"
        const val ROLE_ATTRIBUTE: String = "authMemberRole"
        private const val AUTHORIZATION_HEADER: String = "Authorization"
        private const val BEARER_PREFIX: String = "Bearer "
    }
}
