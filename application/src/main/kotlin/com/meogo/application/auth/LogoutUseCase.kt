package com.meogo.application.auth

import com.meogo.domain.member.RefreshTokenStore
import org.springframework.stereotype.Service

@Service
class LogoutUseCase(
    private val tokenParser: TokenParser,
    private val refreshTokenStore: RefreshTokenStore,
) {
    fun logout(refreshToken: String?) {
        if (refreshToken.isNullOrBlank()) {
            return
        }
        val jti = tokenParser.refreshTokenJtiOrNull(refreshToken) ?: return
        refreshTokenStore.delete(jti)
    }
}
