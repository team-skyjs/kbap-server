package com.kbap.application.auth.social

import com.kbap.common.application.auth.social.SocialTokenVerifier
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.member.SocialAccountDeleter
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider

object UnavailableSocialAuth : SocialTokenVerifier, SocialAccountDeleter {
    override fun verify(idToken: String): SocialIdentity = throw BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN)

    override fun delete(provider: SocialProvider, providerUserId: String) {
        throw BusinessException(ErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
    }
}
