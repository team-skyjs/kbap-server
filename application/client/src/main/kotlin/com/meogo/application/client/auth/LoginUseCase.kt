package com.meogo.application.client.auth

import com.meogo.core.member.MemberIdentityResolver
import com.meogo.core.member.RefreshTokenStore
import org.springframework.stereotype.Service

data class LoginResult(
    val memberId: Long,
    val newMember: Boolean,
    val accessToken: String,
    val refreshToken: String,
)

@Service
class LoginUseCase(
    private val socialTokenVerifier: SocialTokenVerifier,
    private val memberIdentityResolver: MemberIdentityResolver,
    private val tokenIssuer: TokenIssuer,
    private val refreshTokenStore: RefreshTokenStore,
    private val properties: AuthTokenProperties,
) {
    fun login(idToken: String): LoginResult {
        val identity = socialTokenVerifier.verify(idToken)
        val resolution = memberIdentityResolver.resolve(identity)
        val memberId = resolution.member.id ?: throw AuthException(AuthErrorCode.INVALID_SOCIAL_TOKEN)

        val refreshToken = tokenIssuer.issueRefreshToken(memberId)
        refreshTokenStore.save(refreshToken.jti, memberId, properties.refreshTtl)

        return LoginResult(
            memberId = memberId,
            newMember = resolution.isNewMember,
            accessToken = tokenIssuer.issueAccessToken(memberId),
            refreshToken = refreshToken.token,
        )
    }
}
