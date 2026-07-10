package com.meogo.application.client.auth

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.meogo.core.member.MemberIdentityResolver
import com.meogo.core.member.MemberRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.io.FileInputStream
import java.time.Duration

@Configuration
class AuthConfig {

    @Bean
    fun memberIdentityResolver(memberRepository: MemberRepository): MemberIdentityResolver =
        MemberIdentityResolver(memberRepository)

    @Bean
    fun authTokenProperties(
        @Value("\${meogo.auth.jwt.secret}") secret: String,
        @Value("\${meogo.auth.jwt.access-ttl}") accessTtl: Duration,
        @Value("\${meogo.auth.jwt.refresh-ttl}") refreshTtl: Duration,
    ): AuthTokenProperties = AuthTokenProperties(secret = secret, accessTtl = accessTtl, refreshTtl = refreshTtl)

    @Bean
    fun socialTokenVerifier(
        @Value("\${meogo.auth.firebase.credentials-path:}") credentialsPath: String,
    ): SocialTokenVerifier {
        if (credentialsPath.isBlank()) {
            return UnavailableSocialTokenVerifier
        }
        return FirebaseTokenVerifier(firebaseApp(credentialsPath))
    }

    private fun firebaseApp(credentialsPath: String): FirebaseApp {
        FirebaseApp.getApps().firstOrNull()?.let { return it }
        val options = FirebaseOptions.builder()
            .setCredentials(FileInputStream(credentialsPath).use { GoogleCredentials.fromStream(it) })
            .build()
        return FirebaseApp.initializeApp(options)
    }
}

private object UnavailableSocialTokenVerifier : SocialTokenVerifier {
    override fun verify(idToken: String) = throw AuthException(AuthErrorCode.INVALID_SOCIAL_TOKEN)
}
