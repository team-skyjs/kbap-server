package com.kbap.api.infra.auth.token

enum class TokenType {
    ACCESS,
    REFRESH,
    ;

    companion object {
        const val CLAIM: String = "token_type"
    }
}
