package com.meogo.application.client.auth

import com.meogo.core.member.SocialIdentity

interface SocialTokenVerifier {
    fun verify(idToken: String): SocialIdentity
}
