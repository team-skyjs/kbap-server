package com.meogo.application.member

import com.meogo.core.error.ErrorCode

enum class OnboardingErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    INVALID_NICKNAME(400, "닉네임은 비어 있을 수 없습니다"),
    INVALID_AVOIDANCE_SUBSTANCE_CODE(400, "지원하지 않는 기피 성분 코드입니다"),
    INVALID_COUNTRY_CODE(400, "지원하지 않는 국가 코드입니다"),
    UNSUPPORTED_APP_LANGUAGE(400, "지원하지 않는 언어입니다"),
}
