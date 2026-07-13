package com.kbap.application.auth

import com.kbap.domain.member.SocialIdentity

interface SocialTokenVerifier {
    fun verify(idToken: String): SocialIdentity
}
