package com.kbap.api.food

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode

data class SearchFoodsInput(
    val keyword: String,
    val cursor: Long?,
    val lang: LanguageCode,
    val memberId: Long? = null,
    val scope: FoodSearchScope = FoodSearchScope.ALL,
)

enum class FoodSearchScope {
    ALL,
    SCANNED,
    ;

    companion object {
        fun from(raw: String?): FoodSearchScope {
            if (raw.isNullOrBlank()) return ALL
            return entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
                ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
    }
}
