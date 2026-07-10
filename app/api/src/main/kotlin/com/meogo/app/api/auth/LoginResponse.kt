package com.meogo.app.api.auth

import com.meogo.application.client.auth.LoginResult

data class LoginResponse(
    val memberId: Long,
    val newMember: Boolean,
) {
    companion object {
        fun from(result: LoginResult): LoginResponse =
            LoginResponse(memberId = result.memberId, newMember = result.newMember)
    }
}
