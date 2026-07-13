package com.kbap.application.auth.social

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.kbap.domain.member.SocialAccountDeleter
import com.kbap.domain.member.SocialIdentity
import com.kbap.domain.member.SocialProvider

// 자격증명 미설정 환경에서 소셜 인증/삭제를 명시적으로 거절하는 폴백 구현.
object UnavailableSocialAuth : SocialTokenVerifier, SocialAccountDeleter {
    override fun verify(idToken: String): SocialIdentity = throw KbapException(ErrorCode.INVALID_SOCIAL_TOKEN)

    override fun delete(provider: SocialProvider, providerUserId: String) {
        throw KbapException(ErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
    }
}
