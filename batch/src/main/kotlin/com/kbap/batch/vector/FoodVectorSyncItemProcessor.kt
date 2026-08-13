package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.vector.FoodVectorDocument
import com.kbap.common.domain.food.vector.FoodVectorDocuments
import com.kbap.common.domain.food.vector.FoodVectorStore
import com.kbap.common.port.llm.TextEmbeddingClient
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

class FoodVectorSyncItemProcessor(
    private val foodRepository: FoodJpaRepository,
    transactionManager: PlatformTransactionManager,
    private val embeddingClient: TextEmbeddingClient,
    private val vectorStore: FoodVectorStore,
    private val embeddingModel: String,
    private val embeddingDimension: Int,
) : ItemProcessor<FoodVectorOutbox, FoodVectorSyncOutcome> {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    override fun process(item: FoodVectorOutbox): FoodVectorSyncOutcome =
        try {
            when (item.operation) {
                FoodVectorOutboxOperation.UPSERT -> upsertDocument(item.foodId)
                FoodVectorOutboxOperation.DELETE -> vectorStore.delete(item.foodId)
            }
            FoodVectorSyncOutcome.success(item.id)
        } catch (e: Exception) {
            logger.warn("음식 벡터 동기화 실패 outboxId={} foodId={}", item.id, item.foodId, e)
            FoodVectorSyncOutcome.failure(item.id, e.message ?: e.javaClass.simpleName)
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

    private companion object {
        val logger = LoggerFactory.getLogger(FoodVectorSyncItemProcessor::class.java)
    }
}
