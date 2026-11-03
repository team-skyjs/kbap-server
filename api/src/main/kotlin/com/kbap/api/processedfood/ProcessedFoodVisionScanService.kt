package com.kbap.api.processedfood

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadPurpose
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.ProcessedFoodPhotoKind
import com.kbap.common.port.llm.ProcessedFoodPhotoReader
import com.kbap.common.port.llm.ProcessedFoodPhotoReading
import com.kbap.common.port.llm.ProcessedFoodPhotoReason
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

@Service
class ProcessedFoodVisionScanService(
    private val reader: ProcessedFoodPhotoReader,
    private val uploads: UploadedImageJpaRepository,
    private val operations: ScanOperationJpaRepository,
    private val members: MemberJpaRepository,
    private val memberService: MemberService,
    private val nameScanService: ProcessedFoodNameScanService,
    private val labelScanService: ProcessedFoodLabelScanService,
    private val translationService: ProcessedFoodTranslationService,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.processed-food.vision.daily-limit-per-member:30}") private val dailyLimit: Long,
    @Value("\${kbap.processed-food.vision.max-image-bytes:2097152}") private val maxImageBytes: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    fun scanVision(memberId: Long, imagePath: String, key: UUID, language: LanguageCode): ProcessedFoodVisionScanResponse {
        val stored = transaction.execute { reserve(memberId, imagePath, key, language) }
        val reading = stored ?: readPhoto(memberId, key, imagePath)
        return when (reading.kind) {
            ProcessedFoodPhotoKind.PACKAGE_FRONT -> {
                val ocrText = listOfNotNull(reading.maker, reading.productName).joinToString("\n")
                val scanned = nameScanService.scanFront(memberId, key, ocrText, reading.reportNo, language.code, imagePath)
                responseOf(key, reading, scanned.state).copy(
                    front = ProcessedFoodVisionFrontResponse(reading.productName, reading.maker, reading.reportNo, translationService.localizeSummaries(scanned.candidates, language)),
                )
            }
            ProcessedFoodPhotoKind.INGREDIENT_LABEL -> {
                val label = labelScanService.scanLabel(memberId, null, key, listOf(reading.labelText.orEmpty().ifBlank { " " }), language.code)
                responseOf(key, reading, label.state).copy(label = label)
            }
            ProcessedFoodPhotoKind.MENU, ProcessedFoodPhotoKind.OTHER -> responseOf(key, reading, ProcessedFoodNameScanState.NOT_PACKAGED)
        }
    }

    private fun reserve(memberId: Long, imagePath: String, key: UUID, language: LanguageCode): ProcessedFoodPhotoReading? {
        members.findByIdForUpdate(memberId)
        memberService.getMember(memberId)
        val image = uploads.findByPathIn(listOf(imagePath))
            .firstOrNull { it.memberId == memberId && UploadPurpose.PACKAGED_SCAN.segment in it.path }
            ?: throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)
        if (image.sizeBytes > maxImageBytes) throw BusinessException(ErrorCode.PROCESSED_FOOD_PHOTO_TOO_LARGE)
        val now = LocalDateTime.now()
        val hash = hashOf(imagePath)
        operations.findForUpdate(memberId, NAMESPACE, ProcessedFoodNameScanService.bytesOf(key))?.let { existing ->
            if (!existing.manifestHash.contentEquals(hash)) throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
            readingOf(existing)?.let { return it }
            if (existing.lifecycle == READING && now.isBefore(existing.updatedAt.plus(READING_STALE_AFTER))) {
                throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
            }
            val attempts = (existing.requestDocument["attempts"] as? Number)?.toInt() ?: 1
            if (attempts >= MAX_ATTEMPTS_PER_KEY) throw BusinessException(ErrorCode.PROCESSED_FOOD_PHOTO_DAILY_LIMIT)
            existing.lifecycle = READING
            existing.requestDocument = existing.requestDocument + mapOf("attempts" to attempts + 1, "retriedAt" to now.toString())
            return null
        }
        if (operations.countByMemberIdAndKeyNamespaceAndCreatedAtGreaterThanEqual(memberId, NAMESPACE, startOfTodayKst()) >= dailyLimit) {
            throw BusinessException(ErrorCode.PROCESSED_FOOD_PHOTO_DAILY_LIMIT)
        }
        try {
            operations.saveAndFlush(
                ScanOperation(
                    memberId = memberId,
                    keyNamespace = NAMESPACE,
                    requestKey = ProcessedFoodNameScanService.bytesOf(key),
                    manifestHash = hash,
                    mode = MODE,
                    lifecycle = READING,
                    initialLanguage = language.code,
                    fxState = FX_STATE,
                    requestDocument = mapOf("imagePath" to imagePath, "attempts" to 1),
                    limitsVersion = LIMITS_VERSION,
                    expiresAt = now.plus(ProcessedFoodNameScanService.RESULT_TTL),
                    resultExpiresAt = now.plus(ProcessedFoodNameScanService.RESULT_TTL),
                    keyRetireAfter = now.plus(ProcessedFoodNameScanService.KEY_TTL),
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
        }
        return null
    }

    private fun readPhoto(memberId: Long, key: UUID, imagePath: String): ProcessedFoodPhotoReading {
        val started = System.nanoTime()
        val reading = try {
            reader.read(imagePath)
        } catch (e: Exception) {
            markFailed(memberId, key)
            log.warn("가공식품 사진 비전 실패 imagePath={} latencyMs={}", imagePath, elapsedMillis(started), e)
            throw when (e) {
                is MenuBoardVisionRateLimitedException -> BusinessException(ErrorCode.SCAN_RATE_LIMITED, payload = e.retryAfterSeconds?.let { mapOf("retryAfterSeconds" to it) })
                else -> BusinessException(ErrorCode.SCAN_VISION_UNAVAILABLE)
            }
        }
        log.info(
            "가공식품 사진 비전 kind={} confidence={} reason={} reportNo={} latencyMs={}",
            reading.kind, reading.confidence, reading.reason, reading.reportNo != null, elapsedMillis(started),
        )
        transaction.executeWithoutResult {
            operations.findForUpdate(memberId, NAMESPACE, ProcessedFoodNameScanService.bytesOf(key))?.let {
                it.lifecycle = READ
                it.requestDocument = it.requestDocument + mapOf(
                    "visionKind" to reading.kind.name,
                    "confidence" to reading.confidence,
                    "reading" to documentOf(reading),
                )
            }
        }
        return reading
    }

    private fun markFailed(memberId: Long, key: UUID) {
        runCatching {
            transaction.executeWithoutResult {
                operations.findForUpdate(memberId, NAMESPACE, ProcessedFoodNameScanService.bytesOf(key))?.lifecycle = FAILED
            }
        }.onFailure { log.warn("가공식품 사진 비전 실패 기록 실패", it) }
    }

    private fun responseOf(key: UUID, reading: ProcessedFoodPhotoReading, state: ProcessedFoodNameScanState) =
        ProcessedFoodVisionScanResponse(
            analysisId = key.toString(),
            kind = reading.kind,
            confidence = reading.confidence,
            reason = reading.reason,
            state = state,
            front = null,
            label = null,
            warnings = reading.warnings,
        )

    private fun startOfTodayKst(): LocalDateTime =
        LocalDate.now(KST).atStartOfDay(KST).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()

    private fun elapsedMillis(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    companion object {
        const val NAMESPACE = "PFOOD_VISION"
        const val MODE = "PROCESSED_FOOD_VISION"
        private const val READING = "READING"
        private const val READ = "READ"
        private const val FAILED = "FAILED"
        private const val FX_STATE = "NONE"
        private const val LIMITS_VERSION = "pfood-vision-1"
        private const val MAX_ATTEMPTS_PER_KEY = 3
        private val READING_STALE_AFTER: Duration = Duration.ofMinutes(2)
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")

        private fun hashOf(imagePath: String): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(imagePath.toByteArray(Charsets.UTF_8))

        private fun documentOf(reading: ProcessedFoodPhotoReading): Map<String, Any?> = mapOf(
            "kind" to reading.kind.name,
            "confidence" to reading.confidence,
            "reason" to reading.reason?.name,
            "productName" to reading.productName,
            "maker" to reading.maker,
            "reportNo" to reading.reportNo,
            "labelText" to reading.labelText,
            "warnings" to reading.warnings,
        )

        private fun readingOf(operation: ScanOperation): ProcessedFoodPhotoReading? {
            if (operation.lifecycle != READ) return null
            val doc = operation.requestDocument["reading"] as? Map<*, *> ?: return null
            return ProcessedFoodPhotoReading(
                kind = ProcessedFoodPhotoKind.valueOf(doc["kind"] as String),
                confidence = (doc["confidence"] as Number).toDouble(),
                reason = (doc["reason"] as? String)?.let(ProcessedFoodPhotoReason::valueOf),
                productName = doc["productName"] as? String,
                maker = doc["maker"] as? String,
                reportNo = doc["reportNo"] as? String,
                labelText = doc["labelText"] as? String,
                warnings = (doc["warnings"] as? List<*>).orEmpty().filterIsInstance<String>(),
            )
        }
    }
}
