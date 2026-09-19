package com.kbap.api.food

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.RiskLevel

data class BrowseFoodsInput(
    val cursor: Long?,
    val lang: LanguageCode,
    val memberId: Long? = null,
    val risks: Set<RiskLevel>? = null,
)
