package com.meogo.application.client.auth

import com.meogo.core.error.ErrorCode

enum class AuthErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    INVALID_SOCIAL_TOKEN(401, "유효하지 않은 소셜 인증 토큰입니다"),
    UNSUPPORTED_PROVIDER(401, "지원하지 않는 소셜 로그인 제공자입니다"),
    INVALID_ACCESS_TOKEN(401, "유효하지 않은 인증 토큰입니다"),
    EXPIRED_ACCESS_TOKEN(401, "만료된 인증 토큰입니다"),
    INVALID_REFRESH_TOKEN(401, "유효하지 않은 갱신 토큰입니다"),
    EXPIRED_REFRESH_TOKEN(401, "만료된 갱신 토큰입니다"),
    SOCIAL_ACCOUNT_DELETE_FAILED(500, "소셜 계정 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요"),
}
