package com.meogo.core.food

import com.meogo.core.kernel.error.ErrorCode

enum class FoodErrorCode(
    override val status: Int,
    override val message: String,
) : ErrorCode {
    NOT_FOUND(400, "해당 음식 정보 없음"),
    INVALID_CURSOR(400, "커서 형식이 올바르지 않습니다"),
}
