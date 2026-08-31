package com.kbap.api.infra.auth.token

enum class TokenType {
    ACCESS,
    REFRESH,
    SCAN_TICKET,
    ;

    companion object {
        const val CLAIM: String = "token_type"
    }
}
