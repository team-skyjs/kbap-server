package com.kbap.api.food

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode

data class FoodSearchCursor(val grade: Int, val scans: Long, val id: Long) {
    fun encode(): String = "$grade:$scans:$id"

    companion object {
        fun parse(raw: String?): FoodSearchCursor? {
            if (raw.isNullOrBlank()) return null
            if (raw.all { it.isDigit() }) return null
            val parts = raw.split(':')
            val grade = parts.getOrNull(0)?.toIntOrNull()
            val scans = parts.getOrNull(1)?.toLongOrNull()
            val id = parts.getOrNull(2)?.toLongOrNull()
            if (parts.size != 3 || grade == null || scans == null || id == null || grade < 0 || scans < 0 || id < 0) {
                throw BusinessException(ErrorCode.INVALID_CURSOR)
            }
            return FoodSearchCursor(grade, scans, id)
        }
    }
}
