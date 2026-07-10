package com.meogo.application.client.auth

import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.meogo.core.member.SocialIdentity

class FirebaseTokenVerifier(
    private val firebaseApp: FirebaseApp,
) : SocialTokenVerifier {
    override fun verify(idToken: String): SocialIdentity {
        val decoded = try {
            FirebaseAuth.getInstance(firebaseApp).verifyIdToken(idToken)
        } catch (e: FirebaseAuthException) {
            throw AuthException(AuthErrorCode.INVALID_SOCIAL_TOKEN)
        } catch (e: IllegalArgumentException) {
            throw AuthException(AuthErrorCode.INVALID_SOCIAL_TOKEN)
        }
        return FirebaseClaimMapper.toSocialIdentity(decoded.claims)
    }
}
