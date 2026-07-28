package com.kbap.common.domain.food.dto

import com.kbap.common.domain.LanguageCode

data class SearchFoodsInput(
    val keyword: String,
    val cursor: Long?,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
