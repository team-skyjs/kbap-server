package com.kbap.api.processedfood

import java.text.Normalizer

data class ProcessedFoodLabelText(
    val rawText: String,
    val allergens: String?,
    val mayContain: String?,
    val rawMaterials: String,
) {
    companion object {
        private val WHITESPACE = Regex("[ \\t\\u00A0]+")
        private val MAY_CONTAIN = Regex("(같은|동일한)\\s*(제조\\s*)?(시설|공정)|혼입|함유될\\s*수|포함될\\s*수")
        private val DECLARATION_END = Regex("함유\\s*[.)。]?\\s*$")

        fun of(pages: List<String>): ProcessedFoodLabelText {
            val lines = pages.flatMap { Normalizer.normalize(it, Normalizer.Form.NFKC).lines() }
                .map { it.replace(WHITESPACE, " ").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val (mayContain, rest) = lines.partition { MAY_CONTAIN.containsMatchIn(it) }
            val (declaration, raw) = rest.partition { "알레르기" in it || DECLARATION_END.containsMatchIn(it) }
            return ProcessedFoodLabelText(
                rawText = lines.joinToString("\n"),
                allergens = declaration.joinToString("\n").ifEmpty { null },
                mayContain = mayContain.joinToString("\n").ifEmpty { null },
                rawMaterials = raw.joinToString("\n"),
            )
        }
    }
}
