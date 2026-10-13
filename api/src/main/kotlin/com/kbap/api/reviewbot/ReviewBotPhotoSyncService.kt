package com.kbap.api.reviewbot

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
        val activePaths = uploadedImageRepository.findActivePathsLike(LikeWildcards.escape(photoPool.prefix) + "%")
            .filter { ReviewBotPhotoPool.keyOfPath(it) != null }
            .toSet()
        var registered = 0
        var skipped = 0
        var restored = 0
        stored.chunked(pageSize).forEach { chunk ->
            val chunkPaths = chunk.map { it.path }
            val recorded = uploadedImageRepository.findRecordedPathsAnyStatus(chunkPaths).toSet()
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
        val missingPaths = activePaths.filter { it !in storedPaths }.toSet()
        val suspicious = stored.isEmpty() || (activePaths.size >= REMOVAL_GUARD_MIN_ROWS && missingPaths.size * 2 > activePaths.size)
        val removalSkipped = suspicious && !force
        val removal = if (removalSkipped) {
            if (missingPaths.isNotEmpty()) {
                log.warn(
                    "리뷰 봇 사진 풀 동기화 — 삭제 단계를 건너뜁니다(prefix={} 목록 {} 활성 {} 사라짐 {}) — 빈/잘린 목록일 수 있어 풀을 지우지 않는다. 확실하면 force=true 로 다시 호출",
                    photoPool.prefix, stored.size, activePaths.size, missingPaths.size,
                )
            }
            Removal.NONE
        } else {
            if (suspicious) log.warn("리뷰 봇 사진 풀 동기화 — force=true 로 가드를 무시하고 {}건을 지웁니다(활성 {})", missingPaths.size, activePaths.size)
            removeMissing(missingPaths)
        }
        skipped -= restored
        log.info(
            "리뷰 봇 사진 풀 동기화 — 등록 {} 복구 {} 건너뜀 {} 삭제 {} (리뷰에서 뗌 {} 리뷰 삭제 {}) 키 {}개 소유자 botId={}",
            registered, restored, skipped, removal.removed, removal.detachedReviews, removal.deletedReviews, imagesPerKey.size, owner.id,
        )
        return ReviewBotPhotoSyncResult(registered, restored, skipped, removal.removed, removal.detachedReviews, removal.deletedReviews, removalSkipped, imagesPerKey)
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

    private fun removeMissing(missingPaths: Set<String>): Removal {
        if (missingPaths.isEmpty()) return Removal.NONE
        var detached = 0
        var deleted = 0
        val stillReferenced = mutableSetOf<String>()
        reviewRepository.findActiveRefsReferencingAny(photoPool.ownerIds(), jsonArrayOf(missingPaths)).forEach { row ->
            val referencedMissing = missingRefsOf(row.imageRefs, missingPaths)
            val outcome = runCatching {
                transaction.execute { detachOrDelete(row.id, missingPaths) }!!
            }.onFailure { log.warn("리뷰 봇 사진 풀 동기화 — 리뷰 {} 의 사라진 사진 처리 실패(사진 행은 남겨 다음 동기화가 다시 잡는다)", row.id, it) }.getOrNull()
            when (outcome) {
                Outcome.DETACHED -> detached++
                Outcome.DELETED -> deleted++
                else -> stillReferenced += referencedMissing
            }
        }
        val removable = missingPaths - stillReferenced
        val removed = if (removable.isEmpty()) 0 else transaction.execute {
            uploadedImageRepository.findByPathIn(removable).onEach { it.delete() }.size
        }!!
        return Removal(removed, detached, deleted)
    }

    private fun missingRefsOf(imageRefsJson: String, missingPaths: Set<String>): Set<String> =
        runCatching { objectMapper.readTree(imageRefsJson) }.getOrNull()
            ?.takeIf { it.isArray }
            ?.mapNotNull { node -> node.takeIf { it.isTextual }?.asText() }
            ?.filter { it in missingPaths }
            ?.toSet()
            ?: missingPaths

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

    private data class Removal(val removed: Int, val detachedReviews: Int, val deletedReviews: Int) {
        companion object {
            val NONE = Removal(0, 0, 0)
        }
    }

    private fun jsonArrayOf(paths: Collection<String>): String = objectMapper.writeValueAsString(paths.toList())

    companion object {
        private const val PAGE_SIZE = 1000
        const val REMOVAL_GUARD_MIN_ROWS = 10
    }
}

data class ReviewBotPhotoSyncResult(
    val registeredCount: Int,
    val restoredCount: Int,
    val skippedCount: Int,
    val removedCount: Int,
    val detachedReviewCount: Int,
    val deletedReviewCount: Int,
    val removalSkipped: Boolean,
    val imagesPerKey: Map<String, Int>,
)
