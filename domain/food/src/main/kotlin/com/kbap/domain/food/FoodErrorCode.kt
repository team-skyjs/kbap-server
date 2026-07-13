package com.kbap.domain.food

import com.kbap.core.error.ErrorCode

enum class FoodErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    NOT_FOUND(400, "해당 음식 정보를 찾을 수 없습니다"),
    INVALID_CURSOR(400, "커서 형식이 올바르지 않습니다"),
    BLANK_SEARCH_KEYWORD(400, "검색어를 입력해 주세요"),
}
