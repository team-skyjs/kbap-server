package com.kbap.domain.food.dto

import com.kbap.core.lang.LanguageCode

data class BrowseFoodsInput(
    val cursor: Long?,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
