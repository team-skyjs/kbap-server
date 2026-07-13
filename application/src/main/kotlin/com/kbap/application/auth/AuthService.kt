package com.kbap.application.auth

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.kbap.domain.member.Member
import com.kbap.domain.member.MemberJpaRepository
import com.kbap.domain.member.MemberRole
import com.kbap.domain.member.MemberStatus
import com.kbap.domain.member.RefreshTokenStore
import com.kbap.domain.member.SocialIdentity
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

data class LoginResult(
    val memberId: Long,
    val newMember: Boolean,
    val accessToken: String,
    val refreshToken: String,
)

data class RefreshResult(
    val accessToken: String,
    val refreshToken: String,
)

@Service
class AuthService(
    private val socialTokenVerifier: SocialTokenVerifier,
    private val memberRepository: MemberJpaRepository,
    private val tokenIssuer: TokenIssuer,
    private val tokenParser: TokenParser,
    private val refreshTokenStore: RefreshTokenStore,
    private val properties: AuthTokenProperties,
) {
    fun login(idToken: String): LoginResult {
        val identity = socialTokenVerifier.verify(idToken)
        val (member, isNewMember) = resolveMember(identity)
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

        if (memberRepository.findByIdAndMemberStatus(memberId, MemberStatus.ACTIVE) == null) {
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

    private fun resolveMember(identity: SocialIdentity): Pair<Member, Boolean> {
        findActive(identity)?.let { return it to false }

        return try {
            memberRepository.save(Member.signUp(identity)) to true
        } catch (e: DataIntegrityViolationException) {
            val existing = findActive(identity)
                ?: throw KbapException(ErrorCode.DUPLICATE_SOCIAL_IDENTITY)
            existing to false
        }
    }

    private fun findActive(identity: SocialIdentity): Member? =
        memberRepository.findByProviderAndProviderUidAndMemberStatus(
            identity.provider,
            identity.providerUserId,
            MemberStatus.ACTIVE,
        )
}
