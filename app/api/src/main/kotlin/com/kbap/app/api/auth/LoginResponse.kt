package com.kbap.app.api.auth

import com.kbap.application.auth.LoginResult

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
