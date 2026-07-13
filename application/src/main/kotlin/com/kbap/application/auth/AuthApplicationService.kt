package com.kbap.application.auth

import com.kbap.application.auth.social.SocialTokenVerifier
import com.kbap.application.auth.token.AuthTokenProperties
import com.kbap.application.auth.token.TokenIssuer
import com.kbap.application.auth.token.TokenParser
import com.kbap.application.auth.dto.LoginResult
import com.kbap.application.auth.dto.RefreshResult
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.kbap.domain.member.MemberService
import com.kbap.domain.member.MemberRole
import com.kbap.application.auth.token.RefreshTokenStore
import org.springframework.stereotype.Service

@Service
class AuthApplicationService(
    private val socialTokenVerifier: SocialTokenVerifier,
    private val memberService: MemberService,
    private val tokenIssuer: TokenIssuer,
    private val tokenParser: TokenParser,
    private val refreshTokenStore: RefreshTokenStore,
    private val properties: AuthTokenProperties,
) {
    fun login(idToken: String): LoginResult {
        val identity = socialTokenVerifier.verify(idToken)
        val (member, isNewMember) = memberService.findOrSignUp(identity)
        val memberId = member.id

        val refreshToken = tokenIssuer.issueRefreshToken(memberId)
        refreshTokenStore.save(refreshToken.jti, memberId, properties.refreshTtl)

        return LoginResult(
            memberId = memberId,
            newMember = isNewMember,
            accessToken = tokenIssuer.issueAccessToken(memberId, MemberRole.USER),
            refreshToken = refreshToken.token,
        )
    }

    fun refresh(refreshToken: String): RefreshResult {
        val parsed = try {
            tokenParser.parseRefreshToken(refreshToken)
        } catch (e: KbapException) {
            if (e.errorCode == ErrorCode.EXPIRED_REFRESH_TOKEN) {
                tokenParser.refreshTokenJtiOrNull(refreshToken)?.let { refreshTokenStore.delete(it) }
            }
            throw e
        }

        val memberId = refreshTokenStore.consume(parsed.jti)
            ?: throw KbapException(ErrorCode.INVALID_REFRESH_TOKEN)

        if (memberService.findActive(memberId) == null) {
            throw KbapException(ErrorCode.INVALID_REFRESH_TOKEN)
        }

        val rotated = tokenIssuer.issueRefreshToken(memberId)
        refreshTokenStore.save(rotated.jti, memberId, properties.refreshTtl)

        return RefreshResult(
            accessToken = tokenIssuer.issueAccessToken(memberId, MemberRole.USER),
            refreshToken = rotated.token,
        )
    }

    fun logout(refreshToken: String?) {
        if (refreshToken.isNullOrBlank()) {
            return
        }
        val jti = tokenParser.refreshTokenJtiOrNull(refreshToken) ?: return
        refreshTokenStore.delete(jti)
    }

}
