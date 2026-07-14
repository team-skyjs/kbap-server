package com.kbap.application.auth.social

import com.kbap.core.error.ErrorCode
import com.kbap.core.error.BusinessException
import com.kbap.domain.member.SocialAccountDeleter
import com.kbap.domain.member.model.SocialIdentity
import com.kbap.domain.member.model.SocialProvider

// 자격증명 미설정 환경에서 소셜 인증/삭제를 명시적으로 거절하는 폴백 구현.
object UnavailableSocialAuth : SocialTokenVerifier, SocialAccountDeleter {
    override fun verify(idToken: String): SocialIdentity = throw BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN)

    override fun delete(provider: SocialProvider, providerUserId: String) {
        throw BusinessException(ErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
    }
}
