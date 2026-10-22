package com.kbap.api.processedfood

import com.kbap.common.domain.ingredient.label.AllergenDeclaration
import com.kbap.common.domain.ingredient.label.IngredientLabelDetection
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.ingredient.label.IngredientLabelReading
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.swagger.v3.oas.annotations.media.Schema
import java.text.Normalizer

@Schema(description = "가공식품 내 판정 — AVOID(회피 재료가 원재료·알레르기 표시에 있음) · CAUTION(혼입 가능 표기에만 있음) · SAFE · SAFE_WITH_NOTE(회피 재료는 없지만 내용 불명 원재료가 있음)")
enum class ProcessedFoodVerdict { AVOID, CAUTION, SAFE, SAFE_WITH_NOTE }

data class ProcessedFoodVerdictMatch(
    @field:Schema(description = "회원이 고른 회피 재료")
    val code: IngredientCode,
    @field:Schema(description = "근거 원문 토큰")
    val evidence: List<String>,
)

data class ProcessedFoodJudgement(
    val reading: IngredientLabelReading,
    val verdict: ProcessedFoodVerdict?,
    val avoidMatches: List<ProcessedFoodVerdictMatch>,
    val mayContainMatches: List<ProcessedFoodVerdictMatch>,
    val unclearIngredients: List<String>,
    val majorAllergens: List<IngredientCode>,
)

object ProcessedFoodJudge {
    private val unclearTerms: Map<String, List<String>> =
        requireNotNull(ProcessedFoodJudge::class.java.getResourceAsStream("/processed-food/unclear-ingredients.tsv")) { "unclear-ingredients.tsv" }
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .groupBy({ it[1].trim() }, { it[0].trim() })
    private val containsTerms = unclearTerms[CONTAINS].orEmpty()
    private val standaloneTerms = unclearTerms[STANDALONE].orEmpty()
    private val wholeTerms = unclearTerms[WHOLE].orEmpty().toSet()
    private val PERCENT = Regex("""\d+(\.\d+)?\s*%""")
    private val TOKEN_BREAK = Regex("""[,()\[\]{}/·:]+""")
    private val ITEM_BREAK = Regex("""[,/·]+""")
    private val SUB_INGREDIENTS = Regex("""[(\[{]""")
    private val majorParents: Map<IngredientCode, Set<IngredientCode>> =
        IngredientLabelReader.mandatoryAllergenCodes().associateWith { major -> setOf(major) + (major.impliedCodes - IngredientCode.GELATIN) }
    private val majorFamily: Set<IngredientCode> = majorParents.values.flatten().toSet()
    private const val CONTAINS = "contains"
    private const val STANDALONE = "standalone"
    private const val WHOLE = "whole"

    fun judge(allergens: String?, rawMaterials: String?, avoidance: Avoidance?): ProcessedFoodJudgement {
        val (declared, declaredMayContain) = ProcessedFoodLabelText.splitMayContain(allergens)
        val (raw, rawMayContain) = ProcessedFoodLabelText.splitMayContain(rawMaterials)
        val mayContainText = listOfNotNull(declaredMayContain, rawMayContain).joinToString("\n").ifEmpty { null }
        val mayContain = mayContainText?.let { IngredientLabelReader.read(null, it).detections }.orEmpty()
        return judge(IngredientLabelReader.read(declared, raw), mayContain, raw, avoidance)
    }

    fun judge(contained: IngredientLabelReading, mayContain: List<IngredientLabelDetection>, rawMaterials: String?, avoidance: Avoidance?): ProcessedFoodJudgement {
        val unclear = unclearIn(rawMaterials)
        val rawUnknown = IngredientLabelReader.isRawNotProvided(rawMaterials)
        val detected = contained.detections.map { it.code }.toSet()
        val majorAllergens = majorParents.filterValues { family -> family.any { it in detected } }.keys.sortedBy { it.ordinal }
        val readable = contained.detections.isNotEmpty() || mayContain.isNotEmpty() || !rawUnknown || contained.declaration == AllergenDeclaration.LISTED
        if (avoidance == null || avoidance.chosen.isEmpty() || !readable) {
            return ProcessedFoodJudgement(contained, null, emptyList(), emptyList(), unclear, majorAllergens)
        }
        val avoidMatches = matchesOf(avoidance, contained.detections)
        val matched = avoidMatches.map { it.code }.toSet()
        val mayContainMatches = matchesOf(avoidance, mayContain).filterNot { it.code in matched }
        val verdict = when {
            avoidMatches.isNotEmpty() -> ProcessedFoodVerdict.AVOID
            mayContainMatches.isNotEmpty() -> ProcessedFoodVerdict.CAUTION
            rawUnknown && !avoidance.codes.all { it in majorFamily } -> null
            unclear.isNotEmpty() -> ProcessedFoodVerdict.SAFE_WITH_NOTE
            else -> ProcessedFoodVerdict.SAFE
        }
        return ProcessedFoodJudgement(contained, verdict, avoidMatches, mayContainMatches, unclear, majorAllergens)
    }

    private fun matchesOf(avoidance: Avoidance, detections: List<IngredientLabelDetection>): List<ProcessedFoodVerdictMatch> =
        avoidance.chosen.sortedBy { it.ordinal }.mapNotNull { chosen ->
            val covered = avoidance.codeNamesCoveredBy(chosen)
            detections.filter { it.code.name in covered }.map { it.evidence }.distinct().takeIf { it.isNotEmpty() }?.let { ProcessedFoodVerdictMatch(chosen, it) }
        }

    private fun unclearIn(rawMaterials: String?): List<String> {
        val text = rawMaterials?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }.orEmpty()
        val categories = text.split(TOKEN_BREAK).map { it.trim() }.filter { token -> token.isNotEmpty() && containsTerms.any { it in token } }
        val bareSeasonings = text.split(ITEM_BREAK).map { it.trim() }
            .filter { item -> item.isNotEmpty() && !SUB_INGREDIENTS.containsMatchIn(item) && standaloneTerms.any { it in item } }
            .map { it.trimEnd(')', ']', '}').trim() }
        val bareWords = text.split(ITEM_BREAK).map { it.replace(PERCENT, "").trim() }.filter { it in wholeTerms }
        return text.split(ITEM_BREAK).flatMap { item -> (categories + bareSeasonings + bareWords).filter { it in item } }.distinct()
    }
}
