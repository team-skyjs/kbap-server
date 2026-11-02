package com.kbap.api.processedfood

import com.kbap.common.domain.LanguageCode
import com.kbap.api.image.UploadedImageService
import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.processedfood.model.ProcessedFoodOcrText
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.domain.scan.ScanUsageJpaRepository
import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanUsage
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
class ProcessedFoodNameScanService(
    private val processedFoodService: ProcessedFoodService,
    private val operations: ScanOperationJpaRepository,
    private val usages: ScanUsageJpaRepository,
    private val memberService: MemberService,
    private val decisions: ProcessedFoodNameDecisionService,
    private val uploadedImages: UploadedImageService,
    private val signals: ProcessedFoodSignalRecorder,
) {
    @Transactional
    fun scanName(memberId: Long, ocrText: String, language: String, imagePath: String? = null): ProcessedFoodNameScanResponse {
        memberService.getMember(memberId)
        val ranked = processedFoodService.getNameScanCandidates(ProcessedFoodOcrText.queriesOf(ocrText), MAX_CANDIDATES)
        val candidates = ranked.products
        val analysisId = UUID.randomUUID()
        val now = LocalDateTime.now()
        val state = if (candidates.isEmpty()) ProcessedFoodNameScanState.NO_CANDIDATE else ProcessedFoodNameScanState.CANDIDATES
        val operation = operations.save(
            ScanOperation(
                memberId = memberId,
                keyNamespace = NAMESPACE,
                requestKey = bytesOf(analysisId),
                manifestHash = MessageDigest.getInstance("SHA-256").digest(ocrText.toByteArray(Charsets.UTF_8)),
                mode = MODE,
                lifecycle = state.name,
                initialLanguage = language,
                fxState = FX_STATE,
                requestDocument = mapOf("ocrText" to ocrText, "candidates" to candidates.map { it.reportNo }, "boostedReportNos" to ranked.boostedReportNos) +
                    listOfNotNull(imagePath?.let { "imagePath" to it }),
                limitsVersion = LIMITS_VERSION,
                expiresAt = now.plus(CANDIDATES_TTL),
                resultExpiresAt = now.plus(RESULT_TTL),
                keyRetireAfter = now.plus(KEY_TTL),
            ),
        )
        if (imagePath != null && candidates.isNotEmpty() && decisions.isEnabled()) {
            val segment = UploadPurpose.PACKAGED_SCAN.segment
            when {
                segment !in imagePath -> decisions.recordSkipped(operation.id, "purpose")
                !uploadedImages.areAllOwnedByAnyOf(listOf(imagePath), segment, setOf(memberId)) -> decisions.recordSkipped(operation.id, "image")
                else -> decisions.requestAfterCommit(operation.id, imagePath, candidates)
            }
        }
        return ProcessedFoodNameScanResponse(analysisId.toString(), state, processedFoodService.getSummaries(candidates, memberId), operation.expiresAt)
    }

    @Transactional
    fun confirm(memberId: Long, analysisId: String, reportNo: String?): ProcessedFoodNameConfirmation {
        val key = runCatching { UUID.fromString(analysisId) }.getOrNull() ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
        val operation = operations.findForUpdate(memberId, NAMESPACE, bytesOf(key)) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
        val now = LocalDateTime.now()
        val state = ProcessedFoodNameScanState.valueOf(operation.lifecycle)
        val deadline = if (state == ProcessedFoodNameScanState.CANDIDATES) operation.expiresAt else operation.resultExpiresAt
        if (now.isAfter(deadline)) throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
        val response = when (state) {
            ProcessedFoodNameScanState.CANDIDATES -> settle(operation, memberId, reportNo, now)
            ProcessedFoodNameScanState.CONFIRMED -> {
                val selected = operation.requestDocument["selectedReportNo"] as String
                if (reportNo != selected) throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_ALREADY_CONFIRMED)
                ProcessedFoodNameConfirmResponse(analysisId, state, false, processedFoodService.getConfirmedProcessedFoodDetail(selected, memberId), null)
            }
            ProcessedFoodNameScanState.REJECTED ->
                if (reportNo == null) ProcessedFoodNameConfirmResponse(analysisId, state, false, null, null)
                else throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_ALREADY_CONFIRMED)
            ProcessedFoodNameScanState.NO_CANDIDATE, ProcessedFoodNameScanState.LABEL_RESULT -> throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_ALREADY_CONFIRMED)
        }
        return ProcessedFoodNameConfirmation(response, LanguageCode.from(operation.initialLanguage))
    }

    private fun settle(operation: ScanOperation, memberId: Long, reportNo: String?, now: LocalDateTime): ProcessedFoodNameConfirmResponse {
        val analysisId = uuidOf(operation.requestKey).toString()
        if (reportNo == null) {
            operation.lifecycle = ProcessedFoodNameScanState.REJECTED.name
            return ProcessedFoodNameConfirmResponse(analysisId, ProcessedFoodNameScanState.REJECTED, false, null, null)
        }
        val detail = processedFoodService.getProcessedFoodDetailByReportNo(reportNo, memberId)
        memberService.getMember(memberId)
        val offered = operation.requestDocument["candidates"] as? List<*> ?: emptyList<Any>()
        operation.requestDocument = operation.requestDocument + mapOf("selectedReportNo" to reportNo, "selectedOutsideCandidates" to (reportNo !in offered))
        operation.usageRecordedAt = now
        operation.lifecycle = ProcessedFoodNameScanState.CONFIRMED.name
        usages.save(ScanUsage(operation.id, now, ENTITLEMENT_NOT_CHARGED))
        if (reportNo in offered) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = signals.record(detail.id, memberId, ProcessedFoodSignalType.SCAN_MATCH)
                },
            )
        }
        return ProcessedFoodNameConfirmResponse(analysisId, ProcessedFoodNameScanState.CONFIRMED, false, detail, null)
    }

    companion object {
        const val NAMESPACE = "PFOOD_NAME"

        fun bytesOf(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

        fun uuidOf(bytes: ByteArray): UUID = ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
        const val MODE = "PROCESSED_FOOD_NAME"
        private const val FX_STATE = "NONE"
        private const val LIMITS_VERSION = "pfood-name-1"
        const val ENTITLEMENT_NOT_CHARGED = "NOT_CHARGED"
        private const val MAX_CANDIDATES = 30
        private val CANDIDATES_TTL: Duration = Duration.ofMinutes(30)
        val RESULT_TTL: Duration = Duration.ofDays(7)
        val KEY_TTL: Duration = Duration.ofDays(30)
    }
}
