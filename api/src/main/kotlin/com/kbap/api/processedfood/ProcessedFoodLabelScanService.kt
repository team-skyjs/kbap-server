package com.kbap.api.processedfood

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.ingredient.label.AllergenDeclaration
import com.kbap.common.domain.ingredient.label.IngredientLabelReader
import com.kbap.common.domain.ingredient.label.IngredientLabelReading
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.domain.scan.ScanUsageJpaRepository
import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanUsage
import com.kbap.common.port.scan.ScanReservationResult
import com.kbap.common.port.scan.ScanReservationStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

@Service
class ProcessedFoodLabelScanService(
    private val operations: ScanOperationJpaRepository,
    private val usages: ScanUsageJpaRepository,
    private val memberService: MemberService,
    private val members: MemberJpaRepository,
    private val reservationStore: ScanReservationStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun scanLabel(memberId: Long, analysisId: String?, pages: List<String>, language: String): ProcessedFoodLabelScanResponse {
        if (analysisId == null) members.findByIdForUpdate(memberId)
        val member = memberService.getMember(memberId)
        val text = ProcessedFoodLabelText.of(pages)
        val reading = IngredientLabelReader.read(text.allergens, text.rawMaterials.ifEmpty { null })
        val readable = text.hasLabelLine && reading.detections.isNotEmpty() || text.hasLabelLine && reading.unmappedTokens.count { token -> token.count { it in '가'..'힣' } >= 2 } >= MIN_HANGUL_TOKENS
        val now = LocalDateTime.now()
        val operation = analysisId?.let { continued(memberId, it, now) }
        if (!readable) throw BusinessException(ErrorCode.PROCESSED_FOOD_LABEL_NOT_READ)
        val key = operation?.let { ProcessedFoodNameScanService.uuidOf(it.requestKey) } ?: UUID.randomUUID()
        val target = operation ?: operations.save(
            ScanOperation(
                memberId = memberId,
                keyNamespace = NAMESPACE,
                requestKey = ProcessedFoodNameScanService.bytesOf(key),
                manifestHash = MessageDigest.getInstance("SHA-256").digest(text.rawText.toByteArray(Charsets.UTF_8)),
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
        val charged = target.usageRecordedAt == null
        if (charged) charge(memberId, key, target, member, now)
        val previous = target.requestDocument["label"] as? Map<*, *>
        val firstSucceededAt = previous?.get("firstSucceededAt") as? String ?: now.toString()
        val recallCount = if (previous == null) 0 else (previous["recallCount"] as? Number)?.toInt()?.plus(1) ?: 1
        target.lifecycle = ProcessedFoodNameScanState.LABEL_RESULT.name
        target.requestDocument = target.requestDocument + mapOf(
            "label" to mapOf("rawText" to text.rawText, "pageCount" to pages.size, "firstSucceededAt" to firstSucceededAt, "recallCount" to recallCount),
        )
        val mayContain = text.mayContain?.let { IngredientLabelReader.read(null, it).detections }.orEmpty()
        return ProcessedFoodLabelScanResponse(
            analysisId = key.toString(),
            state = ProcessedFoodNameScanState.LABEL_RESULT,
            labelSource = sourceOf(reading, text),
            detections = reading.detections.map { ProcessedFoodDetectionResponse(it.code, it.source, it.evidence) },
            unmapped = reading.unmappedTokens,
            mayContain = mayContain.map { ProcessedFoodMayContainResponse(it.code, it.source, it.evidence) },
            avoidance = ProcessedFoodDetailResponse.avoidanceOf(memberService.getAvoidance(memberId), reading),
            rawText = text.rawText,
            charged = charged,
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

    private fun charge(memberId: Long, key: UUID, operation: ScanOperation, member: Member, now: LocalDateTime) {
        val reservationId = RESERVATION_PREFIX + key
        when (reservationStore.reserve(memberId, reservationId, member.scanCount, member.scanLimit())) {
            ScanReservationResult.LIMIT_EXCEEDED -> throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
            ScanReservationResult.DUPLICATE_REQUEST -> throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
            ScanReservationResult.RESERVED -> TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        runCatching { reservationStore.release(memberId, reservationId) }
                            .onFailure { log.warn("성분표 분석 예약 해제 실패 — TTL 만료로 회수됨, memberId={}", memberId, it) }
                    }
                },
            )
        }
        operation.usageRecordedAt = now
        usages.save(ScanUsage(operation.id, now, if (member.scanUnlocked) ProcessedFoodNameScanService.ENTITLEMENT_UNLIMITED else ProcessedFoodNameScanService.ENTITLEMENT_FREE))
        if (members.increaseScanCountWithinLimit(memberId, Member.FREE_SCAN_LIMIT) == 0) throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
    }

    private fun sourceOf(reading: IngredientLabelReading, text: ProcessedFoodLabelText): ProcessedFoodLabelSource = when {
        reading.declaration == AllergenDeclaration.LISTED -> ProcessedFoodLabelSource.DECLARED
        text.rawMaterials.isNotBlank() -> ProcessedFoodLabelSource.INGREDIENTS_ONLY
        else -> ProcessedFoodLabelSource.NONE
    }

    companion object {
        const val NAMESPACE = "PFOOD_LABEL"
        const val MODE = "PROCESSED_FOOD_LABEL"
        private const val RESERVATION_PREFIX = "pfood-label:"
        private const val FX_STATE = "NONE"
        private const val LIMITS_VERSION = "pfood-label-1"
        private const val MIN_HANGUL_TOKENS = 2
        private const val MAX_FREE_RECALLS = 3
        private val FREE_RECALL_WINDOW: java.time.Duration = java.time.Duration.ofMinutes(30)
        private val CONTINUABLE = setOf(ProcessedFoodNameScanState.NO_CANDIDATE, ProcessedFoodNameScanState.REJECTED, ProcessedFoodNameScanState.LABEL_RESULT)
    }
}
