package com.kbap.common.domain.ingredient.model

data class Avoidance(
    val chosen: Set<IngredientCode>,
) {
    val codes: Set<IngredientCode> = chosen + chosen.flatMap { it.impliedCodes }

    val codeNames: Set<String> = codes.map { it.name }.toSet()

    fun codeNamesCoveredBy(chosenCode: IngredientCode): Set<String> =
        (chosenCode.impliedCodes + chosenCode).map { it.name }.toSet()

    fun matchedBy(code: String): String? {
        if (chosen.any { it.name == code }) return code
        return chosen.sortedBy { it.ordinal }.firstOrNull { chosenCode -> chosenCode.impliedCodes.any { it.name == code } }?.name
    }

    companion object {
        val NONE = Avoidance(emptySet())
    }
}
