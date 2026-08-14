package com.kbap.api.food

import com.kbap.common.domain.LanguageCode

data class BrowseFoodsInput(
    val cursor: Long?,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
