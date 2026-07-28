package com.kbap.common.domain.food.dto

import com.kbap.common.core.lang.LanguageCode

data class BrowseFoodsInput(
    val cursor: Long?,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
