package com.kbap.batch.vector

import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodVectorOutboxJpaRepository
import com.kbap.common.domain.food.model.FoodVectorOutbox
import com.kbap.common.domain.food.model.FoodVectorOutboxOperation
import com.kbap.common.domain.food.vector.FoodVectorDocument
import com.kbap.common.domain.food.vector.FoodVectorDocuments
import com.kbap.common.domain.food.vector.FoodVectorStore
import com.kbap.common.port.llm.TextEmbeddingClient
import org.slf4j.LoggerFactory
import org.springframework.batch.infrastructure.item.ItemProcessor
import java.time.Instant

class FoodVectorSyncItemProcessor(
    private val foodRepository: FoodJpaRepository,
    private val outboxRepository: FoodVectorOutboxJpaRepository,
    private val embeddingClient: TextEmbeddingClient,
    private val vectorStore: FoodVectorStore,
    private val embeddingModel: String,
    private val embeddingDimension: Int,
) : ItemProcessor<FoodVectorOutbox, FoodVectorOutbox> {
    override fun process(item: FoodVectorOutbox): FoodVectorOutbox? {
        when (item.operation) {
            FoodVectorOutboxOperation.UPSERT -> return upsertDocument(item)
            FoodVectorOutboxOperation.DELETE -> vectorStore.delete(item.foodId)
        }
        return item
    }

    private fun upsertDocument(item: FoodVectorOutbox): FoodVectorOutbox? {
        val foodId = item.foodId
        val food = foodRepository.findById(foodId).orElse(null)
        if (food == null || !food.isReady()) {
            vectorStore.delete(foodId)
            return item
        }

        val longDescription = food.longDescription
        if (longDescription.isNullOrBlank()) {
            return abandon(item, "긴 설명이 비어 있어 임베딩할 수 없습니다: foodId=$foodId")
        }

        val embeddingText = FoodVectorDocuments.embeddingText(food.koreanName, longDescription)
        val embeddingHash = FoodVectorDocuments.embeddingHash(embeddingModel, embeddingDimension, embeddingText)
        if (vectorStore.findEmbeddingHash(foodId) == embeddingHash) {
            return item
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
        return item
    }

    private fun abandon(item: FoodVectorOutbox, reason: String): FoodVectorOutbox? {
        logger.warn("음식 벡터 동기화 영구 실패 outboxId={} foodId={} reason={}", item.id, item.foodId, reason)
        outboxRepository.findById(item.id).orElse(null)?.failPermanently(reason)
        return null
    }

    private companion object {
        val logger = LoggerFactory.getLogger(FoodVectorSyncItemProcessor::class.java)
    }
}
