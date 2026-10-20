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
import com.kbap.common.domain.scan.ScanMergedItemJpaRepository
import com.kbap.common.domain.scan.ScanOccurrenceJpaRepository
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.ScanHistorySourceJpaRepository
import com.kbap.common.domain.scan.model.ScanMergedItem
import com.kbap.common.domain.scan.model.ScanOccurrence
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.common.domain.scan.model.ScanHistorySource
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.llm.MenuBoardLine
import com.kbap.common.util.KoreanMenuNameNormalizer
import com.kbap.api.food.FoodService
import java.text.Normalizer
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
    val objectKey: String,
    val batchId: UUID,
)

data class ScanBatchPhotoInput(val photoId: UUID, val imagePath: String)

data class ScanBatchOrderPlan(
    val operationId: Long,
    val resultRevision: Long,
    val photos: List<ScanOperationPhoto>,
    val itemOccurrenceIds: List<List<Long>>,
)

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
    private val mergedItems: ScanMergedItemJpaRepository,
    private val occurrences: ScanOccurrenceJpaRepository,
    private val histories: ScanHistoryJpaRepository,
    private val historySources: ScanHistorySourceJpaRepository,
    private val foodService: FoodService,
    private val foods: FoodJpaRepository,
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
        operations.findByMemberIdAndKeyNamespaceAndRequestKey(memberId, NAMESPACE, bytesOf(batchId))?.let { existing ->
            if (!existing.manifestHash.contentEquals(manifestHash)) throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
            val now = LocalDateTime.now()
            if (!now.isBefore(existing.resultExpiresAt)) throw BusinessException(ErrorCode.SCAN_BATCH_RESULT_EXPIRED)
            return viewOf(existing, now)
        }
        val images = uploads.findByPathIn(inputs.map { it.imagePath }).associateBy { it.path }
        val verified = inputs.map { input ->
            images[input.imagePath]?.takeIf { it.isOwnedBy(memberId) && it.path.contains(SCAN_SEGMENT) }
                ?: throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)
        }
        reserve(memberId, batchId, memberService.getMember(memberId), EXECUTION_WINDOW)
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

    @Transactional(readOnly = true)
    fun planOrder(memberId: Long, batchId: String, items: List<Pair<String?, Long>>): ScanBatchOrderPlan {
        requireEnabled()
        val operation = parse(batchId)?.let { operations.findByMemberIdAndKeyNamespaceAndRequestKey(memberId, NAMESPACE, bytesOf(it)) }
            ?: throw BusinessException(ErrorCode.SCAN_BATCH_NOT_FOUND)
        if (operation.usageRecordedAt == null) throw BusinessException(ErrorCode.INVALID_REQUEST)
        val itemsByPublicId = mergedItems.findByOperationId(operation.id).associateBy { uuidOf(it.publicId).toString() }
        val sourcesByItem = occurrences.findByOperationId(operation.id).groupBy { it.mergedItemId }
        val itemOccurrenceIds = items.map { (mergedItemId, foodId) ->
            val item = mergedItemId?.lowercase()?.let(itemsByPublicId::get) ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
            val sources = sourcesByItem[item.id].orEmpty()
            if (sources.mapNotNull { it.matchedFoodId }.distinct().singleOrNull() != foodId) throw BusinessException(ErrorCode.INVALID_REQUEST)
            sources.map { it.id }
        }
        return ScanBatchOrderPlan(operation.id, operation.resultRevision, photos.findByOperationIdOrderBySelectionIndex(operation.id), itemOccurrenceIds)
    }

    @Transactional
    fun cancelBatch(memberId: Long, batchId: String): ScanBatchResponse {
        requireEnabled()
        val now = LocalDateTime.now()
        val operation = parse(batchId)?.let { operations.findForUpdate(memberId, NAMESPACE, bytesOf(it)) }
            ?.takeIf { now.isBefore(it.resultExpiresAt) }
            ?: throw BusinessException(ErrorCode.SCAN_BATCH_NOT_FOUND)
        if (operation.lifecycle == ACTIVE) {
            if (now.isBefore(operation.expiresAt)) {
                operation.lifecycle = CANCELLED
                photos.findAllForUpdate(operation.id).forEach(ScanOperationPhoto::cancel)
            }
            if (operation.reservationState == RESERVATION_HELD) releaseAfterCommit(operation)
        }
        return viewOf(operation, now)
    }

    @Transactional
    fun retryPhoto(memberId: Long, batchId: String, selectionIndex: Int): UUID {
        requireEnabled()
        val key = parse(batchId) ?: throw BusinessException(ErrorCode.SCAN_BATCH_NOT_FOUND)
        operations.findByMemberIdAndKeyNamespaceAndRequestKey(memberId, NAMESPACE, bytesOf(key)) ?: throw BusinessException(ErrorCode.SCAN_BATCH_NOT_FOUND)
        prepare(memberId, key, selectionIndex, LocalDateTime.now()) ?: throw BusinessException(ErrorCode.SCAN_BATCH_PHOTO_NOT_RETRYABLE)
        return key
    }

    @Transactional
    fun startAttempt(memberId: Long, batchId: UUID, selectionIndex: Int): ScanBatchClaim? = claimNext(memberId, batchId, selectionIndex)

    private fun prepare(memberId: Long, batchId: UUID, selectionIndex: Int, now: LocalDateTime): Pair<ScanOperation, ScanOperationPhoto>? {
        if (!batchSwitch.enabled) return null
        val operation = operations.findForUpdate(memberId, NAMESPACE, bytesOf(batchId)) ?: return null
        if (operation.lifecycle != ACTIVE || !now.isBefore(operation.expiresAt)) return null
        val photo = photos.findAllForUpdate(operation.id).singleOrNull { it.selectionIndex == selectionIndex } ?: return null
        if (!photo.isRunnableAt(now)) return null
        if (attempts.countUnchargedSince(memberId, now.minus(ATTEMPT_LIMIT_WINDOW)) >= DAILY_UNCHARGED_ATTEMPT_LIMIT) {
            throw BusinessException(ErrorCode.SCAN_BATCH_ATTEMPT_LIMIT)
        }
        if (operation.usageRecordedAt == null && operation.reservationState != RESERVATION_HELD) {
            reserve(memberId, batchId, memberService.getMember(memberId), Duration.between(now, operation.expiresAt))
            operation.reservationState = RESERVATION_HELD
        }
        return operation to photo
    }

    private fun claimNext(memberId: Long, batchId: UUID, selectionIndex: Int): ScanBatchClaim? {
        val now = LocalDateTime.now()
        val (operation, photo) = prepare(memberId, batchId, selectionIndex, now) ?: return null
        val callLimit = attempts.lockGlobalCallLimit() ?: DEFAULT_GLOBAL_CALL_LIMIT
        if (attempts.countLiveCallSlots(now) >= callLimit || attempts.countLiveCallSlotsOfMember(memberId, now) >= PER_MEMBER_CALL_SLOTS) {
            throw ScanBatchSlotsBusyException()
        }
        val token = bytesOf(UUID.randomUUID())
        val leaseUntil = minOf(now.plus(ATTEMPT_LEASE), operation.expiresAt)
        photo.claim(token, leaseUntil)
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
                deadlineAt = leaseUntil,
                leaseUntil = leaseUntil,
                callSlotHeld = true,
            ),
        )
        return ScanBatchClaim(memberId, operation.id, photo.id, attempt.id, photo.attemptNo, token, photo.objectKey, batchId)
    }

    @Transactional
    fun recordSuccess(claim: ScanBatchClaim, lines: List<MenuBoardLine>): Boolean {
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
        results.save(ScanOperationResult(operation.id, photo.id, attempt.id, attempt.attemptNo, 1, mapOf("lines" to lines.map(::documentOf))))
        storeLines(operation, photo, lines)
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

    private fun storeLines(operation: ScanOperation, photo: ScanOperationPhoto, lines: List<MenuBoardLine>) {
        val keys = lines.map { KoreanMenuNameNormalizer.matchKey(it.koreanName) }
        val namesByKey = lines.zip(keys).filter { (_, key) -> key.isNotBlank() }.associate { (line, key) -> key to line.koreanName }
        val known = foodService.getFoodsByKoreanNames(namesByKey.keys)
        val foodsByKey = known + foodService.createIncomplete(namesByKey - known.keys)
        lines.forEachIndexed { rowIndex, line ->
            val food = foodsByKey[keys[rowIndex]]
            val mergeKey = line.priceKrw?.let { price -> sha256("${normalized(line.rawName)}|$price|${normalized(line.option).orEmpty()}") }
            val existing = mergeKey?.let { mergedItems.findByOperationIdAndMergeKey(operation.id, it) }
            val merged = existing ?: mergedItems.save(ScanMergedItem(operation.id, bytesOf(UUID.randomUUID()), mergeKey, documentOf(line)))
            val occurrence = occurrences.save(
                ScanOccurrence(
                    operationId = operation.id,
                    photoRowId = photo.id,
                    occurrenceKey = bytesOf(UUID.randomUUID()),
                    rowIndex = rowIndex,
                    rawDocument = documentOf(line),
                    comparisonDocument = mapOf("name" to normalized(line.rawName), "priceKrw" to line.priceKrw, "option" to normalized(line.option)),
                    matchedFoodId = food?.id,
                    mergedItemId = merged.id,
                ),
            )
            if (existing == null) {
                val history = histories.save(ScanHistory.record(operation.memberId, line.priceKrw, food?.id))
                historySources.save(ScanHistorySource(history.id, occurrence.id))
            }
        }
    }

    private fun lockClaim(claim: ScanBatchClaim): Triple<ScanOperation, ScanOperationPhoto, ScanAttempt>? {
        val now = LocalDateTime.now()
        val operation = operations.findByIdForUpdate(claim.operationId) ?: return null
        val photo = photos.findAllForUpdate(operation.id).singleOrNull { it.id == claim.photoRowId } ?: return null
        val attempt = attempts.findById(claim.attemptId).orElse(null) ?: return null
        if (operation.lifecycle != ACTIVE || !photo.isClaimedBy(claim.claimToken)) {
            if (attempt.state == ATTEMPT_RUNNING) attempt.finish(ATTEMPT_DISCARDED, null, now)
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

    private fun reserve(memberId: Long, batchId: UUID, member: Member, ttl: Duration) {
        val reservationId = reservationIdOf(batchId)
        when (reservationStore.reserve(memberId, reservationId, member.scanCount, member.scanLimit(), ttl)) {
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

    private fun viewOf(operation: ScanOperation, now: LocalDateTime): ScanBatchResponse {
        val batchPhotos = photos.findByOperationIdOrderBySelectionIndex(operation.id)
        return ScanBatchResponse.of(operation, uuidOf(operation.requestKey), batchPhotos, now, itemsOf(operation, batchPhotos))
    }

    private fun itemsOf(operation: ScanOperation, batchPhotos: List<ScanOperationPhoto>): List<ScanBatchItemResponse> {
        val rows = occurrences.findByOperationId(operation.id)
        if (rows.isEmpty()) return emptyList()
        val photosById = batchPhotos.associateBy { it.id }
        val foodsById = foods.findAllById(rows.mapNotNull { it.matchedFoodId }.toSet()).associateBy { it.id }
        val avoided = memberService.getMemberOrNull(operation.memberId)?.profile?.avoidance()?.codeNames.orEmpty()
        val language = LanguageCode.from(operation.initialLanguage)
        val rowsByItem = rows.groupBy { it.mergedItemId }
        return mergedItems.findByOperationId(operation.id)
            .mapNotNull { item -> rowsByItem[item.id]?.sortedWith(compareBy({ photosById.getValue(it.photoRowId).selectionIndex }, { it.rowIndex }))?.let { item to it } }
            .sortedWith(compareBy({ (_, sources) -> photosById.getValue(sources.first().photoRowId).selectionIndex }, { (_, sources) -> sources.first().rowIndex }))
            .map { (item, sources) ->
                val foodId = sources.mapNotNull { it.matchedFoodId }.distinct().singleOrNull()
                val ready = foodId?.let(foodsById::get)?.takeIf { it.isReady() }
                ScanBatchItemResponse.of(item, foodId, ready, ready?.overallRisk(avoided), language, sources.map { photosById.getValue(it.photoRowId) to it.rowIndex })
            }
    }

    private fun documentOf(line: MenuBoardLine): Map<String, Any?> =
        mapOf("rawName" to line.rawName, "koreanName" to line.koreanName, "priceKrw" to line.priceKrw, "option" to line.option)

    private fun normalized(text: String?): String? =
        text?.let { Normalizer.normalize(it, Normalizer.Form.NFKC).trim().replace(WHITESPACE, " ") }?.ifBlank { null }

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
        const val DAILY_UNCHARGED_ATTEMPT_LIMIT = 30
        private const val DEFAULT_GLOBAL_CALL_LIMIT = 4
        private const val PER_MEMBER_CALL_SLOTS = 3
        private val ATTEMPT_LIMIT_WINDOW: Duration = Duration.ofHours(24)
        private val ATTEMPT_LEASE: Duration = Duration.ofMinutes(2)
        private val RESULT_TTL: Duration = Duration.ofDays(7)
        private val KEY_TTL: Duration = Duration.ofDays(30)
        private val WHITESPACE = Regex("\\s+")

        fun reservationIdOf(batchId: UUID) = "scan-batch:$batchId"

        fun bytesOf(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()

        fun uuidOf(bytes: ByteArray): UUID = ByteBuffer.wrap(bytes).let { UUID(it.long, it.long) }
    }
}
