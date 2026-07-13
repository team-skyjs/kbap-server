package com.meogo.application.auth

import com.meogo.domain.member.SocialProvider

interface SocialAccountDeleter {
    fun delete(provider: SocialProvider, providerUserId: String)
}
