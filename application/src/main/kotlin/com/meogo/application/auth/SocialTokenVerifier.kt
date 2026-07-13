package com.meogo.application.auth

import com.meogo.domain.member.SocialIdentity

interface SocialTokenVerifier {
    fun verify(idToken: String): SocialIdentity
}
