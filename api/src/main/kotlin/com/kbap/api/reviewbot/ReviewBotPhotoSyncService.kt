package com.kbap.api.reviewbot

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.image.UploadedImageJpaRepository
import com.kbap.common.domain.image.model.UploadedImage
import com.kbap.common.port.storage.StorageObjectStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ReviewBotPhotoSyncService(
    private val storageObjectStore: StorageObjectStore,
    private val uploadedImageRepository: UploadedImageJpaRepository,
    private val accountService: ReviewBotAccountService,
    private val photoPool: ReviewBotPhotoPool,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun sync(): ReviewBotPhotoSyncResult {
        val owner = accountService.getBots().minByOrNull { it.id } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        var registered = 0
        var skipped = 0
        val imagesPerKey = sortedMapOf<String, Int>()
        var afterPath: String? = null
        while (true) {
            val page = storageObjectStore.list(photoPool.prefix, afterPath, PAGE_SIZE)
            if (page.isEmpty()) break
            for (stored in page) {
                val key = ReviewBotPhotoPool.keyOfPath(stored.path) ?: continue
                imagesPerKey.merge(key, 1, Int::plus)
                if (uploadedImageRepository.countRecordedAnyStatus(stored.path) > 0) {
                    skipped++
                    continue
                }
                val metadata = storageObjectStore.head(stored.path) ?: continue
                uploadedImageRepository.save(
                    UploadedImage(memberId = owner.id, path = stored.path, contentType = metadata.contentType, sizeBytes = metadata.sizeBytes),
                )
                registered++
            }
            afterPath = page.last().path
            if (page.size < PAGE_SIZE) break
        }
        log.info("리뷰 봇 사진 풀 동기화 — 등록 {} 건너뜀 {} 키 {}개 소유자 botId={}", registered, skipped, imagesPerKey.size, owner.id)
        return ReviewBotPhotoSyncResult(registered, skipped, imagesPerKey)
    }

    companion object {
        private const val PAGE_SIZE = 1000
    }
}

data class ReviewBotPhotoSyncResult(
    val registeredCount: Int,
    val skippedCount: Int,
    val imagesPerKey: Map<String, Int>,
)
