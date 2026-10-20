package com.kbap.api.processedfood

import java.text.Normalizer

data class ProcessedFoodLabelText(
    val rawText: String,
    val allergens: String?,
    val mayContain: String?,
    val rawMaterials: String,
    val hasLabelLine: Boolean,
) {
    companion object {
        private val WHITESPACE = Regex("[ \\t\\u00A0]+")
        private val MAY_CONTAIN = Regex("(같은|동일한)\\s*(제조\\s*)?(시설|공정)|혼입|함유될\\s*수|포함될\\s*수")
        private val DECLARATION_END = Regex("함유\\s*[.)。]?\\s*$")
        private val SENTENCE_BREAK = Regex("(?<=함유)\\s*[.,。]\\s*|(?<=[.。])\\s+|(?<=습니다|합니다|있음)\\s+|\\s+(?=원재료|알레르기|이\\s*제품은)")

        fun of(pages: List<String>): ProcessedFoodLabelText {
            val lines = pages.flatMap { Normalizer.normalize(it, Normalizer.Form.NFKC).lines() }
                .map { it.replace(WHITESPACE, " ").trim() }
                .filter { it.isNotEmpty() }
                .distinct()
            val sentences = lines.joinToString(" ").split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
            val (mayContain, rest) = sentences.partition { MAY_CONTAIN.containsMatchIn(it) }
            val (declaration, raw) = rest.partition { "알레르기" in it || DECLARATION_END.containsMatchIn(it) }
            return ProcessedFoodLabelText(
                rawText = lines.joinToString("\n"),
                allergens = declaration.joinToString("\n").ifEmpty { null },
                mayContain = mayContain.joinToString("\n").ifEmpty { null },
                rawMaterials = raw.joinToString("\n"),
                hasLabelLine = declaration.isNotEmpty() || raw.any { "원재료" in it },
            )
        }
    }
}
