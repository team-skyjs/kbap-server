package com.meogo.application.client.member

import com.meogo.application.client.auth.AuthErrorCode
import com.meogo.application.client.auth.AuthException
import com.meogo.application.client.auth.SocialAccountDeleter
import com.meogo.domain.member.MemberErrorCode
import com.meogo.domain.member.MemberException
import com.meogo.domain.member.MemberRepository
import com.meogo.domain.member.SocialIdentity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class WithdrawUseCase(
    private val memberRepository: MemberRepository,
    private val socialAccountDeleter: SocialAccountDeleter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun withdraw(memberId: Long) {
        val member = memberRepository.findById(memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)

        deleteSocialAccount(memberId, member.identity)

        memberRepository.withdraw(memberId)
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
            throw AuthException(AuthErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
        }
    }
}
