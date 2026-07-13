package com.kbap.domain.member


interface SocialAccountDeleter {
    fun delete(provider: SocialProvider, providerUserId: String)
}
