package com.kbap.api.food

import com.kbap.common.domain.LanguageCode

data class SearchFoodsInput(
    val keyword: String,
    val cursor: Long?,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
