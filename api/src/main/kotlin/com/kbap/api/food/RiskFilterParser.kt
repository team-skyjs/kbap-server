package com.kbap.api.food

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.model.RiskLevel

object RiskFilterParser {
    fun parse(raw: String?): Set<RiskLevel>? {
        if (raw.isNullOrBlank()) return null
        val risks = raw.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { token ->
                RiskLevel.entries.firstOrNull { it.name == token.uppercase() }
                    ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
            }
            .toSet()
        return risks.ifEmpty { null }
    }
}
