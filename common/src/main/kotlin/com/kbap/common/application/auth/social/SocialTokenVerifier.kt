package com.kbap.common.application.auth.social

import com.kbap.common.domain.member.model.SocialIdentity

interface SocialTokenVerifier {
    fun verify(idToken: String): SocialIdentity
}
