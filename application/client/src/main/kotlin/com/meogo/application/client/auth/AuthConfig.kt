package com.meogo.application.client.auth

import com.google.api.client.http.javanet.NetHttpTransport
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
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
        val credentials = FirebaseCredentialsSource.resolve(json = credentialsJson, path = credentialsPath)
        if (credentials == null) {
            log.warn("Firebase 자격증명 미설정 — 소셜 로그인 비활성(모든 로그인이 401 로 거절된다)")
            return UnavailableSocialTokenVerifier
        }
        log.info("Firebase 토큰 검증기 활성화")
        return FirebaseTokenVerifier(firebaseApp(credentials))
    }

    private fun firebaseApp(credentials: ByteArray): FirebaseApp {
        FirebaseApp.getApps().firstOrNull()?.let { return it }
        val options = FirebaseOptions.builder()
            .setCredentials(ByteArrayInputStream(credentials).use { GoogleCredentials.fromStream(it) })
            .setHttpTransport(NetHttpTransport())
            .build()
        return FirebaseApp.initializeApp(options)
    }
}

private object UnavailableSocialTokenVerifier : SocialTokenVerifier {
    override fun verify(idToken: String) = throw AuthException(AuthErrorCode.INVALID_SOCIAL_TOKEN)
}
