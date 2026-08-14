package com.kbap.api.infra.auth.firebase

import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.error.BusinessException
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider

object FirebaseClaimMapper {
    private const val FIREBASE_CLAIM = "firebase"
    private const val SIGN_IN_PROVIDER = "sign_in_provider"
    private const val IDENTITIES = "identities"
    private const val EMAIL = "email"

    private val PROVIDERS = mapOf(
        "google.com" to SocialProvider.GOOGLE,
        "apple.com" to SocialProvider.APPLE,
    )

    fun toSocialIdentity(claims: Map<String, Any?>): SocialIdentity {
        val firebase = claims[FIREBASE_CLAIM] as? Map<*, *> ?: throw BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN)
        val signInProvider = firebase[SIGN_IN_PROVIDER] as? String
            ?: throw BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN)
        val provider = PROVIDERS[signInProvider] ?: throw BusinessException(ErrorCode.UNSUPPORTED_PROVIDER)

        val identities = firebase[IDENTITIES] as? Map<*, *> ?: throw BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN)
        val providerUserId = (identities[signInProvider] as? List<*>)
            ?.firstOrNull()
            ?.toString()
            ?.takeIf { it.isNotBlank() }
            ?: throw BusinessException(ErrorCode.INVALID_SOCIAL_TOKEN)

        return SocialIdentity(
            provider = provider,
            providerUserId = providerUserId,
            email = claims[EMAIL] as? String,
        )
    }
}
