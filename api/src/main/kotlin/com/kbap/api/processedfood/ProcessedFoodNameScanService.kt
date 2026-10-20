package com.kbap.api.processedfood

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.processedfood.model.ProcessedFoodOcrText
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.domain.scan.ScanUsageJpaRepository
import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanUsage
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
) {
    @Transactional
    fun scanName(memberId: Long, ocrText: String, language: String): ProcessedFoodNameScanResponse {
        memberService.getMember(memberId)
        val candidates = processedFoodService.getNameScanCandidates(ProcessedFoodOcrText.queriesOf(ocrText), MAX_CANDIDATES)
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
                requestDocument = mapOf("ocrText" to ocrText, "candidates" to candidates.map { it.reportNo }),
                limitsVersion = LIMITS_VERSION,
                expiresAt = now.plus(CANDIDATES_TTL),
                resultExpiresAt = now.plus(RESULT_TTL),
                keyRetireAfter = now.plus(KEY_TTL),
            ),
        )
        return ProcessedFoodNameScanResponse(analysisId.toString(), state, candidates.map(ProcessedFoodSummaryResponse::from), operation.expiresAt)
    }

    @Transactional
    fun confirm(memberId: Long, analysisId: String, reportNo: String?): ProcessedFoodNameConfirmResponse {
        val key = runCatching { UUID.fromString(analysisId) }.getOrNull() ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
        val operation = operations.findForUpdate(memberId, NAMESPACE, bytesOf(key)) ?: throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
        val now = LocalDateTime.now()
        val state = ProcessedFoodNameScanState.valueOf(operation.lifecycle)
        val deadline = if (state == ProcessedFoodNameScanState.CANDIDATES) operation.expiresAt else operation.resultExpiresAt
        if (now.isAfter(deadline)) throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_NOT_FOUND)
        return when (state) {
            ProcessedFoodNameScanState.CANDIDATES -> settle(operation, memberId, reportNo, now)
            ProcessedFoodNameScanState.CONFIRMED -> {
                val selected = operation.requestDocument["selectedReportNo"] as String
                if (reportNo != selected) throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_ALREADY_CONFIRMED)
                ProcessedFoodNameConfirmResponse(analysisId, state, false, processedFoodService.getProcessedFoodDetailByReportNo(selected, memberId))
            }
            ProcessedFoodNameScanState.REJECTED ->
                if (reportNo == null) ProcessedFoodNameConfirmResponse(analysisId, state, false, null)
                else throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_ALREADY_CONFIRMED)
            ProcessedFoodNameScanState.NO_CANDIDATE -> throw BusinessException(ErrorCode.PROCESSED_FOOD_SCAN_ALREADY_CONFIRMED)
        }
    }

    private fun settle(operation: ScanOperation, memberId: Long, reportNo: String?, now: LocalDateTime): ProcessedFoodNameConfirmResponse {
        val analysisId = uuidOf(operation.requestKey).toString()
        if (reportNo == null) {
            operation.lifecycle = ProcessedFoodNameScanState.REJECTED.name
            return ProcessedFoodNameConfirmResponse(analysisId, ProcessedFoodNameScanState.REJECTED, false, null)
        }
        val detail = processedFoodService.getProcessedFoodDetailByReportNo(reportNo, memberId)
        val member = memberService.getMember(memberId)
        if (!member.isScanAllowed()) throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
        val offered = operation.requestDocument["candidates"] as? List<*> ?: emptyList<Any>()
        operation.requestDocument = operation.requestDocument + mapOf("selectedReportNo" to reportNo, "selectedOutsideCandidates" to (reportNo !in offered))
        operation.usageRecordedAt = now
        operation.lifecycle = ProcessedFoodNameScanState.CONFIRMED.name
        usages.save(ScanUsage(operation.id, now, if (member.scanUnlocked) ENTITLEMENT_UNLIMITED else ENTITLEMENT_FREE))
        memberService.increaseScanCount(memberId)
        return ProcessedFoodNameConfirmResponse(analysisId, ProcessedFoodNameScanState.CONFIRMED, true, detail)
    }

    private fun bytesOf(uuid: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

    private fun uuidOf(bytes: ByteArray): UUID = ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }

    companion object {
        const val NAMESPACE = "PFOOD_NAME"
        const val MODE = "PROCESSED_FOOD_NAME"
        private const val FX_STATE = "NONE"
        private const val LIMITS_VERSION = "pfood-name-1"
        private const val ENTITLEMENT_FREE = "FREE"
        private const val ENTITLEMENT_UNLIMITED = "UNLIMITED"
        private const val MAX_CANDIDATES = 5
        private val CANDIDATES_TTL: Duration = Duration.ofMinutes(30)
        private val RESULT_TTL: Duration = Duration.ofDays(7)
        private val KEY_TTL: Duration = Duration.ofDays(30)
    }
}
