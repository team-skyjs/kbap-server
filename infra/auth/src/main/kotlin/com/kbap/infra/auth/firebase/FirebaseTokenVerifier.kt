package com.kbap.infra.auth.firebase

import com.kbap.application.auth.social.SocialTokenVerifier
import com.kbap.core.error.ErrorCode
import com.kbap.core.error.KbapException
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.kbap.domain.member.SocialIdentity
import org.slf4j.LoggerFactory

class FirebaseTokenVerifier(
    private val firebaseApp: FirebaseApp,
) : SocialTokenVerifier {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun verify(idToken: String): SocialIdentity {
        val decoded = try {
            FirebaseAuth.getInstance(firebaseApp).verifyIdToken(idToken)
        } catch (e: FirebaseAuthException) {
            log.warn(
                "Firebase ID 토큰 검증 실패: authErrorCode={}, message={}",
                e.authErrorCode,
                e.message,
            )
            throw KbapException(ErrorCode.INVALID_SOCIAL_TOKEN)
        } catch (e: IllegalArgumentException) {
            log.warn("Firebase ID 토큰 형식 오류: {}", e.message)
            throw KbapException(ErrorCode.INVALID_SOCIAL_TOKEN)
        }
        return FirebaseClaimMapper.toSocialIdentity(decoded.claims)
    }
}
