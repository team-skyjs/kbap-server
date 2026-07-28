package com.kbap.common.port.auth

import com.kbap.common.domain.member.model.SocialIdentity

interface SocialTokenVerifier {
    fun verify(idToken: String): SocialIdentity
}
