package com.kbap.api.reviewbot

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.api.admin.AdminReviewBotApi
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.port.storage.StorageObjectMetadata
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.port.storage.StoredObject
import com.kbap.common.util.LikeWildcards
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime

@Service
class ReviewBotPhotoSyncService internal constructor(
    private val storageObjectStore: StorageObjectStore,
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val accountService: ReviewBotAccountService,
    private val photoPool: ReviewBotPhotoPool,
    private val reviewRepository: ReviewJpaRepository,
    private val reviewService: ReviewService,
    transactionManager: PlatformTransactionManager,
    private val pageSize: Int,
) {
    @Autowired
    constructor(
        storageObjectStore: StorageObjectStore,
        uploadedImageRepository: UploadedImageJpaRepository,
        accountService: ReviewBotAccountService,
        photoPool: ReviewBotPhotoPool,
        reviewRepository: ReviewJpaRepository,
        reviewService: ReviewService,
        transactionManager: PlatformTransactionManager,
    ) : this(storageObjectStore, uploadedImageRepository, accountService, photoPool, reviewRepository, reviewService, transactionManager, PAGE_SIZE)

    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)
    private val objectMapper = jacksonObjectMapper()

    fun sync(force: Boolean = false): ReviewBotPhotoSyncResult {
        val owner = accountService.getBots().minByOrNull { it.id } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val stored = listAll().filter { ReviewBotPhotoPool.keyOfPath(it.path) != null }
        val imagesPerKey = stored.groupingBy { ReviewBotPhotoPool.keyOfPath(it.path)!! }.eachCount().toSortedMap()
        val prefixPattern = LikeWildcards.escape(photoPool.prefix) + "%"
        val activePaths = uploadedImageRepository.findActivePathsLike(prefixPattern).filter { ReviewBotPhotoPool.keyOfPath(it) != null }.toSet()
        val recordedPaths = uploadedImageRepository.findRecordedPathsLikeSince(prefixPattern, LocalDateTime.now().minus(DETECTION_WINDOW))
            .filter { ReviewBotPhotoPool.keyOfPath(it) != null }
            .toSet()
        var registered = 0
        var skipped = 0
        var restored = 0
        stored.chunked(pageSize).forEach { chunk ->
            val chunkPaths = chunk.map { it.path }
            val recorded = chunkPaths.filter { it in recordedPaths }.toSet()
            val toRestore = recorded.filterNot { it in activePaths }
            if (toRestore.isNotEmpty()) restored += transaction.execute { uploadedImageRepository.restoreDeletedByPaths(toRestore) }!!
            for (obj in chunk) {
                if (obj.path in recorded) {
                    skipped++
                    continue
                }
                val metadata = storageObjectStore.head(obj.path) ?: continue
                if (register(owner.id, obj.path, metadata)) registered++ else skipped++
            }
        }
        val storedPaths = stored.map { it.path }.toSet()
        val missingActive = activePaths.filter { it !in storedPaths }.toSet()
        val missingRecorded = recordedPaths.filter { it !in storedPaths }.toSet()
        val suspicious = activePaths.size >= AdminReviewBotApi.REMOVAL_GUARD_MIN_ROWS && missingActive.size * 2 > activePaths.size
        val removalSkipped = stored.isEmpty() || (suspicious && !force)
        val removal = if (removalSkipped) {
            if (missingActive.isNotEmpty()) {
                log.warn(
                    "리뷰 봇 사진 풀 동기화 — 삭제 단계를 건너뜁니다(prefix={} 목록 {} 활성 {} 사라짐 {}) — 빈/잘린 목록일 수 있어 풀을 지우지 않는다. 잘린 목록이 아니면 force=true 로 다시 호출(빈 목록은 항상 건너뜀)",
                    photoPool.prefix, stored.size, activePaths.size, missingActive.size,
                )
            }
            Removal.NONE
        } else {
            if (suspicious) log.warn("리뷰 봇 사진 풀 동기화 — force=true 로 가드를 무시하고 {}건을 지웁니다(활성 {})", missingActive.size, activePaths.size)
            removeMissing(missingActive, missingRecorded)
        }
        skipped -= restored
        log.info(
            "리뷰 봇 사진 풀 동기화 — 등록 {} 복구 {} 건너뜀 {} 삭제 {} (리뷰에서 뗌 {} 리뷰 삭제 {} 실패 {} 못읽음 {}) 키 {}개 소유자 botId={}",
            registered, restored, skipped, removal.removed, removal.detachedReviews, removal.deletedReviews, removal.failedReviews, removal.unparseableReviewIds.size, imagesPerKey.size, owner.id,
        )
        return ReviewBotPhotoSyncResult(
            registeredCount = registered,
            restoredCount = restored,
            skippedCount = skipped,
            removedCount = removal.removed,
            detachedReviewCount = removal.detachedReviews,
            deletedReviewCount = removal.deletedReviews,
            failedReviewCount = removal.failedReviews,
            unparseableReviewIds = removal.unparseableReviewIds,
            detectionFailed = removal.detectionFailed,
            removalSkipped = removalSkipped,
            imagesPerKey = imagesPerKey,
        )
    }

    private fun listAll(): List<StoredObject> {
        val all = mutableListOf<StoredObject>()
        var afterPath: String? = null
        while (true) {
            val page = storageObjectStore.list(photoPool.prefix, afterPath, pageSize)
            if (page.isEmpty()) return all
            all += page
            afterPath = page.last().path
        }
    }

    private fun register(ownerId: Long, path: String, metadata: StorageObjectMetadata): Boolean =
        try {
            transaction.execute {
                uploadedImageRepository.save(UploadedImage(memberId = ownerId, path = path, contentType = metadata.contentType, sizeBytes = metadata.sizeBytes))
            }
            true
        } catch (e: DataIntegrityViolationException) {
            false
        }

    private fun removeMissing(missingActive: Set<String>, missingRecorded: Set<String>): Removal {
        val removed = if (missingActive.isEmpty()) 0 else transaction.execute {
            uploadedImageRepository.findByPathIn(missingActive).onEach { it.delete() }.size
        }!!
        if (missingRecorded.isEmpty()) return Removal(removed, 0, 0, 0, emptyList(), false)
        return runCatching { processReferencingReviews(missingRecorded) }
            .getOrElse { e ->
                log.warn("리뷰 봇 사진 풀 동기화 — 영향 리뷰 탐지·처리 실패(행 삭제 {}건은 반영됨, 다음 동기화가 다시 잡는다)", removed, e)
                Removal(removed, 0, 0, 0, emptyList(), true)
            }
            .let { it.copy(removed = removed) }
    }

    private fun processReferencingReviews(missingRecorded: Set<String>): Removal {
        val ownerIds = photoPool.ownerIds()
        val rows = missingRecorded.chunked(DETECTION_CHUNK)
            .flatMap { chunk -> reviewRepository.findActiveRefsReferencingAny(ownerIds, jsonArrayOf(chunk)) }
            .distinctBy { it.id }
        var detached = 0
        var deleted = 0
        var failed = 0
        val unparseable = mutableListOf<Long>()
        rows.forEach { row ->
            if (parsedRefsOrNull(row.imageRefs) == null) {
                log.warn("리뷰 봇 사진 풀 동기화 — 리뷰 {} 의 image_refs 를 읽을 수 없어 건너뜁니다(문자열 배열이 아님, 데이터 정정 필요)", row.id)
                unparseable += row.id
                return@forEach
            }
            val outcome = runCatching {
                transaction.execute { detachOrDelete(row.id, missingRecorded) }!!
            }.onFailure { log.warn("리뷰 봇 사진 풀 동기화 — 리뷰 {} 의 사라진 사진 처리 실패(다음 동기화가 기록 경로 기준으로 다시 잡는다)", row.id, it) }.getOrNull()
            when (outcome) {
                Outcome.DETACHED -> detached++
                Outcome.DELETED -> deleted++
                else -> failed++
            }
        }
        return Removal(0, detached, deleted, failed, unparseable, false)
    }

    private fun parsedRefsOrNull(imageRefsJson: String): List<String>? =
        runCatching { objectMapper.readTree(imageRefsJson) }.getOrNull()
            ?.takeIf { node -> node.isArray && node.all { it.isTextual } }
            ?.map { it.asText() }

    private fun detachOrDelete(reviewId: Long, missingPaths: Set<String>): Outcome {
        val review = reviewRepository.findById(reviewId).orElse(null) ?: return Outcome.UNCHANGED
        val refs = review.imageRefs.orEmpty()
        if (refs.none { it in missingPaths }) return Outcome.UNCHANGED
        val remaining = refs.filterNot { it in missingPaths }
        return if (remaining.isEmpty()) {
            reviewService.deleteForModeration(review)
            Outcome.DELETED
        } else {
            review.imageRefs = remaining
            Outcome.DETACHED
        }
    }

    private enum class Outcome { DETACHED, DELETED, UNCHANGED }

    private data class Removal(
        val removed: Int,
        val detachedReviews: Int,
        val deletedReviews: Int,
        val failedReviews: Int,
        val unparseableReviewIds: List<Long>,
        val detectionFailed: Boolean,
    ) {
        companion object {
            val NONE = Removal(0, 0, 0, 0, emptyList(), false)
        }
    }

    private fun jsonArrayOf(paths: Collection<String>): String = objectMapper.writeValueAsString(paths.toList())

    companion object {
        private const val PAGE_SIZE = 1000
        private const val DETECTION_CHUNK = 1000
        private val DETECTION_WINDOW: Duration = Duration.ofDays(30)
    }
}

data class ReviewBotPhotoSyncResult(
    val registeredCount: Int,
    val restoredCount: Int,
    val skippedCount: Int,
    val removedCount: Int,
    val detachedReviewCount: Int,
    val deletedReviewCount: Int,
    val failedReviewCount: Int,
    val unparseableReviewIds: List<Long>,
    val detectionFailed: Boolean,
    val removalSkipped: Boolean,
    val imagesPerKey: Map<String, Int>,
)
