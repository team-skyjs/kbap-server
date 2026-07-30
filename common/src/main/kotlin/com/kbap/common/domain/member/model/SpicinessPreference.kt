package com.kbap.common.domain.member.model

import com.fasterxml.jackson.annotation.JsonCreator
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
        // Jackson 기본 정수→ordinal 매핑 차단 — 이관 전 정수 데이터는 조용히 읽히면 안 된다
        @JsonCreator
        @JvmStatic
        fun from(raw: String): SpicinessPreference =
            entries.firstOrNull { it.name == raw }
                ?: throw BusinessException(ErrorCode.INVALID_SPICINESS_PREFERENCE)
    }
}
