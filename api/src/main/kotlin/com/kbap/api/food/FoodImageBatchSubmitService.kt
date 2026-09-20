package com.kbap.api.food

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.llm.FoodImageBatchClient
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.ImageBatchStatus
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class FoodImageBatchSubmitService(
    private val foodRepository: FoodJpaRepository,
    private val batchRepository: ImageBatchJpaRepository,
    private val itemRepository: ImageBatchItemJpaRepository,
    private val client: FoodImageBatchClient,
    private val properties: FoodImageProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val metaTransaction = TransactionTemplate(transactionManager)

    fun submitMissingImages(): FoodImageSubmitResult = submit(foodRepository.findImageCandidates(), emptyList())

    fun submitForFoods(foodIds: List<Long>?): FoodImageSubmitResult {
        if (foodIds == null) return submitMissingImages()
        if (foodIds.isEmpty()) return FoodImageSubmitResult(0, 0, emptyList())
        val inProgress = itemRepository.findFoodIdsInProgress(foodIds).toSet()
        val targets = foodRepository.findImageCandidatesByIdIn(foodIds - inProgress)
        return submit(targets, foodIds.filter { it in inProgress })
    }

    fun claimOne(food: com.kbap.common.domain.food.model.Food): FoodImageBatchClaim {
        if (itemRepository.findFoodIdsInProgress(listOf(food.id)).isNotEmpty()) {
            throw BusinessException(ErrorCode.IMAGE_BATCH_IN_PROGRESS)
        }
        val batch = claim(listOf(food))
        val itemId = itemRepository.findFoodIdsInProgressItemId(food.id)
            ?: throw BusinessException(ErrorCode.IMAGE_BATCH_IN_PROGRESS)
        return FoodImageBatchClaim(batch = batch, foods = listOf(food), itemId = itemId)
    }

    fun submitClaimed(claim: FoodImageBatchClaim) {
        submitToClient(claim.batch, claim.foods)
    }

    private fun claim(foods: List<com.kbap.common.domain.food.model.Food>): ImageBatch =
        metaTransaction.execute {
            val claimed = batchRepository.save(
                ImageBatch(promptVersion = FoodImageProperties.PROMPT_VERSION, model = properties.model),
            )
            itemRepository.saveAll(foods.map { ImageBatchItem(batchId = claimed.id, foodId = it.id) })
            claimed
        }!!

    private fun submitToClient(batch: ImageBatch, foods: List<com.kbap.common.domain.food.model.Food>) {
        try {
            val entries = foods.map {
                FoodImageBatchClient.Entry(customId = it.id.toString(), prompt = properties.promptFor(it.displayName(LanguageCode.KO)))
            }
            val openaiBatchId = client.submit(entries)
            metaTransaction.executeWithoutResult {
                batchRepository.save(batch.apply { markSubmitted(openaiBatchId) })
            }
        } catch (e: Exception) {
            metaTransaction.executeWithoutResult {
                itemRepository.findByBatchIdAndItemStatus(batch.id, ImageBatchItemStatus.PENDING)
                    .forEach { item -> itemRepository.save(item.apply { fail("제출 실패: ${e.message}") }) }
                batchRepository.save(batch.apply { close(ImageBatchStatus.FAILED) })
            }
            throw e
        }
    }

    private fun submit(candidates: List<com.kbap.common.domain.food.model.Food>, skipped: List<Long>): FoodImageSubmitResult {
        var submittedBatchCount = 0
        var submittedFoodCount = 0
        candidates.chunked(properties.batchSize).forEach { chunk ->
            val batch = try {
                claim(chunk)
            } catch (e: DataIntegrityViolationException) {
                log.warn("이미지 제출 선점 경합 — 청크 스킵 foodIds={}", chunk.map { it.id }, e)
                return@forEach
            }
            submitToClient(batch, chunk)
            submittedBatchCount++
            submittedFoodCount += chunk.size
        }
        return FoodImageSubmitResult(submittedBatchCount, submittedFoodCount, skipped)
    }
}

data class FoodImageBatchClaim(
    val batch: ImageBatch,
    val foods: List<com.kbap.common.domain.food.model.Food>,
    val itemId: Long,
)

data class FoodImageSubmitResult(
    val submittedBatchCount: Int,
    val submittedFoodCount: Int,
    val skippedInProgress: List<Long> = emptyList(),
)
