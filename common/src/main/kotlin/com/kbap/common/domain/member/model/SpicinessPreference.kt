package com.kbap.common.domain.member.model

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode

enum class SpicinessPreference {
    SKIP,
    NONE,
    MILD,
    MEDIUM,
    HOT,
    EXTREME,
    ;

    companion object {
        fun from(raw: String): SpicinessPreference =
            entries.firstOrNull { it.name == raw }
                ?: throw BusinessException(ErrorCode.INVALID_SPICINESS_PREFERENCE)
    }
}
