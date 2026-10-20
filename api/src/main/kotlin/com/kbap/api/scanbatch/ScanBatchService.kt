package com.kbap.api.scanbatch

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.scan.ScanAttemptJpaRepository
import com.kbap.common.domain.scan.ScanOperationJpaRepository
import com.kbap.common.domain.scan.ScanOperationPhotoJpaRepository
import com.kbap.common.domain.scan.ScanOperationResultJpaRepository
import com.kbap.common.domain.scan.ScanUsageJpaRepository
import com.kbap.common.domain.scan.model.ScanAttempt
import com.kbap.common.domain.scan.model.ScanOperation
import com.kbap.common.domain.scan.model.ScanOperationPhoto
import com.kbap.common.domain.scan.model.ScanOperationResult
import com.kbap.common.domain.scan.model.ScanPhotoState
import com.kbap.common.domain.scan.model.ScanUsage
import com.kbap.common.port.scan.ScanReservationResult
import com.kbap.common.port.scan.ScanReservationStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

data class ScanBatchClaim(
    val memberId: Long,
    val operationId: Long,
    val photoRowId: Long,
    val attemptId: Long,
    val attemptNo: Int,
    val claimToken: ByteArray,
)

data class ScanBatchPhotoInput(val photoId: UUID, val imagePath: String)

@Service
class ScanBatchService(
    private val operations: ScanOperationJpaRepository,
    private val photos: ScanOperationPhotoJpaRepository,
    private val attempts: ScanAttemptJpaRepository,
    private val results: ScanOperationResultJpaRepository,
    private val usages: ScanUsageJpaRepository,
    private val uploads: UploadedImageJpaRepository,
    private val memberService: MemberService,
    private val members: MemberJpaRepository,
    private val reservationStore: ScanReservationStore,
    private val batchSwitch: ScanBatchSwitch,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun createBatch(memberId: Long, batchId: UUID, lang: String, currency: String, inputs: List<ScanBatchPhotoInput>): ScanBatchResponse {
        requireEnabled()
        if (inputs.size > MAX_PHOTOS) throw BusinessException(ErrorCode.SCAN_BATCH_TOO_MANY_PHOTOS)
        if (inputs.isEmpty() || inputs.distinctBy { it.photoId }.size != inputs.size || inputs.distinctBy { it.imagePath }.size != inputs.size) {
            throw BusinessException(ErrorCode.INVALID_REQUEST)
        }
        members.findByIdForUpdate(memberId) ?: throw BusinessException(ErrorCode.MEMBER_NOT_FOUND)
        val manifestHash = sha256("$lang|$currency|" + inputs.joinToString("|") { "${it.photoId}=${it.imagePath}" })
        operations.findForUpdate(memberId, NAMESPACE, bytesOf(batchId))?.let { existing ->
            if (!existing.manifestHash.contentEquals(manifestHash)) throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
            return viewOf(existing, LocalDateTime.now())
        }
        val images = uploads.findByPathIn(inputs.map { it.imagePath }).associateBy { it.path }
        val verified = inputs.map { input ->
            images[input.imagePath]?.takeIf { it.isOwnedBy(memberId) && it.path.contains(SCAN_SEGMENT) }
                ?: throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)
        }
        reserve(memberId, batchId, memberService.getMember(memberId))
        val now = LocalDateTime.now()
        val operation = operations.save(
            ScanOperation(
                memberId = memberId,
                keyNamespace = NAMESPACE,
                requestKey = bytesOf(batchId),
                manifestHash = manifestHash,
                mode = MODE,
                lifecycle = ACTIVE,
                initialLanguage = lang,
                fxState = FX_STATE,
                requestDocument = mapOf("currency" to currency),
                limitsVersion = LIMITS_VERSION,
                expiresAt = now.plus(EXECUTION_WINDOW),
                resultExpiresAt = now.plus(RESULT_TTL),
                keyRetireAfter = now.plus(KEY_TTL),
                reservationState = RESERVATION_HELD,
            ),
        )
        photos.saveAll(
            inputs.zip(verified).mapIndexed { index, (input, image) ->
                ScanOperationPhoto(
                    operationId = operation.id,
                    photoId = bytesOf(input.photoId),
                    selectionIndex = index,
                    uploadedImageId = image.id,
                    objectKey = image.path,
                    sourceFingerprint = mapOf("uploadedImageId" to image.id, "contentType" to image.contentType, "sizeBytes" to image.sizeBytes),
                    byteSize = image.sizeBytes,
                )
            },
        )
        return viewOf(operation, now)
    }

    @Transactional(readOnly = true)
    fun getBatch(memberId: Long, batchId: String): ScanBatchResponse {
        requireEnabled()
        val now = LocalDateTime.now()
        val operation = parse(batchId)?.let { operations.findByMemberIdAndKeyNamespaceAndRequestKey(memberId, NAMESPACE, bytesOf(it)) }
            ?.takeIf { now.isBefore(it.resultExpiresAt) }
            ?: throw BusinessException(ErrorCode.SCAN_BATCH_NOT_FOUND)
        return viewOf(operation, now)
    }

    @Transactional
    fun cancelBatch(memberId: Long, batchId: String): ScanBatchResponse {
        requireEnabled()
        val operation = parse(batchId)?.let { operations.findForUpdate(memberId, NAMESPACE, bytesOf(it)) }
            ?: throw BusinessException(ErrorCode.SCAN_BATCH_NOT_FOUND)
        if (operation.lifecycle == ACTIVE) {
            operation.lifecycle = CANCELLED
            photos.findAllForUpdate(operation.id).forEach(ScanOperationPhoto::cancel)
            if (operation.reservationState == RESERVATION_HELD) releaseAfterCommit(operation)
        }
        return viewOf(operation, LocalDateTime.now())
    }

    @Transactional
    fun startAttempt(memberId: Long, batchId: UUID, selectionIndex: Int): ScanBatchClaim? {
        if (!batchSwitch.enabled) return null
        val now = LocalDateTime.now()
        val operation = operations.findForUpdate(memberId, NAMESPACE, bytesOf(batchId)) ?: return null
        if (operation.lifecycle != ACTIVE || !now.isBefore(operation.expiresAt)) return null
        val photo = photos.findAllForUpdate(operation.id).singleOrNull { it.selectionIndex == selectionIndex } ?: return null
        if (!photo.isRunnableAt(now)) return null
        if (operation.usageRecordedAt == null && operation.reservationState != RESERVATION_HELD) {
            reserve(memberId, batchId, memberService.getMember(memberId))
            operation.reservationState = RESERVATION_HELD
        }
        val token = bytesOf(UUID.randomUUID())
        photo.claim(token, now.plus(ATTEMPT_LEASE))
        val attempt = attempts.save(
            ScanAttempt(
                operationId = operation.id,
                memberId = memberId,
                photoRowId = photo.id,
                unitKey = "photo:${photo.selectionIndex}",
                phase = PHASE,
                attemptNo = photo.attemptNo,
                claimToken = token,
                state = ATTEMPT_RUNNING,
                startedAt = now,
                deadlineAt = now.plus(ATTEMPT_LEASE),
                leaseUntil = now.plus(ATTEMPT_LEASE),
            ),
        )
        return ScanBatchClaim(memberId, operation.id, photo.id, attempt.id, photo.attemptNo, token)
    }

    @Transactional
    fun recordSuccess(claim: ScanBatchClaim, document: Map<String, Any?>): Boolean {
        val now = LocalDateTime.now()
        val (operation, photo, attempt) = lockClaim(claim) ?: return false
        if (operation.usageRecordedAt == null) {
            val unlocked = memberService.getMember(claim.memberId).scanUnlocked
            if (members.increaseScanCountWithinLimit(claim.memberId, Member.FREE_SCAN_LIMIT) == 0) {
                fail(operation, photo, attempt, ScanOperationPhoto.QUOTA_EXHAUSTED, now)
                operation.lifecycle = QUOTA_EXHAUSTED
                photos.findByOperationIdOrderBySelectionIndex(operation.id).forEach(ScanOperationPhoto::cancel)
                if (operation.reservationState == RESERVATION_HELD) releaseAfterCommit(operation)
                return false
            }
            usages.save(ScanUsage(operation.id, now, if (unlocked) ENTITLEMENT_UNLIMITED else ENTITLEMENT_FREE))
            operation.usageRecordedAt = now
            releaseAfterCommit(operation)
        }
        results.save(ScanOperationResult(operation.id, photo.id, attempt.id, attempt.attemptNo, 1, document))
        photo.succeed()
        attempt.finish(ATTEMPT_SUCCEEDED, null, now)
        operation.resultRevision += 1
        return true
    }

    @Transactional
    fun recordFailure(claim: ScanBatchClaim, reason: String): Boolean {
        val (operation, photo, attempt) = lockClaim(claim) ?: return false
        fail(operation, photo, attempt, reason, LocalDateTime.now())
        return true
    }

    private fun fail(operation: ScanOperation, photo: ScanOperationPhoto, attempt: ScanAttempt, reason: String, now: LocalDateTime) {
        photo.fail(reason)
        attempt.finish(ATTEMPT_FAILED, reason, now)
        val allFailed = photos.findByOperationIdOrderBySelectionIndex(operation.id).all { it.executionState == ScanPhotoState.FAILED }
        if (allFailed && operation.usageRecordedAt == null && operation.reservationState == RESERVATION_HELD) releaseAfterCommit(operation)
    }

    private fun lockClaim(claim: ScanBatchClaim): Triple<ScanOperation, ScanOperationPhoto, ScanAttempt>? {
        val now = LocalDateTime.now()
        val operation = operations.findByIdForUpdate(claim.operationId) ?: return null
        val photo = photos.findAllForUpdate(operation.id).singleOrNull { it.id == claim.photoRowId } ?: return null
        val attempt = attempts.findById(claim.attemptId).orElse(null) ?: return null
        if (operation.lifecycle != ACTIVE || !photo.isClaimedBy(claim.claimToken)) {
            attempt.finish(ATTEMPT_DISCARDED, null, now)
            return null
        }
        if (!photo.isLeaseLiveAt(now)) {
            fail(operation, photo, attempt, ScanOperationPhoto.LEASE_EXPIRED, now)
            return null
        }
        return Triple(operation, photo, attempt)
    }

    private fun requireEnabled() {
        if (!batchSwitch.enabled) throw BusinessException(ErrorCode.SCAN_BATCH_NOT_FOUND)
    }

    private fun reserve(memberId: Long, batchId: UUID, member: Member) {
        val reservationId = reservationIdOf(batchId)
        when (reservationStore.reserve(memberId, reservationId, member.scanCount, member.scanLimit(), EXECUTION_WINDOW)) {
            ScanReservationResult.LIMIT_EXCEEDED -> throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
            ScanReservationResult.DUPLICATE_REQUEST -> throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
            ScanReservationResult.RESERVED -> TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCompletion(status: Int) {
                        if (status != TransactionSynchronization.STATUS_COMMITTED) release(memberId, reservationId)
                    }
                },
            )
        }
    }

    private fun releaseAfterCommit(operation: ScanOperation) {
        operation.reservationState = RESERVATION_RELEASED
        val memberId = operation.memberId
        val reservationId = reservationIdOf(uuidOf(operation.requestKey))
        TransactionSynchronizationManager.registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit() = release(memberId, reservationId)
            },
        )
    }

    private fun release(memberId: Long, reservationId: String) {
        runCatching { reservationStore.release(memberId, reservationId) }
            .onFailure { log.warn("스캔 묶음 예약 해제 실패 — TTL 만료로 회수됨, memberId={}", memberId, it) }
    }

    private fun viewOf(operation: ScanOperation, now: LocalDateTime): ScanBatchResponse =
        ScanBatchResponse.of(operation, uuidOf(operation.requestKey), photos.findByOperationIdOrderBySelectionIndex(operation.id), now)

    private fun parse(raw: String): UUID? = runCatching { UUID.fromString(raw) }.getOrNull()?.takeIf { it.toString() == raw.lowercase() }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    companion object {
        const val NAMESPACE = "SCAN_BATCH"
        const val MODE = "MENU_BATCH"
        const val ACTIVE = "ACTIVE"
        const val CANCELLED = "CANCELLED"
        const val QUOTA_EXHAUSTED = "QUOTA_EXHAUSTED"
        const val MAX_PHOTOS = 10
        private const val SCAN_SEGMENT = "images/scans/"
        private const val FX_STATE = "NONE"
        private const val LIMITS_VERSION = "scan-batch-1"
        private const val PHASE = "EXTRACT"
        private const val RESERVATION_HELD = "HELD"
        private const val RESERVATION_RELEASED = "RELEASED"
        private const val ATTEMPT_RUNNING = "RUNNING"
        private const val ATTEMPT_SUCCEEDED = "SUCCEEDED"
        private const val ATTEMPT_FAILED = "FAILED"
        private const val ATTEMPT_DISCARDED = "DISCARDED"
        private const val ENTITLEMENT_FREE = "FREE"
        private const val ENTITLEMENT_UNLIMITED = "UNLIMITED"
        val EXECUTION_WINDOW: Duration = Duration.ofMinutes(15)
        private val ATTEMPT_LEASE: Duration = Duration.ofMinutes(2)
        private val RESULT_TTL: Duration = Duration.ofDays(7)
        private val KEY_TTL: Duration = Duration.ofDays(30)

        fun reservationIdOf(batchId: UUID) = "scan-batch:$batchId"

        fun bytesOf(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

        fun uuidOf(bytes: ByteArray): UUID = ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
    }
}
