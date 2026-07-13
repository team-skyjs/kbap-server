package com.kbap.application.auth

import com.kbap.application.auth.social.SocialTokenVerifier
import com.kbap.application.auth.token.AuthTokenProperties
import com.kbap.application.auth.token.TokenIssuer
import com.kbap.application.auth.token.TokenParser
import com.kbap.application.auth.dto.LoginResult
import com.kbap.application.auth.dto.RefreshResult
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
import com.kbap.domain.member.MemberService
import com.kbap.domain.member.SocialAccountDeleter
import com.kbap.domain.member.SocialIdentity
import com.kbap.domain.member.MemberRole
import com.kbap.application.auth.token.RefreshTokenStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class AuthApplicationService(
    private val socialTokenVerifier: SocialTokenVerifier,
    private val memberService: MemberService,
    private val tokenIssuer: TokenIssuer,
    private val tokenParser: TokenParser,
    private val refreshTokenStore: RefreshTokenStore,
    private val socialAccountDeleter: SocialAccountDeleter,
    private val properties: AuthTokenProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        } catch (e: BusinessException) {
            if (e.errorCode == ErrorCode.EXPIRED_REFRESH_TOKEN) {
                tokenParser.refreshTokenJtiOrNull(refreshToken)?.let { refreshTokenStore.delete(it) }
            }
            throw e
        }

        val memberId = refreshTokenStore.consume(parsed.jti)
            ?: throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)

        if (memberService.findActive(memberId) == null) {
            throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
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

    // 소셜 계정 삭제(외부 호출)를 트랜잭션 밖에서 먼저 수행하고, DB 탈퇴 마킹은 MemberService 트랜잭션에 맡긴다.
    fun withdraw(memberId: Long) {
        val member = memberService.findActive(memberId)
            ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)

        deleteSocialAccount(memberId, member.identity)

        memberService.withdraw(memberId)
    }

    private fun deleteSocialAccount(memberId: Long, identity: SocialIdentity) {
        try {
            socialAccountDeleter.delete(identity.provider, identity.providerUserId)
        } catch (e: Exception) {
            log.error(
                "소셜 계정 삭제 실패 — 관리자가 콘솔에서 직접 삭제해야 한다: " +
                    "memberId={}, provider={}, providerUserId={}",
                memberId,
                identity.provider,
                identity.providerUserId,
                e,
            )
            throw BusinessException(ErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
        }
    }
}
