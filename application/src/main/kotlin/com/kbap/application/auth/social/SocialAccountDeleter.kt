package com.kbap.application.auth.social

import com.kbap.domain.member.SocialProvider

interface SocialAccountDeleter {
    fun delete(provider: SocialProvider, providerUserId: String)
}
