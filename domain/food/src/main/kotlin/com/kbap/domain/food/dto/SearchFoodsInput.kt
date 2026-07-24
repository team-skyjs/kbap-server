package com.kbap.domain.food.dto

import com.kbap.core.lang.LanguageCode

data class SearchFoodsInput(
    val keyword: String,
    val cursor: Long?,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
