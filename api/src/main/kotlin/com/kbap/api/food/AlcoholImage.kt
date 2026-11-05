package com.kbap.api.food

import com.kbap.common.domain.food.model.Food
import com.kbap.common.port.llm.MenuNameKind
import com.kbap.common.util.ImageUrls
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class AlcoholImage(
    @Value("\${kbap.food.alcohol-image-ref:}") ref: String,
    @Value("\${kbap.storage.public-base-url:}") imagePublicBaseUrl: String,
) {
    private val url: String? = ref.trim().takeIf { it.isNotEmpty() }?.let { ImageUrls.resolve(imagePublicBaseUrl, it) }

    fun urlFor(food: Food): String? = url?.takeIf { food.isAlcoholicDrink() }

    fun urlForEstimate(kind: String, estimatedCodes: List<String>?): String? =
        url?.takeIf { kind == MenuNameKind.DRINK.name && estimatedCodes.orEmpty().contains(Food.ALCOHOL_CODE) }
}
