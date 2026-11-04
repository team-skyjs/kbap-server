package com.kbap.api.admin

import com.kbap.api.food.FoodImageBatchClaim
import com.kbap.api.food.FoodImageBatchSubmitService
import com.kbap.api.food.FoodService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodContentOutboxJpaRepository
import com.kbap.common.domain.food.FoodImageJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodImage
import com.kbap.common.domain.food.model.FoodImageSource
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.model.FoodVectorOutboxStatus
import com.kbap.common.domain.food.model.RegenerationIntent
import com.kbap.common.port.storage.PresignedUpload
import com.kbap.common.port.storage.PresignedUploadPort
import com.kbap.common.port.storage.StorageObjectStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.Base64

@Service
class AdminFoodImageService(
    private val foodRepository: FoodJpaRepository,
    private val foodImageRepository: FoodImageJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
    private val contentOutboxRepository: FoodContentOutboxJpaRepository,
    private val foodService: FoodService,
    private val batchSubmitService: FoodImageBatchSubmitService,
    private val imageBatchItemRepository: ImageBatchItemJpaRepository,
    private val regenerationStateResolver: RegenerationStateResolver,
    private val uploads: PresignedUploadPort,
    private val store: StorageObjectStore,
    transactionManager: PlatformTransactionManager,
    @Value("\${kbap.storage.key-prefix:}") private val keyPrefix: String,
) {
    private val transaction = TransactionTemplate(transactionManager)

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun getGallery(foodId: Long): AdminFoodImageGalleryResult = galleryOf(foodId)

    @Transactional
    fun setPrimary(foodId: Long, imageId: Long, expectedVersion: Long): AdminFoodImageGalleryResult {
        val food = foodRepository.findByIdForUpdate(foodId) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        val target = foodImageRepository.findById(imageId)
            .filter { it.foodId == foodId }
            .orElseThrow { BusinessException(ErrorCode.FOOD_IMAGE_NOT_FOUND) }

        val changed = !target.isPrimary || food.imageRef != target.imageKey
        if (changed && food.version != expectedVersion) throw BusinessException(ErrorCode.FOOD_VERSION_CONFLICT)
        if (!target.isPrimary) {
            foodImageRepository.demotePrimaryByFoodId(foodId)
            target.isPrimary = true
            foodImageRepository.save(target)
        }
        food.imageRef = target.imageKey
        if (changed) vectorOutboxRepository.enqueue(foodId, FoodVectorOutboxOperation.UPSERT)
        foodRepository.flush()
        return galleryOf(foodId)
    }

    fun presignPhoto(foodId: Long, contentType: String, contentLength: Long, contentSha256: String): PresignedUpload {
        val extension = PHOTO_EXTENSIONS[contentType] ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        if (contentLength > PHOTO_MAX_BYTES) throw BusinessException(ErrorCode.IMAGE_TOO_LARGE)
        val sha256 = contentSha256.lowercase().takeIf { SHA256_HEX.matches(it) } ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        if (!foodRepository.existsById(foodId)) throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        val checksum = Base64.getEncoder().encodeToString(sha256.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        return uploads.issue(photoKeyBaseOf(foodId) + sha256.take(PHOTO_HASH_LENGTH) + "." + extension, contentType, contentLength, PHOTO_UPLOAD_TTL, checksum)
    }

    fun confirmPhoto(foodId: Long, key: String, setPrimary: Boolean): AdminFoodImageGalleryResult {
        val keyBase = photoKeyBaseOf(foodId)
        val (hash, extension) = key.takeIf { it.startsWith(keyBase) }?.removePrefix(keyBase)?.let(PHOTO_KEY_PART::matchEntire)?.destructured
            ?: throw BusinessException(ErrorCode.INVALID_REQUEST)
        if (!foodRepository.existsById(foodId)) throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        runCatching { store.head(key) }.getOrNull()
            ?.takeIf { PHOTO_EXTENSIONS[it.contentType] == extension && it.sizeBytes in 1..PHOTO_MAX_BYTES && it.sha256Hex?.take(PHOTO_HASH_LENGTH) == hash }
            ?: throw BusinessException(ErrorCode.FOOD_PHOTO_NOT_UPLOADED)
        return transaction.execute {
            val food = foodRepository.findByIdForUpdate(foodId) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
            val image = foodImageRepository.findByFoodIdAndImageKey(foodId, key)
                ?: foodImageRepository.save(
                    FoodImage(
                        foodId = foodId,
                        imageKey = key,
                        source = FoodImageSource.UPLOADED,
                        sortOrder = (foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(foodId).maxOfOrNull { it.sortOrder } ?: -1) + 1,
                    ),
                )
            if (setPrimary && (!image.isPrimary || food.imageRef != key)) {
                if (!image.isPrimary) {
                    foodImageRepository.findByFoodIdAndIsPrimaryTrue(foodId)?.isPrimary = false
                    foodImageRepository.flush()
                    image.isPrimary = true
                }
                food.imageRef = key
                vectorOutboxRepository.enqueue(foodId, FoodVectorOutboxOperation.UPSERT)
            }
            foodRepository.flush()
            galleryOf(foodId)
        }!!
    }

    private fun photoKeyBaseOf(foodId: Long): String =
        listOf(keyPrefix.trim('/'), "images/foods/$foodId/").filter { it.isNotEmpty() }.joinToString("/")

    fun regenerateImage(foodId: Long, intent: RegenerationIntent?, reason: String?): AdminFoodImageRegenerateResult {
        val claim = claimForRegeneration(foodId, intent, reason)
        batchSubmitService.submitClaimed(claim)
        return AdminFoodImageRegenerateResult(
            foodId = foodId,
            contentStatus = claim.foods.single().contentStatus.name,
            batchItemId = claim.itemId,
        )
    }

    fun generateAdditionalImage(foodId: Long): AdminFoodImageRegenerateResult {
        val claim = try {
            transaction.execute {
                val target = foodRepository.findByIdForUpdate(foodId) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
                if (!target.isReady()) throw BusinessException(ErrorCode.FOOD_STATUS_NOT_READY)
                if (imageBatchItemRepository.findFoodIdsInProgress(listOf(foodId)).isNotEmpty()) {
                    throw BusinessException(additionalConflictCode(foodId))
                }
                batchSubmitService.claimOne(target, RegenerationIntent.ADDITIONAL, null)
            }!!
        } catch (e: BusinessException) {
            throw if (e.errorCode == ErrorCode.IMAGE_BATCH_IN_PROGRESS) BusinessException(additionalConflictCode(foodId)) else e
        } catch (e: RuntimeException) {
            throw if (imageBatchItemRepository.findFoodIdsInProgress(listOf(foodId)).isNotEmpty()) BusinessException(additionalConflictCode(foodId)) else e
        }
        batchSubmitService.submitClaimed(claim)
        return AdminFoodImageRegenerateResult(
            foodId = foodId,
            contentStatus = claim.foods.single().contentStatus.name,
            batchItemId = claim.itemId,
        )
    }

    private fun additionalConflictCode(foodId: Long): ErrorCode =
        if (regenerationStateResolver.isAdditionalInProgress(foodId)) ErrorCode.FOOD_ADDITIONAL_IMAGE_IN_PROGRESS else ErrorCode.FOOD_STATUS_NOT_READY

    private fun claimForRegeneration(foodId: Long, intent: RegenerationIntent?, reason: String?): FoodImageBatchClaim = try {
        transaction.execute {
            val target = foodRepository.findByIdForUpdate(foodId) ?: throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
            if (imageBatchItemRepository.findFoodIdsInProgress(listOf(foodId)).isNotEmpty()) {
                throw BusinessException(ErrorCode.IMAGE_BATCH_IN_PROGRESS)
            }
            val wrongImageOnHeldFood = intent == RegenerationIntent.WRONG_IMAGE && target.isWrongImageRegenerable()
            if (!target.isReady() && !target.isFailedRegeneration() && !wrongImageOnHeldFood) {
                throw BusinessException(ErrorCode.FOOD_STATUS_NOT_READY)
            }
            if (contentOutboxRepository.findInFlightRequest(foodId) != null) {
                throw BusinessException(ErrorCode.FOOD_CONTENT_AND_IMAGE_JOBS_CONFLICT)
            }
            if (intent == RegenerationIntent.REPLACE_BETTER && !target.isReady()) {
                throw BusinessException(ErrorCode.FOOD_STATUS_NOT_READY)
            }
            target.freezePublishedAtIfLegacy()
            if (wrongImageOnHeldFood) target.reopenRejectedForImageRegeneration()
            target.contentStatus = FoodContentStatus.PENDING_IMAGE
            cancelPendingVectorOutboxes(foodId, FoodVectorOutboxOperation.UPSERT)
            vectorOutboxRepository.enqueue(foodId, FoodVectorOutboxOperation.DELETE)
            batchSubmitService.claimOne(target, intent, reason)
        }!!
    } catch (e: BusinessException) {
        throw e
    } catch (e: RuntimeException) {
        throw contentionOr(e, foodId)
    }

    private fun contentionOr(cause: RuntimeException, foodId: Long): RuntimeException =
        if (imageBatchItemRepository.findFoodIdsInProgress(listOf(foodId)).isNotEmpty()) {
            log.warn("재생성 선점 경합 — 다른 요청이 먼저 선점했다 foodId={}", foodId, cause)
            BusinessException(ErrorCode.IMAGE_BATCH_IN_PROGRESS)
        } else {
            cause
        }

    private fun cancelPendingVectorOutboxes(foodId: Long, operation: FoodVectorOutboxOperation) {
        vectorOutboxRepository
            .findByFoodIdAndOperationAndOutboxStatus(foodId, operation, FoodVectorOutboxStatus.PENDING)
            .forEach { it.delete() }
    }

    private fun galleryOf(foodId: Long): AdminFoodImageGalleryResult {
        val food = foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
        val images = foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(foodId)
            .sortedWith(compareByDescending<FoodImage> { it.isPrimary }.thenBy { it.sortOrder }.thenBy { it.id })
        val generations = regenerationStateResolver.generationsOf(foodId, images.map { it.imageKey })
        val items = images
            .map {
                AdminFoodImageGalleryResult.Item(
                    id = it.id,
                    imageKey = it.imageKey,
                    imageUrl = foodService.resolveImageKeyUrl(it.imageKey),
                    isPrimary = it.isPrimary,
                    sortOrder = it.sortOrder,
                    source = it.source.name,
                    createdAt = it.createdAt,
                    generation = generations[it.imageKey],
                )
            }
        return AdminFoodImageGalleryResult(
            foodId = food.id,
            version = food.version,
            contentStatus = food.contentStatus.name,
            regeneration = regenerationStateResolver.of(food.id),
            additionalInProgress = regenerationStateResolver.isAdditionalInProgress(food.id),
            items = items,
        )
    }
    companion object {
        const val PHOTO_MAX_BYTES = 5L * 1024 * 1024
        private val PHOTO_EXTENSIONS = mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp")
        private val PHOTO_UPLOAD_TTL = Duration.ofMinutes(10)
        private const val PHOTO_HASH_LENGTH = 16
        private val PHOTO_KEY_PART = Regex("([0-9a-f]{16})\\.(png|jpg|webp)")
        private val SHA256_HEX = Regex("[0-9a-f]{64}")
    }
}

data class AdminFoodImageRegenerateResult(
    val foodId: Long,
    val contentStatus: String,
    val batchItemId: Long,
)

data class AdminFoodImageGalleryResult(
    val foodId: Long,
    val version: Long,
    val contentStatus: String,
    val regeneration: AdminRegenerationStateResponse?,
    val additionalInProgress: Boolean,
    val items: List<Item>,
) {
    data class Item(
        val id: Long,
        val imageKey: String,
        val imageUrl: String?,
        val isPrimary: Boolean,
        val sortOrder: Int,
        val source: String,
        val createdAt: java.time.LocalDateTime,
        val generation: AdminImageGenerationResponse?,
    )

}
