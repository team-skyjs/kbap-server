package com.kbap.api.processedfood

import com.kbap.common.domain.processedfood.model.ProcessedFoodSnapshot

object ProcessedFoodGrouping {
    fun keyOf(normalizedName: String, displayMaker: String?): String {
        val maker = makerKeyOf(displayMaker)
        return normalizedName.removePrefix(maker).ifEmpty { normalizedName } + "|" + maker
    }

    fun makerKeyOf(displayMaker: String?): String = displayMaker?.let(ProcessedFoodSnapshot::normalizeName).orEmpty()
}
