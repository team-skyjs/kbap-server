package com.kbap.common.domain.ingredient.label

import com.kbap.common.domain.ingredient.model.IngredientCode
import java.text.Normalizer

object IngredientLabelReader {
    private val NOT_PROVIDED = setOf("알수없음", "정보없음", "미표시", "모름")
    private val NOISE = Regex("""\d+(\.\d+)?\s*%|함유\s*가능|함유|(?<!불)포함""")
    private val SEPARATORS = Regex("""[^\p{L}\p{N}\p{Zs}%.]+|[\u1100-\u11FF\u3131-\u318Eー]+|(?<!\d)\.|\.(?!\d)""")
    private val SPACES = Regex("""\s+""")
    private val TRAILING_ETC = Regex("""\s+등$""")
    private val FLAVOR_MARKERS = listOf("맛", "향", "풍미", "향료")
    private val NEGATED = Regex("""\S+?\s*(무첨가|불포함|미사용|free)""", RegexOption.IGNORE_CASE)
    private val PARTICLES = setOf('을', '를', '이', '가', '은', '는', '도', '와', '과', '의')

    private val mandatory: List<IngredientLabelTerm> =
        load("mandatory-allergens.tsv").map { IngredientLabelTerm(it[0], codesOf(it[1]), IngredientLabelTermMode.EXACT) }
    private val terms: List<IngredientLabelTerm> =
        load("terms.tsv").map { IngredientLabelTerm(it[0], codesOf(it[1]), IngredientLabelTermMode.valueOf(it[2].uppercase())) }
    private val exact: Map<String, Set<IngredientCode>> =
        (mandatory + terms.filter { it.mode == IngredientLabelTermMode.EXACT }).groupBy({ it.term }, { it.codes }).mapValues { it.value.flatten().toSet() }
    private val blocks: List<IngredientLabelTerm> = terms.filter { it.mode == IngredientLabelTermMode.BLOCK }.sortedByDescending { it.term.length }
    private val containing: List<IngredientLabelTerm> = terms.filter { it.mode == IngredientLabelTermMode.CONTAINS }

    private val mandatoryTerms: Set<String> = mandatory.map { it.term }.toSet()

    fun mandatoryAllergenCodes(): Set<IngredientCode> = mandatory.flatMap { it.codes }.toSet()

    fun read(allergens: String?, rawMaterials: String?): IngredientLabelReading {
        val declared = allergens?.let(::normalize)?.takeIf { it.isNotBlank() && it.replace(SPACES, "") !in NOT_PROVIDED }
        val fromAllergens = declared?.let { scan(it, splitSpaces = true) }.orEmpty()
        val fromRaw = rawMaterials?.let { scan(normalize(it), splitSpaces = false) }.orEmpty()
        val detections = linkedMapOf<IngredientCode, IngredientLabelDetection>()
        fromAllergens.forEach { (token, codes) ->
            codes.forEach { detections.putIfAbsent(it, IngredientLabelDetection(it, IngredientLabelSource.ALLERGEN_DECLARATION, token)) }
        }
        fromRaw.forEach { (token, codes) ->
            codes.forEach { detections.putIfAbsent(it, IngredientLabelDetection(it, IngredientLabelSource.RAW_MATERIALS, token)) }
        }
        return IngredientLabelReading(
            detections = detections.values.toList(),
            unmappedTokens = (fromAllergens + fromRaw).filter { it.second.isEmpty() }.map { it.first }.distinct(),
            declaration = if (declared != null) AllergenDeclaration.LISTED else AllergenDeclaration.NOT_PROVIDED,
        )
    }

    private fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC)

    private fun scan(text: String, splitSpaces: Boolean): List<Pair<String, Set<IngredientCode>>> =
        text.replace(NOISE, ",")
            .split(SEPARATORS)
            .flatMap { if (splitSpaces) it.split(SPACES) else listOf(it) }
            .map { it.trim().replace(TRAILING_ETC, "") }
            .filter { token -> token.any { it.isLetter() } }
            .map { it to codesIn(it) }

    private fun codesIn(original: String): Set<IngredientCode> {
        val token = original.replace(NEGATED, " ").trim()
        if (token.isEmpty()) return emptySet()
        exactOf(token)?.let { return it }
        val codes = token.split(SPACES).filter { it.length < token.length }.flatMap { exactOf(it).orEmpty() }.toMutableSet()
        var rest = token
        blocks.filter { it.term in rest }.forEach {
            if (isIngredientMention(rest, it.term)) codes += it.codes
            rest = rest.replace(it.term, " ")
        }
        containing.filter { isIngredientMention(rest, it.term) }.forEach { codes += it.codes }
        return codes
    }

    private fun exactOf(word: String): Set<IngredientCode>? =
        exact[word] ?: word.takeIf { it.length > 1 && it.last() in PARTICLES }?.dropLast(1)?.takeIf { it in mandatoryTerms }?.let { exact[it] }

    private fun isIngredientMention(text: String, term: String): Boolean =
        Regex(Regex.escape(term)).findAll(text).any { match ->
            val followingWord = text.substring(match.range.last + 1).split(SPACES).first()
            followingWord.isEmpty() || FLAVOR_MARKERS.none { followingWord.endsWith(it) }
        }

    private fun load(name: String): List<List<String>> =
        requireNotNull(IngredientLabelReader::class.java.getResourceAsStream("/ingredient-label/$name")) { name }
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }

    private fun codesOf(column: String): Set<IngredientCode> =
        if (column == "-") emptySet() else column.split(',').map { IngredientCode.valueOf(it.trim()) }.toSet()
}

data class IngredientLabelTerm(
    val term: String,
    val codes: Set<IngredientCode>,
    val mode: IngredientLabelTermMode,
)

enum class IngredientLabelTermMode { EXACT, CONTAINS, BLOCK }
