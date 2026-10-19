package com.kbap.api.food

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode

object SelectedFoodIds {
    fun parse(values: List<String>?): List<Long> {
        if (values?.size != 1) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val tokens = values.single().split(",")
        if (tokens.size !in 1..10) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val ids = tokens.map { value ->
            val token = value.trim()
            if (token.isEmpty() || token.any { it !in '0'..'9' }) {
                throw BusinessException(ErrorCode.INVALID_REQUEST)
            }
            token.toLongOrNull()?.takeIf { it > 0 }
                ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        if (ids.distinct().size != ids.size) throw BusinessException(ErrorCode.INVALID_REQUEST)
        return ids
    }
}
