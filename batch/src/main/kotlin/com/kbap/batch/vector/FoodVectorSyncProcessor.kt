package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.vector.FoodVectorDocument
import com.kbap.common.domain.food.vector.FoodVectorDocuments
import com.kbap.common.domain.food.vector.FoodVectorStore
import com.kbap.common.port.llm.TextEmbeddingClient
import org.slf4j.LoggerFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class FoodVectorSyncProcessor(
    private val outboxRepository: FoodVectorOutboxJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val embeddingClient: TextEmbeddingClient,
    private val vectorStore: FoodVectorStore,
    transactionManager: PlatformTransactionManager,
    private val embeddingModel: String,
    private val embeddingDimension: Int,
    private val pageSize: Int,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    init {
        require(pageSize > 0) { "pageSize는 1 이상이어야 합니다" }
    }

    fun syncAll(): FoodVectorSyncSummary {
        var cursor = 0L
        var attempted = 0
        var completed = 0
        var failed = 0

        while (true) {
            val outboxes = transactionTemplate.execute {
                outboxRepository.findPendingAfterId(cursor, pageSize)
            }.orEmpty()
            if (outboxes.isEmpty()) {
                break
            }

            cursor = outboxes.last().id
            outboxes.forEach { outbox ->
                attempted++
                if (sync(outbox)) completed++ else failed++
            }
        }

        return FoodVectorSyncSummary(attempted, completed, failed)
    }

    private fun sync(outbox: FoodVectorOutbox): Boolean =
        try {
            when (outbox.operation) {
                FoodVectorOutboxOperation.UPSERT -> upsertDocument(outbox.foodId)
                FoodVectorOutboxOperation.DELETE -> vectorStore.delete(outbox.foodId)
            }
            recordResult(outbox.id) { it.complete() }
            true
        } catch (e: Exception) {
            logger.warn("음식 벡터 동기화 실패 outboxId={} foodId={}", outbox.id, outbox.foodId, e)
            recordResult(outbox.id) { it.recordFailure(e.message ?: e.javaClass.simpleName) }
            false
        }

    private fun upsertDocument(foodId: Long) {
        val food = readFood(foodId)
        if (food == null || !food.isReady()) {
            vectorStore.delete(foodId)
            return
        }

        val longDescription = food.longDescription
        check(!longDescription.isNullOrBlank()) { "긴 설명이 비어 있어 임베딩할 수 없습니다: foodId=$foodId" }

        val embeddingText = FoodVectorDocuments.embeddingText(food.koreanName, longDescription)
        val embeddingHash = FoodVectorDocuments.embeddingHash(embeddingModel, embeddingDimension, embeddingText)
        if (vectorStore.findEmbeddingHash(foodId) == embeddingHash) {
            return
        }

        vectorStore.upsert(
            FoodVectorDocument(
                foodId = foodId,
                name = food.koreanName,
                longDescription = longDescription,
                imageRef = food.imageRef,
                embedding = embeddingClient.embed(listOf(embeddingText)).first(),
                embeddingHash = embeddingHash,
                embeddingModel = embeddingModel,
                embeddingDimension = embeddingDimension,
                indexedAt = Instant.now(),
            ),
        )
    }

    private fun readFood(foodId: Long): Food? = transactionTemplate.execute {
        foodRepository.findById(foodId).orElse(null)
    }

    private fun recordResult(outboxId: Long, record: (FoodVectorOutbox) -> Unit) {
        transactionTemplate.executeWithoutResult {
            val outbox = outboxRepository.findById(outboxId).orElse(null) ?: return@executeWithoutResult
            record(outbox)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(FoodVectorSyncProcessor::class.java)
    }
}

data class FoodVectorSyncSummary(
    val attempted: Int,
    val completed: Int,
    val failed: Int,
)
