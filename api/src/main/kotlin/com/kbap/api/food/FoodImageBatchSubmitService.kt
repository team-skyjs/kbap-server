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

    fun submitForFoods(foodIds: List<Long>): FoodImageSubmitResult {
        if (foodIds.isEmpty()) return submitMissingImages()
        val inProgress = itemRepository.findFoodIdsInProgress(foodIds).toSet()
        val targets = foodRepository.findByIdIn(foodIds - inProgress)
        return submit(targets, foodIds.filter { it in inProgress })
    }

    fun submitOne(foodId: Long): Long {
        if (itemRepository.findFoodIdsInProgress(listOf(foodId)).isNotEmpty()) {
            throw BusinessException(ErrorCode.IMAGE_BATCH_IN_PROGRESS)
        }
        val food = foodRepository.findById(foodId).orElseThrow { BusinessException(ErrorCode.FOOD_NOT_FOUND) }
        submit(listOf(food), emptyList())
        return itemRepository.findFoodIdsInProgressItemId(foodId)
            ?: throw BusinessException(ErrorCode.IMAGE_BATCH_IN_PROGRESS)
    }

    private fun submit(candidates: List<com.kbap.common.domain.food.model.Food>, skipped: List<Long>): FoodImageSubmitResult {
        var submittedBatchCount = 0
        var submittedFoodCount = 0
        candidates.chunked(properties.batchSize).forEach { chunk ->
            val batch = try {
                metaTransaction.execute {
                    val claimed = batchRepository.save(
                        ImageBatch(promptVersion = FoodImageProperties.PROMPT_VERSION, model = properties.model),
                    )
                    itemRepository.saveAll(chunk.map { ImageBatchItem(batchId = claimed.id, foodId = it.id) })
                    claimed
                }!!
            } catch (e: DataIntegrityViolationException) {
                log.warn("이미지 제출 선점 경합 — 청크 스킵 foodIds={}", chunk.map { it.id }, e)
                return@forEach
            }
            try {
                val entries = chunk.map {
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
            submittedBatchCount++
            submittedFoodCount += chunk.size
        }
        return FoodImageSubmitResult(submittedBatchCount, submittedFoodCount, skipped)
    }
}

data class FoodImageSubmitResult(
    val submittedBatchCount: Int,
    val submittedFoodCount: Int,
    val skippedInProgress: List<Long> = emptyList(),
)
