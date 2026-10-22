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
    val verdict: ProcessedFoodVerdict?,
    val avoidMatches: List<ProcessedFoodVerdictMatch>,
    val mayContainMatches: List<ProcessedFoodVerdictMatch>,
    val unclearIngredients: List<String>,
    val majorAllergens: List<IngredientCode>,
)

object ProcessedFoodJudge {
    private val UNCLEAR_TERMS: List<String> =
        requireNotNull(ProcessedFoodJudge::class.java.getResourceAsStream("/processed-food/unclear-ingredients.tsv")) { "unclear-ingredients.tsv" }
            .bufferedReader(Charsets.UTF_8)
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
    private val TOKEN_BREAK = Regex("""[,()\[\]{}/·:]+""")
    private val majorCodes = IngredientLabelReader.mandatoryAllergenCodes()

    fun judge(allergens: String?, rawMaterials: String?, avoidance: Avoidance?): ProcessedFoodJudgement {
        val (declared, declaredMayContain) = ProcessedFoodLabelText.splitMayContain(allergens)
        val (raw, rawMayContain) = ProcessedFoodLabelText.splitMayContain(rawMaterials)
        val mayContainText = listOfNotNull(declaredMayContain, rawMayContain).joinToString("\n").ifEmpty { null }
        val mayContain = mayContainText?.let { IngredientLabelReader.read(null, it).detections }.orEmpty()
        return judge(IngredientLabelReader.read(declared, raw), mayContain, raw, avoidance)
    }

    fun judge(contained: IngredientLabelReading, mayContain: List<IngredientLabelDetection>, rawMaterials: String?, avoidance: Avoidance?): ProcessedFoodJudgement {
        val unclear = unclearIn(rawMaterials)
        val majorAllergens = contained.detections.map { it.code }.filter { it in majorCodes }.distinct().sortedBy { it.ordinal }
        val readable = contained.detections.isNotEmpty() || !rawMaterials.isNullOrBlank() || contained.declaration == AllergenDeclaration.LISTED
        if (avoidance == null || avoidance.chosen.isEmpty() || !readable) {
            return ProcessedFoodJudgement(null, emptyList(), emptyList(), unclear, majorAllergens)
        }
        val avoidMatches = matchesOf(avoidance, contained.detections)
        val matched = avoidMatches.map { it.code }.toSet()
        val mayContainMatches = matchesOf(avoidance, mayContain).filterNot { it.code in matched }
        val verdict = when {
            avoidMatches.isNotEmpty() -> ProcessedFoodVerdict.AVOID
            mayContainMatches.isNotEmpty() -> ProcessedFoodVerdict.CAUTION
            unclear.isNotEmpty() -> ProcessedFoodVerdict.SAFE_WITH_NOTE
            else -> ProcessedFoodVerdict.SAFE
        }
        return ProcessedFoodJudgement(verdict, avoidMatches, mayContainMatches, unclear, majorAllergens)
    }

    private fun matchesOf(avoidance: Avoidance, detections: List<IngredientLabelDetection>): List<ProcessedFoodVerdictMatch> =
        avoidance.chosen.sortedBy { it.ordinal }.mapNotNull { chosen ->
            val covered = avoidance.codeNamesCoveredBy(chosen)
            detections.filter { it.code.name in covered }.map { it.evidence }.distinct().takeIf { it.isNotEmpty() }?.let { ProcessedFoodVerdictMatch(chosen, it) }
        }

    private fun unclearIn(rawMaterials: String?): List<String> =
        rawMaterials?.let { Normalizer.normalize(it, Normalizer.Form.NFKC) }.orEmpty()
            .split(TOKEN_BREAK)
            .map { it.trim() }
            .filter { token -> token.isNotEmpty() && UNCLEAR_TERMS.any { it in token } }
            .distinct()
}
