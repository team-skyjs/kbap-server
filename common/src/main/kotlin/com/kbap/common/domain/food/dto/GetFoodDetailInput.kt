package com.kbap.common.domain.food.dto

import com.kbap.common.core.lang.LanguageCode

data class GetFoodDetailInput(
    val foodId: Long,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
