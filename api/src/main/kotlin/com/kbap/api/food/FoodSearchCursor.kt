package com.kbap.api.food

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode

data class FoodSearchCursor(val grade: Int, val scans: Long, val id: Long) {
    fun encode(): String = "$grade:$scans:$id"

    companion object {
        private val FORMAT = Regex("^([0-9]+):([0-9]+):([0-9]+)$")

        fun parse(raw: String?): FoodSearchCursor? {
            if (raw.isNullOrBlank()) return null
            val match = FORMAT.matchEntire(raw) ?: throw BusinessException(ErrorCode.INVALID_CURSOR)
            val (grade, scans, id) = match.destructured
            val cursor = FoodSearchCursor(
                grade = grade.toIntOrNull() ?: throw BusinessException(ErrorCode.INVALID_CURSOR),
                scans = scans.toLongOrNull() ?: throw BusinessException(ErrorCode.INVALID_CURSOR),
                id = id.toLongOrNull() ?: throw BusinessException(ErrorCode.INVALID_CURSOR),
            )
            if (cursor.grade > FoodSearchRanker.CONTAINS) throw BusinessException(ErrorCode.INVALID_CURSOR)
            return cursor
        }
    }
}
