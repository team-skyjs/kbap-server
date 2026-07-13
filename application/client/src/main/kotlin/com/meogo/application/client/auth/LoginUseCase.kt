package com.meogo.application.client.auth

import com.meogo.domain.member.Member
import com.meogo.domain.member.MemberErrorCode
import com.meogo.domain.member.MemberException
import com.meogo.domain.member.MemberRepository
import com.meogo.domain.member.MemberRole
import com.meogo.domain.member.RefreshTokenStore
import com.meogo.domain.member.SocialIdentity
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
    private val memberRepository: MemberRepository,
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
        memberRepository.findByIdentity(identity.provider, identity.providerUserId)?.let {
            return it to false
        }

        return try {
            memberRepository.saveNew(Member.signUp(identity)) to true
        } catch (e: MemberException) {
            if (e.errorCode != MemberErrorCode.DUPLICATE_SOCIAL_IDENTITY) throw e
            val existing = memberRepository.findByIdentity(identity.provider, identity.providerUserId)
                ?: throw e
            existing to false
        }
    }
}
