package com.kbap.domain.food.dto

import com.kbap.core.lang.LanguageCode

data class GetFoodDetailInput(
    val foodId: Long,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
