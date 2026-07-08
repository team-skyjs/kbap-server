package com.meogo.core.member

import com.meogo.core.kernel.error.ErrorCode

enum class MemberErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    DUPLICATE_SOCIAL_IDENTITY(409, "이미 가입된 소셜 계정입니다"),
    ONBOARDING_ALREADY_COMPLETED(400, "이미 온보딩을 완료했습니다"),
    MEMBER_NOT_FOUND(404, "해당 회원을 찾을 수 없습니다"),
}
