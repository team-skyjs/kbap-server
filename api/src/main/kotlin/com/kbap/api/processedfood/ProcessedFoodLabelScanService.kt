package com.kbap.api.processedfood

import com.kbap.common.domain.LanguageCode
import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.ingredient.label.AllergenDeclaration
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.ingredient.label.IngredientLabelReading
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.domain.scan.ScanUsageJpaRepository
import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanUsage
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

@Service
class ProcessedFoodLabelScanService(
    private val operations: ScanOperationJpaRepository,
    private val usages: ScanUsageJpaRepository,
    private val members: MemberJpaRepository,
    private val memberService: MemberService,
) {
    @Transactional
    fun scanLabel(memberId: Long, analysisId: String?, clientRequestId: UUID?, pages: List<String>, language: String): ProcessedFoodLabelScanResponse {
        if (analysisId == null) members.findByIdForUpdate(memberId)
        memberService.getMember(memberId)
        val text = ProcessedFoodLabelText.of(pages)
        val headingSeen = listOfNotNull(text.allergens, text.rawMaterials).any { HEADING_PHRASE.containsMatchIn(it) }
        val reading = IngredientLabelReader.read(text.allergens?.let(::withoutHeadings), withoutHeadings(text.rawMaterials).ifBlank { null })
        val now = LocalDateTime.now()
        val manifestHash = MessageDigest.getInstance("SHA-256").digest(pages.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))
        if (analysisId == null) {
            val key = clientRequestId ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
            operations.findForUpdate(memberId, NAMESPACE, ProcessedFoodNameScanService.bytesOf(key))?.let { replayed ->
                if (!now.isBefore(replayed.resultExpiresAt)) throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
                if (!replayed.manifestHash.contentEquals(manifestHash)) throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
                return responseOf(memberId, key, text, reading, language)
            }
        }
        val hangulTokens = unmappedOf(reading).count { token -> token.count { it in '가'..'힣' } >= 2 }
        val mayContainRead = text.mayContain?.let { IngredientLabelReader.read(null, withoutHeadings(it)).detections }.orEmpty()
        val readable = mayContainRead.isNotEmpty() ||
            text.hasLabelLine && (reading.detections.isNotEmpty() || hangulTokens >= if (headingSeen) 1 else MIN_HANGUL_TOKENS)
        val operation = analysisId?.let { continued(memberId, it, now) }
        if (!readable) throw BusinessException(ErrorCode.PROCESSED_FOOD_LABEL_NOT_READ)
        val key = operation?.let { ProcessedFoodNameScanService.uuidOf(it.requestKey) } ?: clientRequestId!!
        val target = operation ?: operations.save(
            ScanOperation(
                memberId = memberId,
                keyNamespace = NAMESPACE,
                requestKey = ProcessedFoodNameScanService.bytesOf(key),
                manifestHash = manifestHash,
                mode = MODE,
                lifecycle = ProcessedFoodNameScanState.LABEL_RESULT.name,
                initialLanguage = language,
                fxState = FX_STATE,
                requestDocument = emptyMap(),
                limitsVersion = LIMITS_VERSION,
                expiresAt = now.plus(ProcessedFoodNameScanService.RESULT_TTL),
                resultExpiresAt = now.plus(ProcessedFoodNameScanService.RESULT_TTL),
                keyRetireAfter = now.plus(ProcessedFoodNameScanService.KEY_TTL),
            ),
        )
        if (target.usageRecordedAt == null) {
            target.usageRecordedAt = now
            usages.save(ScanUsage(target.id, now, ProcessedFoodNameScanService.ENTITLEMENT_NOT_CHARGED))
        }
        val previous = target.requestDocument["label"] as? Map<*, *>
        val firstSucceededAt = previous?.get("firstSucceededAt") as? String ?: now.toString()
        val recallCount = if (previous == null) 0 else (previous["recallCount"] as? Number)?.toInt()?.plus(1) ?: 1
        target.lifecycle = ProcessedFoodNameScanState.LABEL_RESULT.name
        target.requestDocument = target.requestDocument + mapOf(
            "label" to mapOf("rawText" to text.rawText, "pageCount" to pages.size, "firstSucceededAt" to firstSucceededAt, "recallCount" to recallCount),
        )
        return responseOf(memberId, key, text, reading, language)
    }

    private fun responseOf(memberId: Long, key: UUID, text: ProcessedFoodLabelText, reading: IngredientLabelReading, language: String): ProcessedFoodLabelScanResponse {
        val mayContain = text.mayContain?.let { IngredientLabelReader.read(null, it).detections }.orEmpty()
        val avoidance = memberService.getAvoidance(memberId)
        val judgement = ProcessedFoodJudge.judge(reading, mayContain, withoutHeadings(text.rawMaterials).ifBlank { null }, avoidance)
        return ProcessedFoodLabelScanResponse(
            analysisId = key.toString(),
            state = ProcessedFoodNameScanState.LABEL_RESULT,
            labelSource = sourceOf(reading, text),
            detections = reading.detections.map { ProcessedFoodDetectionResponse(it.code, it.source, it.evidence) },
            unmapped = emptyList(),
            mayContain = mayContain.map { ProcessedFoodMayContainResponse(it.code, it.source, it.evidence) },
            avoidance = ProcessedFoodDetailResponse.avoidanceOf(avoidance, reading),
            rawText = text.rawText,
            charged = false,
            verdict = judgement.verdict,
            avoidMatches = judgement.avoidMatches,
            mayContainMatches = judgement.mayContainMatches,
            unclearIngredients = judgement.unclearIngredients,
            unclearIngredientLabels = UnclearIngredientLabels.labelsOf(judgement.unclearTerms, LanguageCode.from(language)),
            majorAllergens = judgement.majorAllergens,
            freeUntil = null,
        )
    }

    private fun continued(memberId: Long, analysisId: String, now: LocalDateTime): ScanOperation {
        val key = runCatching { UUID.fromString(analysisId) }.getOrNull() ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
        val bytes = ProcessedFoodNameScanService.bytesOf(key)
        val operation = (operations.findForUpdate(memberId, ProcessedFoodNameScanService.NAMESPACE, bytes) ?: operations.findForUpdate(memberId, NAMESPACE, bytes))
            ?.takeIf { now.isBefore(it.resultExpiresAt) }
            ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
        val state = ProcessedFoodNameScanState.valueOf(operation.lifecycle)
        if (state !in CONTINUABLE) throw BusinessException(ErrorCode.PROCESSED_FOOD_LABEL_NOT_ALLOWED)
        if (state == ProcessedFoodNameScanState.LABEL_RESULT) {
            val label = operation.requestDocument["label"] as? Map<*, *>
            val firstSucceededAt = (label?.get("firstSucceededAt") as? String)?.let(LocalDateTime::parse)
            val recallCount = (label?.get("recallCount") as? Number)?.toInt() ?: 0
            if (firstSucceededAt == null || !now.isBefore(firstSucceededAt.plus(FREE_RECALL_WINDOW)) || recallCount >= MAX_FREE_RECALLS) {
                throw BusinessException(ErrorCode.PROCESSED_FOOD_LABEL_NOT_ALLOWED)
            }
        }
        return operation
    }

    private fun withoutHeadings(text: String): String = text.replace(HEADING_PHRASE, " ")

    private fun unmappedOf(reading: IngredientLabelReading): List<String> =
        reading.unmappedTokens.filterNot { it.replace(" ", "") in LABEL_HEADINGS }

    private fun sourceOf(reading: IngredientLabelReading, text: ProcessedFoodLabelText): ProcessedFoodLabelSource = when {
        reading.declaration == AllergenDeclaration.LISTED -> ProcessedFoodLabelSource.DECLARED
        !IngredientLabelReader.isRawNotProvided(withoutHeadings(text.rawMaterials)) -> ProcessedFoodLabelSource.INGREDIENTS_ONLY
        else -> ProcessedFoodLabelSource.NONE
    }

    companion object {
        const val NAMESPACE = "PFOOD_LABEL"
        const val MODE = "PROCESSED_FOOD_LABEL"
        private const val FX_STATE = "NONE"
        private const val LIMITS_VERSION = "pfood-label-1"
        private const val MIN_HANGUL_TOKENS = 2
        private val LABEL_HEADINGS = setOf(
            "원재료", "원재료명", "원재료및함량", "원재료명및함량", "함량", "및",
            "알레르기", "유발물질", "알레르기유발물질", "알레르기유발성분", "알레르기성분", "알레르기물질",
            "성분", "성분명", "식품유형", "내용량",
        )
        private val HEADING_PHRASE = Regex(
            LABEL_HEADINGS.sortedByDescending { it.length }.joinToString("|", "(?<![가-힣])(?:", ")(?![가-힣])") { heading -> heading.map { Regex.escape(it.toString()) }.joinToString("\\s*") },
        )
        private const val MAX_FREE_RECALLS = 3
        private val FREE_RECALL_WINDOW: java.time.Duration = java.time.Duration.ofMinutes(30)
        private val CONTINUABLE = setOf(ProcessedFoodNameScanState.NO_CANDIDATE, ProcessedFoodNameScanState.REJECTED, ProcessedFoodNameScanState.LABEL_RESULT)
    }
}
