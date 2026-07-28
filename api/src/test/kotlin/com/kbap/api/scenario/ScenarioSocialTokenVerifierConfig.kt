package com.kbap.api.scenario

import com.kbap.common.application.auth.social.SocialTokenVerifier
import com.kbap.common.domain.member.SocialAccountDeleter
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class ScenarioSocialTokenVerifierConfig {
    @Bean
    @Primary
    fun scenarioSocialTokenVerifier(): SocialTokenVerifier = object : SocialTokenVerifier {
        override fun verify(idToken: String): SocialIdentity =
            SocialIdentity(provider = SocialProvider.GOOGLE, providerUserId = idToken, email = null)
    }

    @Bean
    @Primary
    fun scenarioSocialAccountDeleter(): SocialAccountDeleter = object : SocialAccountDeleter {
        override fun delete(provider: SocialProvider, providerUserId: String) = Unit
    }
}
