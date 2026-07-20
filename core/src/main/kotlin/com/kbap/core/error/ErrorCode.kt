package com.kbap.core.error

// code = 클라이언트 분기용 안정 식별자(도메인 접두 + 번호). message 는 표시용 — 바꿔도 클라이언트가 안 깨진다.
enum class ErrorCode(
    val code: String,
    val status: Int,
    val message: String,
) {
    // 공통 — COMMON-001 은 폐기된 UNSUPPORTED_LANGUAGE 자리다(KB-201, 미지원 언어는 영어 폴백). 재사용 금지.
    INVALID_REQUEST("COMMON-002", 400, "잘못된 요청입니다"),
    INTERNAL_SERVER_ERROR("COMMON-003", 500, "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요"),

    // 인증
    INVALID_SOCIAL_TOKEN("AUTH-001", 401, "유효하지 않은 소셜 인증 토큰입니다"),
    UNSUPPORTED_PROVIDER("AUTH-002", 401, "지원하지 않는 소셜 로그인 제공자입니다"),
    INVALID_ACCESS_TOKEN("AUTH-003", 401, "유효하지 않은 액세스 토큰입니다. 다시 로그인해 주세요"),
    EXPIRED_ACCESS_TOKEN("AUTH-004", 401, "만료된 액세스 토큰입니다. 토큰을 갱신해 주세요"),
    INVALID_REFRESH_TOKEN("AUTH-005", 401, "유효하지 않은 리프레시 토큰입니다. 다시 로그인해 주세요"),
    EXPIRED_REFRESH_TOKEN("AUTH-006", 401, "만료된 리프레시 토큰입니다. 다시 로그인해 주세요"),
    SOCIAL_ACCOUNT_DELETE_FAILED("AUTH-007", 500, "소셜 계정 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요"),

    // 회원
    DUPLICATE_SOCIAL_IDENTITY("MEMBER-001", 409, "이미 가입된 소셜 계정입니다"),
    ONBOARDING_ALREADY_COMPLETED("MEMBER-002", 400, "이미 온보딩을 완료했습니다"),
    MEMBER_NOT_FOUND("MEMBER-003", 400, "해당 회원을 찾을 수 없습니다"),
    INVALID_NICKNAME("MEMBER-004", 400, "닉네임은 비어 있을 수 없습니다"),
    INVALID_AVOIDANCE_SUBSTANCE_CODE("MEMBER-005", 400, "지원하지 않는 기피 성분 코드입니다"),
    INVALID_COUNTRY_CODE("MEMBER-006", 400, "지원하지 않는 국가 코드입니다"),
    UNSUPPORTED_APP_LANGUAGE("MEMBER-007", 400, "지원하지 않는 언어입니다"),
    INVALID_PROFILE_IMAGE_URL("MEMBER-008", 400, "프로필 사진 경로 형식이 올바르지 않습니다. 도메인 없는 이미지 경로(objectKey)를 512자 이내로 보내주세요"),
    INVALID_SPICINESS_PREFERENCE("MEMBER-009", 400, "맵기 선호는 -1(미설정) 또는 0~10 사이여야 합니다"),

    // 음식
    FOOD_NOT_FOUND("FOOD-001", 400, "해당 음식 정보를 찾을 수 없습니다"),
    INVALID_CURSOR("FOOD-002", 400, "커서 형식이 올바르지 않습니다"),
    BLANK_SEARCH_KEYWORD("FOOD-003", 400, "검색어를 입력해 주세요"),

    // 업로드 이미지
    NOT_IMAGE_FILE("IMAGE-001", 400, "이미지 파일만 업로드할 수 있습니다"),
    UPLOAD_MISMATCH("IMAGE-002", 400, "업로드한 파일이 신고한 형식·크기와 일치하지 않습니다"),
    UPLOADED_OBJECT_NOT_FOUND("IMAGE-003", 400, "업로드된 파일을 찾을 수 없습니다"),

    // 스캔
    SCAN_IMAGE_NOT_VERIFIED("SCAN-001", 400, "검증되지 않았거나 접근할 수 없는 이미지입니다"),
    MENU_BOARD_RECOGNITION_FAILED("SCAN-002", 503, "메뉴판 인식에 실패했습니다. 잠시 후 다시 시도해 주세요"),

    // 이미지 업로드 URL 발급
    UNSUPPORTED_IMAGE_CONTENT_TYPE("UPLOAD-001", 400, "지원하지 않는 이미지 형식입니다"),
    UNSUPPORTED_UPLOAD_PURPOSE("UPLOAD-002", 400, "지원하지 않는 업로드 용도입니다"),
    IMAGE_TOO_LARGE("UPLOAD-003", 400, "허용된 이미지 크기를 초과했습니다"),
}
