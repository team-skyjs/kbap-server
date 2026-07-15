package com.kbap.core.error

import com.kbap.core.lang.LanguageCode

// code = 클라이언트 분기용 안정 식별자(도메인 접두 + 번호). message 는 표시용 — 바꿔도 클라이언트가 안 깨진다.
enum class ErrorCode(
    val code: String,
    val status: Int,
    val message: String,
) {
    // 공통
    UNSUPPORTED_LANGUAGE(
        "COMMON-001",
        400,
        "지원하지 않는 언어 코드입니다. 지원 언어: " + LanguageCode.entries.joinToString(", ") { it.code },
    ),
    INVALID_REQUEST("COMMON-002", 400, "잘못된 요청입니다"),

    // 인증
    INVALID_SOCIAL_TOKEN("AUTH-001", 401, "유효하지 않은 소셜 인증 토큰입니다"),
    UNSUPPORTED_PROVIDER("AUTH-002", 401, "지원하지 않는 소셜 로그인 제공자입니다"),
    INVALID_ACCESS_TOKEN("AUTH-003", 401, "유효하지 않은 인증 토큰입니다"),
    EXPIRED_ACCESS_TOKEN("AUTH-004", 401, "만료된 인증 토큰입니다"),
    INVALID_REFRESH_TOKEN("AUTH-005", 401, "유효하지 않은 갱신 토큰입니다"),
    EXPIRED_REFRESH_TOKEN("AUTH-006", 401, "만료된 갱신 토큰입니다"),
    SOCIAL_ACCOUNT_DELETE_FAILED("AUTH-007", 500, "소셜 계정 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요"),

    // 회원
    DUPLICATE_SOCIAL_IDENTITY("MEMBER-001", 409, "이미 가입된 소셜 계정입니다"),
    ONBOARDING_ALREADY_COMPLETED("MEMBER-002", 400, "이미 온보딩을 완료했습니다"),
    MEMBER_NOT_FOUND("MEMBER-003", 400, "해당 회원을 찾을 수 없습니다"),
    INVALID_NICKNAME("MEMBER-004", 400, "닉네임은 비어 있을 수 없습니다"),
    INVALID_AVOIDANCE_SUBSTANCE_CODE("MEMBER-005", 400, "지원하지 않는 기피 성분 코드입니다"),
    INVALID_COUNTRY_CODE("MEMBER-006", 400, "지원하지 않는 국가 코드입니다"),
    UNSUPPORTED_APP_LANGUAGE("MEMBER-007", 400, "지원하지 않는 언어입니다"),
    INVALID_PROFILE_IMAGE_URL("MEMBER-008", 400, "프로필 사진 URL 형식이 올바르지 않습니다"),
    INVALID_SPICINESS_PREFERENCE("MEMBER-009", 400, "맵기 선호는 0~10 사이여야 합니다"),

    // 음식
    FOOD_NOT_FOUND("FOOD-001", 400, "해당 음식 정보를 찾을 수 없습니다"),
    INVALID_CURSOR("FOOD-002", 400, "커서 형식이 올바르지 않습니다"),
    BLANK_SEARCH_KEYWORD("FOOD-003", 400, "검색어를 입력해 주세요"),
}
