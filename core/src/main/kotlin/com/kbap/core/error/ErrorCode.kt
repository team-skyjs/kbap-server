package com.kbap.core.error

import com.kbap.core.lang.LanguageCode

enum class ErrorCode(
    val status: Int,
    val message: String,
) {
    // 언어
    UNSUPPORTED_LANGUAGE(
        400,
        "지원하지 않는 언어 코드입니다. 지원 언어: " + LanguageCode.entries.joinToString(", ") { it.code },
    ),

    // 인증
    INVALID_SOCIAL_TOKEN(401, "유효하지 않은 소셜 인증 토큰입니다"),
    UNSUPPORTED_PROVIDER(401, "지원하지 않는 소셜 로그인 제공자입니다"),
    INVALID_ACCESS_TOKEN(401, "유효하지 않은 인증 토큰입니다"),
    EXPIRED_ACCESS_TOKEN(401, "만료된 인증 토큰입니다"),
    INVALID_REFRESH_TOKEN(401, "유효하지 않은 갱신 토큰입니다"),
    EXPIRED_REFRESH_TOKEN(401, "만료된 갱신 토큰입니다"),
    SOCIAL_ACCOUNT_DELETE_FAILED(500, "소셜 계정 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요"),

    // 회원
    DUPLICATE_SOCIAL_IDENTITY(409, "이미 가입된 소셜 계정입니다"),
    ONBOARDING_ALREADY_COMPLETED(400, "이미 온보딩을 완료했습니다"),
    MEMBER_NOT_FOUND(400, "해당 회원을 찾을 수 없습니다"),

    // 온보딩·프로필
    INVALID_NICKNAME(400, "닉네임은 비어 있을 수 없습니다"),
    INVALID_AVOIDANCE_SUBSTANCE_CODE(400, "지원하지 않는 기피 성분 코드입니다"),
    INVALID_COUNTRY_CODE(400, "지원하지 않는 국가 코드입니다"),
    UNSUPPORTED_APP_LANGUAGE(400, "지원하지 않는 언어입니다"),

    // 음식
    FOOD_NOT_FOUND(400, "해당 음식 정보를 찾을 수 없습니다"),
    INVALID_CURSOR(400, "커서 형식이 올바르지 않습니다"),
    BLANK_SEARCH_KEYWORD(400, "검색어를 입력해 주세요"),
}
