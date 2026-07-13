package com.meogo.application.auth

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.meogo.domain.member.SocialProvider
import org.slf4j.LoggerFactory
import com.google.firebase.auth.AuthErrorCode as FirebaseAuthErrorCode

class FirebaseAccountDeleter(
    private val firebaseApp: FirebaseApp,
) : SocialAccountDeleter {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun delete(provider: SocialProvider, providerUserId: String) {
        val auth = FirebaseAuth.getInstance(firebaseApp)
        val uid = try {
            auth.getUserByProviderUid(firebaseProviderId(provider), providerUserId).uid
        } catch (e: FirebaseAuthException) {
            if (e.authErrorCode == FirebaseAuthErrorCode.USER_NOT_FOUND) {
                log.info(
                    "Firebase 사용자 기록이 이미 없어 삭제를 건너뛴다: provider={}, providerUserId={}",
                    provider,
                    providerUserId,
                )
                return
            }
            throw e
        }
        auth.deleteUser(uid)
    }

    private fun firebaseProviderId(provider: SocialProvider): String =
        when (provider) {
            SocialProvider.GOOGLE -> "google.com"
            SocialProvider.APPLE -> "apple.com"
        }
}
