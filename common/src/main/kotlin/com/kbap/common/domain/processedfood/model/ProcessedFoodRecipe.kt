package com.kbap.common.domain.processedfood.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class ProcessedFoodRecipe(
    val source: String = SOURCE_LABEL,
    val methods: List<RecipeMethod> = emptyList(),
) {
    fun toJson(): String = mapper.writeValueAsString(this)

    companion object {
        const val SOURCE_LABEL = "LABEL"
        private val mapper = jacksonObjectMapper()

        fun fromJsonOrNull(json: String?): ProcessedFoodRecipe? = json?.takeIf { it.isNotBlank() }?.let { runCatching { mapper.readValue<ProcessedFoodRecipe>(it) }.getOrNull() }
    }
}

data class RecipeMethod(
    val type: RecipeMethodType = RecipeMethodType.MICROWAVE,
    val water: RecipeWater? = null,
    val power: List<RecipePower> = emptyList(),
    val waitSeconds: Int? = null,
    val drainKeepMl: Int? = null,
    val steps: List<RecipeStep> = emptyList(),
    val cautions: List<String> = emptyList(),
)

enum class RecipeMethodType { MICROWAVE, BOILING_WATER }

data class RecipeWater(val ml: Int? = null, val line: RecipeWaterLine? = null)

enum class RecipeWaterLine { INNER, OUTER, COVER_NOODLES }

data class RecipePower(val watt: Int = 0, val seconds: Int = 0)

data class RecipeStep(val ko: String = "")
