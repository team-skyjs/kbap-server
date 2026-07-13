package com.kbap.domain.member

import com.kbap.core.error.ErrorCode

enum class MemberErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    DUPLICATE_SOCIAL_IDENTITY(409, "이미 가입된 소셜 계정입니다"),
    ONBOARDING_ALREADY_COMPLETED(400, "이미 온보딩을 완료했습니다"),
    MEMBER_NOT_FOUND(400, "해당 회원을 찾을 수 없습니다"),
}
