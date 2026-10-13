package com.kbap.api.reviewbot

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.api.review.ReviewService
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.domain.review.ReviewJpaRepository
import com.kbap.common.port.storage.StorageObjectMetadata
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.port.storage.StoredObject
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

    fun sync(): ReviewBotPhotoSyncResult {
        val owner = accountService.getBots().minByOrNull { it.id } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val stored = listAll().filter { ReviewBotPhotoPool.keyOfPath(it.path) != null }
        val imagesPerKey = stored.groupingBy { ReviewBotPhotoPool.keyOfPath(it.path)!! }.eachCount().toSortedMap()
        var registered = 0
        var skipped = 0
        var restored = 0
        stored.chunked(pageSize).forEach { chunk ->
            val chunkPaths = chunk.map { it.path }
            restored += transaction.execute { uploadedImageRepository.restoreDeletedByPaths(chunkPaths) }!!
            val recorded = uploadedImageRepository.findRecordedPathsAnyStatus(chunkPaths).toSet()
            for (obj in chunk) {
                if (obj.path in recorded) {
                    skipped++
                    continue
                }
                val metadata = storageObjectStore.head(obj.path) ?: continue
                if (register(owner.id, obj.path, metadata)) registered++ else skipped++
            }
        }
        val removalSkipped = stored.isEmpty()
        val removal = if (removalSkipped) {
            log.warn("리뷰 봇 사진 풀 동기화 — 스토리지 목록이 비어 삭제 단계를 건너뜁니다(prefix={}) — 일시 장애일 수 있어 풀을 지우지 않는다", photoPool.prefix)
            Removal.NONE
        } else {
            transaction.execute { removeMissing(stored.map { it.path }.toSet()) }!!
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
            val page = storageObjectStore.list(photoPool.prefix, afterPath, PAGE_SIZE)
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

    private fun removeMissing(storedPaths: Set<String>): Removal {
        val removedPaths = uploadedImageRepository.findByPathStartingWithOrderByPath(photoPool.prefix)
            .filter { ReviewBotPhotoPool.keyOfPath(it.path) != null && it.path !in storedPaths }
            .onEach { it.delete() }
            .map { it.path }
            .toSet()
        if (removedPaths.isEmpty()) return Removal.NONE
        var detached = 0
        var deleted = 0
        reviewRepository.findByMemberIdInAndImageRefsIsNotNull(photoPool.ownerIds()).forEach { review ->
            val refs = review.imageRefs.orEmpty()
            if (refs.none { it in removedPaths }) return@forEach
            val remaining = refs.filterNot { it in removedPaths }
            if (remaining.isEmpty()) {
                reviewService.deleteForModeration(review)
                deleted++
            } else {
                review.imageRefs = remaining
                detached++
            }
        }
        return Removal(removedPaths.size, detached, deleted)
    }

    private data class Removal(val removed: Int, val detachedReviews: Int, val deletedReviews: Int) {
        companion object {
            val NONE = Removal(0, 0, 0)
        }
    }

    companion object {
        private const val PAGE_SIZE = 1000
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
