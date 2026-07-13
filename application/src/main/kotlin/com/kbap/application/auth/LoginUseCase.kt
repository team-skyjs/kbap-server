package com.kbap.application.auth

import com.kbap.domain.member.Member
import com.kbap.domain.member.MemberErrorCode
import com.kbap.domain.member.MemberException
import com.kbap.domain.member.MemberService
import com.kbap.domain.member.MemberRole
import com.kbap.domain.member.RefreshTokenStore
import com.kbap.domain.member.SocialIdentity
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
    private val memberService: MemberService,
    private val tokenIssuer: TokenIssuer,
    private val refreshTokenStore: RefreshTokenStore,
    private val properties: AuthTokenProperties,
) {
    fun login(idToken: String): LoginResult {
        val identity = socialTokenVerifier.verify(idToken)
        val (member, isNewMember) = resolveMember(identity)
        val memberId = member.id ?: throw AuthException(AuthErrorCode.INVALID_SOCIAL_TOKEN)

        val refreshToken = tokenIssuer.issueRefreshToken(memberId)
        refreshTokenStore.save(refreshToken.jti, memberId, properties.refreshTtl)

        return LoginResult(
            memberId = memberId,
            newMember = isNewMember,
            accessToken = tokenIssuer.issueAccessToken(memberId, MemberRole.USER),
            refreshToken = refreshToken.token,
        )
    }

    private fun resolveMember(identity: SocialIdentity): Pair<Member, Boolean> {
        memberService.findByIdentity(identity.provider, identity.providerUserId)?.let {
            return it to false
        }

        return try {
            memberService.saveNew(Member.signUp(identity)) to true
        } catch (e: MemberException) {
            if (e.errorCode != MemberErrorCode.DUPLICATE_SOCIAL_IDENTITY) throw e
            val existing = memberService.findByIdentity(identity.provider, identity.providerUserId)
                ?: throw e
            existing to false
        }
    }
}
