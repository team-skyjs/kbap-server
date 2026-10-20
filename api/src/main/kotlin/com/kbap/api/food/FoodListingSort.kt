package com.kbap.api.food

import com.ibm.icu.text.Collator
import com.ibm.icu.text.CollationKey
import com.ibm.icu.text.RuleBasedCollator
import com.ibm.icu.util.ULocale
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.dto.FoodListingName

enum class FoodListingSort(val value: String) {
    DEFAULT("default"),
    NAME_ASC("name_asc"),
    NAME_DESC("name_desc"),
    ;

    fun order(names: List<FoodListingName>, lang: LanguageCode): List<Long> {
        if (this == DEFAULT) return names.map { it.id }.sortedDescending()
        val locale = when (lang) {
            LanguageCode.ZH_HANS -> "zh-Hans-u-co-pinyin"
            LanguageCode.ZH_HANT -> "zh-Hant-u-co-stroke"
            else -> lang.code
        }
        val collator = (Collator.getInstance(ULocale.forLanguageTag(locale)) as RuleBasedCollator).apply {
            strength = Collator.SECONDARY
            decomposition = Collator.CANONICAL_DECOMPOSITION
            numericCollation = true
            isAlternateHandlingShifted = false
        }
        val keys = names.map { it.id to collator.getCollationKey(it.name(lang)) }
        val ascending = keys.sortedWith(compareBy<Pair<Long, CollationKey>> { it.second }.thenBy { it.first }).map { it.first }
        return if (this == NAME_DESC) ascending.reversed() else ascending
    }

    companion object {
        const val COMPARATOR_VERSION = "icu77.1-cldr47-secondary-numeric-v1"

        fun parse(raw: String?): FoodListingSort =
            if (raw == null) DEFAULT else entries.firstOrNull { it.value == raw }
                ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
    }
}
