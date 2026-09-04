package com.kbap.common.core.error

// code = 클라이언트 분기용 안정 식별자(도메인 접두 + 번호). message 는 표시용 — 바꿔도 클라이언트가 안 깨진다.
enum class ErrorCode(
    val code: String,
    val status: Int,
    val message: String,
) {
    // 공통 — COMMON-001 은 폐기된 UNSUPPORTED_LANGUAGE 자리다(KB-201, 미지원 언어는 영어 폴백). 재사용 금지.
    INVALID_REQUEST("COMMON-002", 400, "잘못된 요청입니다"),
    INTERNAL_SERVER_ERROR("COMMON-003", 500, "서버 오류가 발생했습니다. 잠시 후 다시 시도해 주세요"),
    CONFLICT("COMMON-004", 409, "다른 요청과 겹쳐 처리하지 못했습니다. 잠시 후 다시 시도해 주세요"),

    INVALID_SOCIAL_TOKEN("AUTH-001", 401, "유효하지 않은 소셜 인증 토큰입니다"),
    UNSUPPORTED_PROVIDER("AUTH-002", 401, "지원하지 않는 소셜 로그인 제공자입니다"),
    INVALID_ACCESS_TOKEN("AUTH-003", 401, "유효하지 않은 액세스 토큰입니다. 다시 로그인해 주세요"),
    EXPIRED_ACCESS_TOKEN("AUTH-004", 401, "만료된 액세스 토큰입니다. 토큰을 갱신해 주세요"),
    INVALID_REFRESH_TOKEN("AUTH-005", 401, "유효하지 않은 리프레시 토큰입니다. 다시 로그인해 주세요"),
    EXPIRED_REFRESH_TOKEN("AUTH-006", 401, "만료된 리프레시 토큰입니다. 다시 로그인해 주세요"),
    SOCIAL_ACCOUNT_DELETE_FAILED("AUTH-007", 500, "소셜 계정 삭제에 실패했습니다. 잠시 후 다시 시도해 주세요"),
    ADMIN_FORBIDDEN("AUTH-008", 403, "관리자만 사용할 수 있는 API 입니다"),
    ADMIN_LOGIN_FAILED("AUTH-009", 401, "아이디 또는 비밀번호가 올바르지 않습니다"),

    DUPLICATE_SOCIAL_IDENTITY("MEMBER-001", 409, "이미 가입된 소셜 계정입니다"),
    ONBOARDING_ALREADY_COMPLETED("MEMBER-002", 400, "이미 온보딩을 완료했습니다"),
    MEMBER_NOT_FOUND("MEMBER-003", 400, "해당 회원을 찾을 수 없습니다"),
    INVALID_NICKNAME("MEMBER-004", 400, "닉네임은 비어 있을 수 없습니다"),
    INVALID_AVOIDANCE_SUBSTANCE_CODE("MEMBER-005", 400, "지원하지 않는 기피 성분 코드입니다"),
    INVALID_COUNTRY_CODE("MEMBER-006", 400, "지원하지 않는 국가 코드입니다"),
    INVALID_PROFILE_IMAGE_URL("MEMBER-008", 400, "프로필 사진 경로 형식이 올바르지 않습니다. 도메인 없는 이미지 경로(objectKey)를 512자 이내로 보내주세요"),
    INVALID_SPICINESS_PREFERENCE("MEMBER-009", 400, "맵기 선호는 SKIP·NONE·MILD·MEDIUM·HOT·EXTREME 중 하나여야 합니다"),
    INVALID_CURRENCY_CODE("MEMBER-010", 400, "지원하지 않는 통화 코드입니다"),
    INVALID_DIET_CATEGORY("MEMBER-011", 400, "지원하지 않는 diet 카테고리입니다"),

    FOOD_NOT_FOUND("FOOD-001", 400, "해당 음식 정보를 찾을 수 없습니다"),
    INVALID_CURSOR("FOOD-002", 400, "커서 형식이 올바르지 않습니다"),
    BLANK_SEARCH_KEYWORD("FOOD-003", 400, "검색어를 입력해 주세요"),
    FOOD_CONTENT_REQUEST_ALREADY_COMPLETED("FOOD-004", 409, "이미 처리된 음식 콘텐츠 수집 요청입니다"),
    DUPLICATE_FOOD_NAME("FOOD-005", 409, "이미 같은 이름의 음식이 있습니다"),
    FOOD_VERSION_CONFLICT("FOOD-006", 409, "다른 관리자가 먼저 수정했습니다. 최신 내용을 다시 불러와 수정해 주세요"),
    VECTOR_OUTBOX_NOT_FOUND("FOOD-007", 400, "해당 벡터 동기화 작업을 찾을 수 없습니다"),
    FOOD_NOT_REVIEWABLE("FOOD-008", 400, "검수 대상(PENDING_REVIEW)이 아닙니다"),
    FOOD_RESTORE_NAME_CONFLICT("FOOD-009", 409, "같은 이름의 음식이 새로 등록되어 복원할 수 없습니다. 새 음식의 이름을 바꾼 뒤 다시 시도해 주세요"),
    FOOD_READY_TRANSITION_FORBIDDEN("FOOD-010", 400, "READY 전이는 검수 승인 API 로만 가능합니다"),

    NOT_IMAGE_FILE("IMAGE-001", 400, "이미지 파일만 업로드할 수 있습니다"),
    UPLOAD_MISMATCH("IMAGE-002", 400, "업로드한 파일이 신고한 형식·크기와 일치하지 않습니다"),
    UPLOADED_OBJECT_NOT_FOUND("IMAGE-003", 400, "업로드된 파일을 찾을 수 없습니다"),

    SCAN_IMAGE_NOT_VERIFIED("SCAN-001", 400, "검증되지 않았거나 접근할 수 없는 이미지입니다"),
    MENU_BOARD_RECOGNITION_FAILED("SCAN-002", 503, "메뉴판 인식에 실패했습니다. 잠시 후 다시 시도해 주세요"),
    MENU_BOARD_NOT_DETECTED("SCAN-003", 400, "메뉴판을 인식하지 못했습니다. 메뉴판이 잘 보이게 다시 찍어주세요"),
    SCAN_LIMIT_EXCEEDED("SCAN-004", 403, "무료 스캔 횟수를 모두 사용했습니다. 리뷰를 작성하면 무제한으로 이용할 수 있어요"),
    DUPLICATE_SCAN_REQUEST("SCAN-005", 409, "이미 처리 중인 스캔 요청입니다"),
    SCAN_VISION_UNAVAILABLE("SCAN-006", 503, "스캔을 완료하지 못했어요. 횟수 차감 없이 다시 시도할 수 있어요."),
    INVALID_SCAN_TICKET("SCAN-007", 400, "유효하지 않은 스캔 티켓이에요. 처음부터 다시 시도해 주세요"),
    SCAN_RATE_LIMITED("SCAN-008", 503, "일시적으로 요청이 많습니다. 잠시 후 다시 시도해 주세요"),

    REVIEW_NOT_FOUND("REVIEW-001", 400, "해당 리뷰를 찾을 수 없습니다"),
    REVIEW_FORBIDDEN("REVIEW-002", 403, "본인이 작성한 리뷰만 수정·삭제할 수 있습니다"),
    REVIEW_IMAGE_NOT_VERIFIED("REVIEW-003", 400, "검증되지 않았거나 본인이 업로드하지 않은 이미지입니다"),
    REVIEW_NOT_ELIGIBLE("REVIEW-004", 403, "스캔 이력이 있는 음식에만 리뷰를 작성할 수 있습니다"),

    PLACE_SEARCH_FAILED("PLACE-001", 502, "식당 검색에 실패했습니다. 잠시 후 다시 시도해 주세요"),

    COMMUNITY_POSTING_NOT_FOUND("COMMUNITY-001", 400, "해당 게시글을 찾을 수 없습니다"),
    COMMUNITY_POSTING_FORBIDDEN("COMMUNITY-002", 403, "본인이 작성한 게시글만 수정·삭제할 수 있습니다"),
    COMMUNITY_IMAGE_NOT_VERIFIED("COMMUNITY-003", 400, "검증되지 않았거나 본인이 업로드하지 않은 이미지입니다"),
    COMMUNITY_FOOD_TAG_INVALID("COMMUNITY-004", 400, "태그할 수 없는 음식입니다"),
    COMMUNITY_LOGIN_REQUIRED("COMMUNITY-005", 401, "로그인이 필요합니다"),
    COMMUNITY_COMMENT_NOT_FOUND("COMMUNITY-006", 400, "해당 댓글을 찾을 수 없습니다"),
    COMMUNITY_COMMENT_FORBIDDEN("COMMUNITY-007", 403, "본인이 작성한 댓글만 수정·삭제할 수 있습니다"),

    SELF_BLOCK_FORBIDDEN("BLOCK-001", 400, "자기 자신은 차단할 수 없습니다"),
    BLOCK_TARGET_NOT_FOUND("BLOCK-002", 404, "차단할 회원을 찾을 수 없습니다"),

    REPORT_SELF_TARGET("REPORT-001", 400, "본인이 작성한 콘텐츠는 신고할 수 없습니다"),
    REPORT_DUPLICATED("REPORT-002", 409, "이미 신고한 콘텐츠입니다"),
    REPORT_TARGET_NOT_FOUND("REPORT-003", 404, "신고 대상을 찾을 수 없습니다"),

    ORDER_NOT_FOUND("ORDER-002", 404, "해당 주문 내역을 찾을 수 없습니다"),
    ORDER_ALREADY_PLACED("ORDER-003", 409, "이 메뉴판으로는 이미 주문했습니다"),

    UNSUPPORTED_IMAGE_CONTENT_TYPE("UPLOAD-001", 400, "지원하지 않는 이미지 형식입니다"),
    UNSUPPORTED_UPLOAD_PURPOSE("UPLOAD-002", 400, "지원하지 않는 업로드 용도입니다"),
    IMAGE_TOO_LARGE("UPLOAD-003", 400, "허용된 이미지 크기를 초과했습니다"),
}
