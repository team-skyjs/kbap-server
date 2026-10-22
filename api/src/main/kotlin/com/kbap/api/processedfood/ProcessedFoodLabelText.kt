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
        private val MAY_CONTAIN = Regex("(같은|동일한)\\s*(제조\\s*)?(시설|공정)|(같은|동일한)\\s*(생산\\s*)?(라인|설비|장소)|혼입|함유\\s*가능|함유(될|할)\\s*수|포함될\\s*수|들어\\s*있을\\s*수|섞일\\s*수")
        private val FACILITY = Regex("(같은|동일한)\\s*(제조\\s*)?(시설|공정)|(같은|동일한)\\s*(생산\\s*)?(라인|설비|장소)|사용한\\s*제품|제품과")
        private val DECLARATION_END = Regex("함유\\s*[.)。]?\\s*$")
        private val SENTENCE_BREAK = Regex("(?<=함유)\\s*[.,。]\\s*|(?<=함유)(?![.,。]|\\s*(된|될|할|하는|되는|가능|량))\\s*|(?<=[^\\d][.。!?])(?!\\d)\\s*|(?<=습니다|합니다|있음)\\s+|\\s+(?=원재료|알레르기|이\\s*제품은)|\\s*(?=[※*])")

        private val LIST_CONTINUES = charArrayOf(',', '(', '·')

        private fun joinListContinuations(lines: List<String>): List<String> =
            lines.fold(mutableListOf()) { joined, line ->
                if (joined.lastOrNull()?.lastOrNull()?.let { it in LIST_CONTINUES } == true) joined[joined.lastIndex] += " $line" else joined += line
                joined
            }

        private fun partitionMayContain(sentences: List<String>): Pair<List<String>, List<String>> {
            val definite = mutableListOf<String>()
            val mayContain = mutableListOf<String>()
            sentences.forEach { sentence ->
                when {
                    !MAY_CONTAIN.containsMatchIn(sentence) -> definite += sentence
                    FACILITY.containsMatchIn(sentence) -> mayContain += sentence
                    else -> {
                        val (marked, plain) = sentence.split(',').map { it.trim() }.filter { it.isNotEmpty() }.partition { MAY_CONTAIN.containsMatchIn(it) }
                        mayContain += marked.joinToString(", ")
                        if (plain.isNotEmpty()) definite += plain.joinToString(", ")
                    }
                }
            }
            return definite to mayContain
        }

        fun splitMayContain(text: String?): Pair<String?, String?> {
            if (text.isNullOrBlank()) return text to null
            val sentences = Normalizer.normalize(text, Normalizer.Form.NFKC).split(SENTENCE_BREAK).map { it.trim() }.filter { it.isNotEmpty() }
            val (rest, mayContain) = partitionMayContain(sentences)
            if (mayContain.isEmpty()) return text to null
            return rest.joinToString("\n").ifEmpty { null } to mayContain.joinToString("\n")
        }

        fun of(pages: List<String>): ProcessedFoodLabelText {
            val seen = mutableSetOf<String>()
            val pageLines = pages.map { page ->
                Normalizer.normalize(page, Normalizer.Form.NFKC).lines()
                    .map { it.replace(WHITESPACE, " ").trim() }
                    .filter { it.isNotEmpty() && seen.add(it) }
            }
            val lines = pageLines.flatten()
            val sentences = pageLines.flatMap(::joinListContinuations).flatMap { it.split(SENTENCE_BREAK) }.map { it.trim() }.filter { it.isNotEmpty() }
            val (rest, mayContain) = partitionMayContain(sentences)
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
