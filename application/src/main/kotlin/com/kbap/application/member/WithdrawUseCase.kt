package com.kbap.application.member

import com.kbap.application.auth.AuthErrorCode
import com.kbap.application.auth.AuthException
import com.kbap.application.auth.SocialAccountDeleter
import com.kbap.domain.member.MemberErrorCode
import com.kbap.domain.member.MemberException
import com.kbap.domain.member.MemberService
import com.kbap.domain.member.SocialIdentity
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class WithdrawUseCase(
    private val memberService: MemberService,
    private val socialAccountDeleter: SocialAccountDeleter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun withdraw(memberId: Long) {
        val member = memberService.findById(memberId)
            ?: throw MemberException(MemberErrorCode.MEMBER_NOT_FOUND)

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
            throw AuthException(AuthErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
        }
    }
}
