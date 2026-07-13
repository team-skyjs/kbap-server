package com.kbap.application.auth

enum class TokenType {
    ACCESS,
    REFRESH,
    ;

    companion object {
        const val CLAIM: String = "token_type"
    }
}
