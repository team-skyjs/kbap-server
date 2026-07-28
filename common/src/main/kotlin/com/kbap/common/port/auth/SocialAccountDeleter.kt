package com.kbap.common.port.auth

import com.kbap.common.domain.member.model.SocialProvider

interface SocialAccountDeleter {
    fun delete(provider: SocialProvider, providerUserId: String)
}
