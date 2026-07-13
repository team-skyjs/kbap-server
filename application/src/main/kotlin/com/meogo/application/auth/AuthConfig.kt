package com.meogo.application.auth

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.meogo.domain.member.SocialProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.ByteArrayInputStream
import java.time.Duration

@Configuration
class AuthConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun authTokenProperties(
        @Value("\${meogo.auth.jwt.secret}") secret: String,
        @Value("\${meogo.auth.jwt.access-ttl}") accessTtl: Duration,
        @Value("\${meogo.auth.jwt.refresh-ttl}") refreshTtl: Duration,
    ): AuthTokenProperties = AuthTokenProperties(secret = secret, accessTtl = accessTtl, refreshTtl = refreshTtl)

    @Bean
    fun socialTokenVerifier(
        @Value("\${meogo.auth.firebase.credentials-json:}") credentialsJson: String,
        @Value("\${meogo.auth.firebase.credentials-path:}") credentialsPath: String,
    ): SocialTokenVerifier {
        val app = firebaseAppOrNull(credentialsJson, credentialsPath)
        if (app == null) {
            log.warn("Firebase 자격증명 미설정 — 소셜 로그인 비활성(모든 로그인이 401 로 거절된다)")
            return UnavailableSocialAuth
        }
        log.info("Firebase 토큰 검증기 활성화")
        return FirebaseTokenVerifier(app)
    }

    @Bean
    fun socialAccountDeleter(
        @Value("\${meogo.auth.firebase.credentials-json:}") credentialsJson: String,
        @Value("\${meogo.auth.firebase.credentials-path:}") credentialsPath: String,
    ): SocialAccountDeleter {
        val app = firebaseAppOrNull(credentialsJson, credentialsPath)
        if (app == null) {
            log.warn("Firebase 자격증명 미설정 — 소셜 계정 삭제 비활성(모든 탈퇴가 500 으로 거절된다)")
            return UnavailableSocialAuth
        }
        log.info("Firebase 계정 삭제기 활성화")
        return FirebaseAccountDeleter(app)
    }

    private fun firebaseAppOrNull(credentialsJson: String, credentialsPath: String): FirebaseApp? =
        FirebaseCredentialsSource.resolve(json = credentialsJson, path = credentialsPath)
            ?.let { firebaseApp(it) }

    private fun firebaseApp(credentials: ByteArray): FirebaseApp {
        FirebaseApp.getApps().firstOrNull()?.let { return it }
        val options = FirebaseOptions.builder()
            .setCredentials(ByteArrayInputStream(credentials).use { GoogleCredentials.fromStream(it) })
            .setHttpTransport(NetHttpTransport())
            .build()
        return FirebaseApp.initializeApp(options)
    }
}

private object UnavailableSocialAuth : SocialTokenVerifier, SocialAccountDeleter {
    override fun verify(idToken: String) = throw AuthException(AuthErrorCode.INVALID_SOCIAL_TOKEN)

    override fun delete(provider: SocialProvider, providerUserId: String) =
        throw AuthException(AuthErrorCode.SOCIAL_ACCOUNT_DELETE_FAILED)
}
