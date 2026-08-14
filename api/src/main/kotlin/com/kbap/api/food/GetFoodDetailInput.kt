package com.kbap.api.food

import com.kbap.common.domain.LanguageCode

data class GetFoodDetailInput(
    val foodId: Long,
    val lang: LanguageCode,
    val memberId: Long? = null,
)
