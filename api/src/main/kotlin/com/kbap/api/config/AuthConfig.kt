package com.kbap.api.config

import com.kbap.common.domain.member.SocialAccountDeleter
import com.kbap.common.application.auth.social.SocialTokenVerifier
import com.kbap.application.auth.social.UnavailableSocialAuth
import com.kbap.common.application.auth.token.AuthTokenProperties
import com.kbap.infra.auth.firebase.FirebaseSocialAuth
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Duration

@Configuration
class AuthConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun authTokenProperties(
        @Value("\${kbap.auth.jwt.secret}") secret: String,
        @Value("\${kbap.auth.jwt.access-ttl}") accessTtl: Duration,
        @Value("\${kbap.auth.jwt.refresh-ttl}") refreshTtl: Duration,
    ): AuthTokenProperties = AuthTokenProperties(secret = secret, accessTtl = accessTtl, refreshTtl = refreshTtl)

    @Bean
    fun socialTokenVerifier(
        @Value("\${kbap.auth.firebase.credentials-json:}") credentialsJson: String,
        @Value("\${kbap.auth.firebase.credentials-path:}") credentialsPath: String,
    ): SocialTokenVerifier {
        val verifier = FirebaseSocialAuth.tokenVerifierOrNull(credentialsJson, credentialsPath)
        if (verifier == null) {
            log.warn("Firebase 자격증명 미설정 — 소셜 로그인 비활성(모든 로그인이 401 로 거절된다)")
            return UnavailableSocialAuth
        }
        log.info("Firebase 토큰 검증기 활성화")
        return verifier
    }

    @Bean
    fun socialAccountDeleter(
        @Value("\${kbap.auth.firebase.credentials-json:}") credentialsJson: String,
        @Value("\${kbap.auth.firebase.credentials-path:}") credentialsPath: String,
    ): SocialAccountDeleter {
        val deleter = FirebaseSocialAuth.accountDeleterOrNull(credentialsJson, credentialsPath)
        if (deleter == null) {
            log.warn("Firebase 자격증명 미설정 — 소셜 계정 삭제 비활성(모든 탈퇴가 500 으로 거절된다)")
            return UnavailableSocialAuth
        }
        log.info("Firebase 계정 삭제기 활성화")
        return deleter
    }
}
