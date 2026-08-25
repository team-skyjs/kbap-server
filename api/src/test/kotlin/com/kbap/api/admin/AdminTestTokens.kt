package com.kbap.api.admin

import com.kbap.common.domain.admin.AdminAccountJpaRepository
import com.kbap.common.domain.admin.model.AdminAccount
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.port.auth.TokenIssuer
import jakarta.servlet.http.Cookie
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.web.servlet.MockHttpServletRequestDsl

object AdminTestTokens {
    const val API_VERSION = "1.0"

    private val encoder = BCryptPasswordEncoder()

    fun adminAccessToken(tokenIssuer: TokenIssuer, adminId: Long = 1L): String =
        tokenIssuer.issueAccessToken(adminId, MemberRole.ADMIN)

    fun userAccessToken(tokenIssuer: TokenIssuer, memberId: Long): String =
        tokenIssuer.issueAccessToken(memberId, MemberRole.USER)

    fun adminCookie(tokenIssuer: TokenIssuer, adminId: Long = 1L): Cookie =
        Cookie(AdminPageAuthInterceptor.COOKIE_NAME, adminAccessToken(tokenIssuer, adminId))

    fun seedAdminAccount(
        repository: AdminAccountJpaRepository,
        loginId: String = "admin",
        rawPassword: String = "changeit",
    ): AdminAccount = repository.save(AdminAccount(loginId = loginId, password = encoder.encode(rawPassword)!!))

    fun MockHttpServletRequestDsl.adminHeaders(token: String) {
        header("Authorization", "Bearer $token")
        header("X-API-Version", API_VERSION)
    }
}
