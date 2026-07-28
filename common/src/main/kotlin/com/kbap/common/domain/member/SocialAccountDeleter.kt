package com.kbap.common.domain.member

import com.kbap.common.domain.member.model.SocialProvider

interface SocialAccountDeleter {
    fun delete(provider: SocialProvider, providerUserId: String)
}
