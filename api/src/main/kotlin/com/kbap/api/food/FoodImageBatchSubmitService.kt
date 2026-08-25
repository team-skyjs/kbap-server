package com.kbap.api.food

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.ImageBatchStatus
import com.kbap.common.port.llm.FoodImageBatchClient
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

    fun countCandidates(): Int = foodRepository.findImageCandidates().size

    fun submitMissingImages(): FoodImageSubmitResult = submit(foodRepository.findImageCandidates())

    fun submitForFoods(foodIds: Collection<Long>): FoodImageSubmitResult {
        val foods = foodRepository.findAllById(foodIds.toSet())
        if (foods.isEmpty()) throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        if (foods.any { itemRepository.existsByFoodIdAndItemStatus(it.id, ImageBatchItemStatus.PENDING) }) {
            throw BusinessException(ErrorCode.IMAGE_REGENERATION_IN_PROGRESS)
        }
        return submit(foods)
    }

    private fun submit(candidates: List<Food>): FoodImageSubmitResult {
        var submittedBatchCount = 0
        var submittedFoodCount = 0
        val batchIds = mutableListOf<Long>()
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
            batchIds += batch.id
        }
        return FoodImageSubmitResult(submittedBatchCount, submittedFoodCount, batchIds)
    }
}

data class FoodImageSubmitResult(
    val submittedBatchCount: Int,
    val submittedFoodCount: Int,
    val batchIds: List<Long> = emptyList(),
)
