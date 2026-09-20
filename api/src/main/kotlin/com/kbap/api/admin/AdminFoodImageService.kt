package com.kbap.api.admin

import com.kbap.api.food.FoodImageBatchSubmitService
import com.kbap.api.food.FoodService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.food.FoodImageJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodImage
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
class AdminFoodImageService(
    private val foodRepository: FoodJpaRepository,
    private val foodImageRepository: FoodImageJpaRepository,
    private val vectorOutboxRepository: FoodVectorOutboxJpaRepository,
    private val foodService: FoodService,
    private val batchSubmitService: FoodImageBatchSubmitService,
    private val imageBatchItemRepository: ImageBatchItemJpaRepository,
    transactionManager: PlatformTransactionManager,
) {
    private val transaction = TransactionTemplate(transactionManager)

    @Transactional(readOnly = true)
    fun getGallery(foodId: Long): AdminFoodImageGalleryResult = galleryOf(foodId)

    @Transactional
    fun setPrimary(foodId: Long, imageId: Long, expectedVersion: Long): AdminFoodImageGalleryResult {
        val food = foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
        if (food.version != expectedVersion) throw BusinessException(ErrorCode.FOOD_VERSION_CONFLICT)

        val target = foodImageRepository.findById(imageId)
            .filter { it.foodId == foodId }
            .orElseThrow { BusinessException(ErrorCode.FOOD_IMAGE_NOT_FOUND) }

        val changed = !target.isPrimary || food.imageRef != target.imageKey
        if (!target.isPrimary) {
            foodImageRepository.demotePrimaryByFoodId(foodId)
            target.isPrimary = true
            foodImageRepository.save(target)
        }
        food.imageRef = target.imageKey
        if (changed) vectorOutboxRepository.enqueueIfAbsent(foodId, FoodVectorOutboxOperation.UPSERT)
        foodRepository.flush()
        return galleryOf(foodId)
    }

    fun regenerateImage(foodId: Long): AdminFoodImageRegenerateResult {
        val claim = transaction.execute {
            val target = foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
            if (imageBatchItemRepository.findFoodIdsInProgress(listOf(foodId)).isNotEmpty()) {
                throw BusinessException(ErrorCode.IMAGE_BATCH_IN_PROGRESS)
            }
            if (!target.isReady() && !target.isFailedRegeneration()) {
                throw BusinessException(ErrorCode.FOOD_STATUS_NOT_READY)
            }
            target.freezePublishedAtIfLegacy()
            target.contentStatus = FoodContentStatus.PENDING_IMAGE
            vectorOutboxRepository.enqueueIfAbsent(foodId, FoodVectorOutboxOperation.DELETE)
            batchSubmitService.claimOne(target)
        }!!
        batchSubmitService.submitClaimed(claim)
        return AdminFoodImageRegenerateResult(
            foodId = foodId,
            contentStatus = claim.foods.single().contentStatus.name,
            batchItemId = claim.itemId,
        )
    }

    private fun Food.isFailedRegeneration(): Boolean =
        contentStatus == FoodContentStatus.PENDING_IMAGE && !imageRef.isNullOrBlank()

    private fun galleryOf(foodId: Long): AdminFoodImageGalleryResult {
        val food = foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
        val items = foodImageRepository.findByFoodIdOrderBySortOrderAscIdAsc(foodId)
            .sortedWith(compareByDescending<FoodImage> { it.isPrimary }.thenBy { it.sortOrder }.thenBy { it.id })
            .map {
                AdminFoodImageGalleryResult.Item(
                    id = it.id,
                    imageKey = it.imageKey,
                    imageUrl = foodService.resolveImageKeyUrl(it.imageKey),
                    isPrimary = it.isPrimary,
                    sortOrder = it.sortOrder,
                    source = it.source.name,
                    createdAt = it.createdAt,
                )
            }
        return AdminFoodImageGalleryResult(
            foodId = food.id,
            version = food.version,
            contentStatus = food.contentStatus.name,
            items = items,
        )
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
    )
}
