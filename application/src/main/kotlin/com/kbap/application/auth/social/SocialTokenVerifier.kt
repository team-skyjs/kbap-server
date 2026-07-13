package com.kbap.application.auth.social

import com.kbap.domain.member.SocialIdentity

interface SocialTokenVerifier {
    fun verify(idToken: String): SocialIdentity
}
