package com.kbap.api.auth

import com.kbap.common.port.auth.SocialTokenVerifier
import com.kbap.common.port.auth.RefreshTokenStore
import com.kbap.common.port.auth.TokenIssuer
import com.kbap.common.port.auth.TokenParser
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.api.member.MemberService
import com.kbap.common.port.auth.SocialAccountDeleter
import com.kbap.common.domain.member.model.MemberRole
import com.kbap.common.domain.member.model.SocialIdentity
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class AuthService(
    private val socialTokenVerifier: SocialTokenVerifier,
    private val memberService: MemberService,
    private val tokenIssuer: TokenIssuer,
    private val tokenParser: TokenParser,
    private val refreshTokenStore: RefreshTokenStore,
    private val socialAccountDeleter: SocialAccountDeleter,
    @Value("\${kbap.auth.jwt.refresh-ttl}") private val refreshTtl: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun login(idToken: String): LoginResult {
        val identity = socialTokenVerifier.verify(idToken)
        val (member, isNewMember) = memberService.findOrSignUp(identity)
        val memberId = member.id

        val refreshToken = tokenIssuer.issueRefreshToken(memberId)
        refreshTokenStore.save(refreshToken.jti, memberId, refreshTtl)

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

        if (memberService.getMemberOrNull(memberId) == null) {
            throw BusinessException(ErrorCode.INVALID_REFRESH_TOKEN)
        }

        val rotated = tokenIssuer.issueRefreshToken(memberId)
        refreshTokenStore.save(rotated.jti, memberId, refreshTtl)

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
        val member = memberService.getMember(memberId)

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
