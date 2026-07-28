package com.kbap.application.foodimage

import com.kbap.common.core.food.FoodImageBatchClient
import com.kbap.common.core.llm.LlmCallCostIncurred
import com.kbap.common.core.storage.StorageObjectStore
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.ImageBatchItemJpaRepository
import com.kbap.common.domain.food.ImageBatchJpaRepository
import com.kbap.common.domain.food.model.ImageBatch
import com.kbap.common.domain.food.model.ImageBatchItem
import com.kbap.common.domain.food.model.ImageBatchItemStatus
import com.kbap.common.domain.food.model.ImageBatchStatus
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

@Service
class FoodImageBatchCollectService(
    private val batchRepository: ImageBatchJpaRepository,
    private val itemRepository: ImageBatchItemJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val client: FoodImageBatchClient,
    private val storageObjectStore: StorageObjectStore,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: FoodImageProperties,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val itemTransaction = TransactionTemplate(transactionManager)

    fun collectSubmitted() {
        recoverStaleSubmitting()
        batchRepository.findByBatchStatus(ImageBatchStatus.SUBMITTED).forEach { batch ->
            runCatching { collect(batch) }
                .onFailure { log.error("이미지 배치 회수 실패 — 다음 틱에 재시도 batchId={}", batch.id, it) }
        }
    }

    private fun recoverStaleSubmitting() {
        val lease = LocalDateTime.now().minusHours(STALE_SUBMITTING_HOURS)
        batchRepository.findByBatchStatus(ImageBatchStatus.SUBMITTING)
            .filter { it.submittedAt.isBefore(lease) }
            .forEach { batch ->
                log.warn("SUBMITTING 잔류 배치 복구 — FAILED 마감 batchId={}", batch.id)
                closeFailed(batch, FoodImageBatchClient.State.FAILED)
            }
    }

    private fun collect(batch: ImageBatch) {
        val openaiBatchId = batch.openaiBatchId ?: return
        val poll = client.status(openaiBatchId)
        when (poll.state) {
            FoodImageBatchClient.State.IN_PROGRESS -> Unit
            FoodImageBatchClient.State.COMPLETED -> collectResults(batch, poll, ImageBatchStatus.COLLECTED)
            FoodImageBatchClient.State.FAILED,
            FoodImageBatchClient.State.EXPIRED,
            -> collectResults(batch, poll, ImageBatchStatus.FAILED)
        }
    }

    private fun collectResults(batch: ImageBatch, poll: FoodImageBatchClient.BatchPoll, closeAs: ImageBatchStatus) {
        val pendingByFoodId = itemRepository.findByBatchIdAndItemStatus(batch.id, ImageBatchItemStatus.PENDING)
            .associateBy { it.foodId }
            .toMutableMap()
        poll.outputFileId?.let { fileId ->
            client.streamResults(fileId) { result ->
                val foodId = result.customId.toLongOrNull() ?: return@streamResults
                val item = pendingByFoodId.remove(foodId) ?: return@streamResults
                handleResult(item, result)
            }
        }
        pendingByFoodId.values.forEach { item ->
            saveItem(item) { it.fail("배치 ${poll.state} — 결과 없음(errorFileId=${poll.errorFileId})") }
        }
        itemTransaction.executeWithoutResult {
            batchRepository.save(batch.apply { close(closeAs) })
        }
    }

    private fun handleResult(item: ImageBatchItem, result: FoodImageBatchClient.Result) {
        val bytes = result.bytes
        if (result.errorMessage != null || bytes == null) {
            saveItem(item) { it.fail(result.errorMessage ?: "이미지 데이터 없음") }
            return
        }
        val key = storageKeyOf(item.foodId)
        storageObjectStore.put(key, bytes, "image/png")
        var attached = false
        itemTransaction.executeWithoutResult {
            val food = foodRepository.findById(item.foodId).orElse(null)
            if (food == null) {
                item.fail("음식이 삭제되어 건너뜀")
            } else {
                food.attachImage(key)
                foodRepository.save(food)
                item.done(key)
                attached = true
            }
            itemRepository.save(item)
        }
        if (attached) result.usage?.let { publishCost(it) }
    }

    private fun closeFailed(batch: ImageBatch, state: FoodImageBatchClient.State) {
        itemRepository.findByBatchIdAndItemStatus(batch.id, ImageBatchItemStatus.PENDING).forEach { item ->
            saveItem(item) { it.fail("배치 $state") }
        }
        itemTransaction.executeWithoutResult {
            batchRepository.save(batch.apply { close(ImageBatchStatus.FAILED) })
        }
    }

    private fun saveItem(item: ImageBatchItem, mutate: (ImageBatchItem) -> Unit) {
        itemTransaction.executeWithoutResult {
            mutate(item)
            itemRepository.save(item)
        }
    }

    private fun publishCost(usage: FoodImageBatchClient.Usage) {
        val costUsd = BigDecimal.valueOf(usage.inputTokens)
            .multiply(BigDecimal.valueOf(properties.inputUsdPerMillionTokens))
            .add(
                BigDecimal.valueOf(usage.outputTokens)
                    .multiply(BigDecimal.valueOf(properties.outputUsdPerMillionTokens)),
            )
            .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP)
        eventPublisher.publishEvent(
            LlmCallCostIncurred(
                modelName = properties.model,
                inputTokens = usage.inputTokens,
                outputTokens = usage.outputTokens,
                costUsd = costUsd,
                costKrw = costUsd.multiply(BigDecimal.valueOf(properties.usdToKrw)).setScale(2, RoundingMode.HALF_UP),
            ),
        )
    }

    companion object {
        fun storageKeyOf(foodId: Long): String = "images/food/$foodId.png"

        const val STALE_SUBMITTING_HOURS: Long = 1
    }
}
