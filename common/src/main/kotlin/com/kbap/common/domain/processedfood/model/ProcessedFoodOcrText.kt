package com.kbap.common.domain.processedfood.model

import java.text.Normalizer

object ProcessedFoodOcrText {
    private const val MAX_QUERIES = 8
    private val LINE_BREAK = Regex("""[\r\n]+""")
    private val SPACES = Regex("""\s+""")
    private val NOISE = Regex("""(?i)\bNEW\b|오리지널|\d+(\.\d+)?\s*(ml|kg|g|l|개입|개|봉|입|매)(?![a-z가-힣])""")
    private val FLAVOR_WORD = Regex("""\s+\S*맛$""")

    fun queriesOf(ocrText: String): List<String> {
        val lines = Normalizer.normalize(ocrText, Normalizer.Form.NFKC)
            .split(LINE_BREAK)
            .map { it.replace(NOISE, " ").replace(SPACES, " ").trim() }
            .filter { line -> line.count { it.isLetter() } >= 2 }
        if (lines.isEmpty()) return emptyList()
        val perLine = lines.flatMap { line -> listOf(line, line.replace(FLAVOR_WORD, "").trim()) }
        return (listOf(lines.joinToString(" ")) + perLine).filter { it.isNotEmpty() }.distinct().take(MAX_QUERIES)
    }
}
