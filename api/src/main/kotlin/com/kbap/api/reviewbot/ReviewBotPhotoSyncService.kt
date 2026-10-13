package com.kbap.api.reviewbot

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.port.storage.StorageObjectMetadata
import com.kbap.common.port.storage.StorageObjectStore
import com.kbap.common.port.storage.StoredObject
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class ReviewBotPhotoSyncService(
    private val storageObjectStore: StorageObjectStore,
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val accountService: ReviewBotAccountService,
    private val photoPool: ReviewBotPhotoPool,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val transaction = TransactionTemplate(transactionManager)

    fun sync(): ReviewBotPhotoSyncResult {
        val owner = accountService.getBots().minByOrNull { it.id } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        val stored = listAll().filter { ReviewBotPhotoPool.keyOfPath(it.path) != null }
        val imagesPerKey = stored.groupingBy { ReviewBotPhotoPool.keyOfPath(it.path)!! }.eachCount().toSortedMap()
        var registered = 0
        var skipped = 0
        stored.chunked(PAGE_SIZE).forEach { chunk ->
            val recorded = uploadedImageRepository.findRecordedPathsAnyStatus(chunk.map { it.path }).toSet()
            for (obj in chunk) {
                if (obj.path in recorded) {
                    skipped++
                    continue
                }
                val metadata = storageObjectStore.head(obj.path) ?: continue
                if (register(owner.id, obj.path, metadata)) registered++ else skipped++
            }
        }
        val removed = transaction.execute { removeMissing(stored.map { it.path }.toSet()) }!!
        log.info("리뷰 봇 사진 풀 동기화 — 등록 {} 건너뜀 {} 삭제 {} 키 {}개 소유자 botId={}", registered, skipped, removed, imagesPerKey.size, owner.id)
        return ReviewBotPhotoSyncResult(registered, skipped, removed, imagesPerKey)
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

    private fun removeMissing(storedPaths: Set<String>): Int =
        uploadedImageRepository.findByPathStartingWithOrderByPath(photoPool.prefix)
            .filter { ReviewBotPhotoPool.keyOfPath(it.path) != null && it.path !in storedPaths }
            .onEach { it.delete() }
            .size

    companion object {
        private const val PAGE_SIZE = 1000
    }
}

data class ReviewBotPhotoSyncResult(
    val registeredCount: Int,
    val skippedCount: Int,
    val removedCount: Int,
    val imagesPerKey: Map<String, Int>,
)
