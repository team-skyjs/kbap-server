package com.kbap.api.auth

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.member.model.SocialIdentity
import com.kbap.common.domain.member.model.SocialProvider
import com.kbap.common.port.auth.SocialAccountDeleter
import com.kbap.common.port.auth.SocialTokenVerifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

class FakeSocialTokenVerifier : SocialTokenVerifier {
    private var failure: ErrorCode? = null

    override fun verify(idToken: String): SocialIdentity {
        failure?.let { throw BusinessException(it) }
        return SocialIdentity(SocialProvider.GOOGLE, idToken, DEFAULT_EMAIL)
    }

    fun failWith(errorCode: ErrorCode) {
        failure = errorCode
    }

    fun reset() {
        failure = null
    }

    companion object {
        const val DEFAULT_SUB: String = "google-sub-fixed"
        const val DEFAULT_EMAIL: String = "user@gmail.com"
    }
}

class FakeSocialAccountDeleter : SocialAccountDeleter {
    val deleted: MutableList<Pair<SocialProvider, String>> = mutableListOf()
    private var failing = false

    override fun delete(provider: SocialProvider, providerUserId: String) {
        if (failing) {
            throw IllegalStateException("인증 제공자 계정 삭제 실패 시뮬레이션")
        }
        deleted += provider to providerUserId
    }

    fun fail() {
        failing = true
    }

    fun reset() {
        deleted.clear()
        failing = false
    }
}

@TestConfiguration
class FakeSocialTokenVerifierConfig {
    @Bean
    @Primary
    fun fakeSocialTokenVerifier(): FakeSocialTokenVerifier = FakeSocialTokenVerifier()

    @Bean
    @Primary
    fun fakeSocialAccountDeleter(): FakeSocialAccountDeleter = FakeSocialAccountDeleter()
}
