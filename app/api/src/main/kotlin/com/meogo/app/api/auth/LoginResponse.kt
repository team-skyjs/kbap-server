package com.meogo.app.api.auth

import com.meogo.application.client.auth.LoginResult

data class LoginResponse(
    val newMember: Boolean,
    val accessToken: String,
    val refreshToken: String,
) {
    companion object {
        fun from(result: LoginResult): LoginResponse =
            LoginResponse(
                newMember = result.newMember,
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
            )
    }
}
