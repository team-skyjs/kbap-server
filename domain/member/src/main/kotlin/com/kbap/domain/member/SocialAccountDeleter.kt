package com.kbap.domain.member

import com.kbap.domain.member.model.SocialProvider

interface SocialAccountDeleter {
    fun delete(provider: SocialProvider, providerUserId: String)
}
