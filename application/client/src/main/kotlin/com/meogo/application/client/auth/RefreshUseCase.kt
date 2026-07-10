package com.meogo.application.client.auth

import com.meogo.core.member.RefreshTokenStore
import org.springframework.stereotype.Service

data class RefreshResult(
    val accessToken: String,
    val refreshToken: String,
)

@Service
class RefreshUseCase(
    private val tokenIssuer: TokenIssuer,
    private val tokenParser: TokenParser,
    private val refreshTokenStore: RefreshTokenStore,
    private val properties: AuthTokenProperties,
) {
    fun refresh(refreshToken: String): RefreshResult {
        val parsed = try {
            tokenParser.parseRefreshToken(refreshToken)
        } catch (e: AuthException) {
            if (e.errorCode == AuthErrorCode.EXPIRED_REFRESH_TOKEN) {
                tokenParser.refreshTokenJtiOrNull(refreshToken)?.let { refreshTokenStore.delete(it) }
            }
            throw e
        }

        val memberId = refreshTokenStore.consume(parsed.jti)
            ?: throw AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN)

        val rotated = tokenIssuer.issueRefreshToken(memberId)
        refreshTokenStore.save(rotated.jti, memberId, properties.refreshTtl)

        return RefreshResult(
            accessToken = tokenIssuer.issueAccessToken(memberId),
            refreshToken = rotated.token,
        )
    }
}
