package com.kbap.api.food

import com.kbap.api.processedfood.ProcessedFoodSummaryResponse
import com.kbap.api.processedfood.ProcessedFoodTranslationService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodProcessedLinkJpaRepository
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate

@Service
class FoodProcessedLinkService(
    private val links: FoodProcessedLinkJpaRepository,
    private val foods: FoodJpaRepository,
    private val products: ProcessedFoodProductJpaRepository,
    private val translations: ProcessedFoodTranslationService,
    transactionManager: PlatformTransactionManager,
) {
    private val readTransaction = TransactionTemplate(transactionManager).apply { isReadOnly = true }

    @Transactional
    fun link(foodId: Long, processedFoodId: Long, adminAccountId: Long?): LinkedProcessedFoodResponse {
        if (!foods.existsById(foodId)) throw BusinessException(ErrorCode.FOOD_NOT_FOUND)
        val product = products.findById(processedFoodId).orElseThrow { BusinessException(ErrorCode.PROCESSED_FOOD_NOT_FOUND) }
        if (!product.hasApprovedCutout()) throw BusinessException(ErrorCode.PROCESSED_FOOD_CUTOUT_NOT_APPROVED)
        links.upsertLink(foodId, product.id, adminAccountId)
        return LinkedProcessedFoodResponse(product.id, product.productName, product.productName, product.displayImageUrl())
    }

    @Transactional
    fun unlink(foodId: Long): Boolean = links.unlink(foodId) > 0

    fun getLinkedProcessedFoods(foodIds: Collection<Long>, language: LanguageCode): Map<Long, LinkedProcessedFoodResponse> {
        val linked = readTransaction.execute { linkedSummaries(foodIds) }!!
        if (linked.isEmpty()) return emptyMap()
        val localized = translations.localizeSummaries(linked.values.distinctBy { it.id }, language).associateBy { it.id }
        return linked.mapValues { (_, summary) -> responseOf(localized.getValue(summary.id)) }
    }

    @Transactional(readOnly = true)
    fun getKoreanLinkedProcessedFoods(foodIds: Collection<Long>): Map<Long, LinkedProcessedFoodResponse> =
        linkedSummaries(foodIds).mapValues { (_, summary) -> responseOf(summary) }

    private fun linkedSummaries(foodIds: Collection<Long>): Map<Long, ProcessedFoodSummaryResponse> {
        if (foodIds.isEmpty()) return emptyMap()
        val productIdByFood = links.findAllByFoodIdIn(foodIds.distinct()).associate { it.foodId to it.processedFoodProductId }
        if (productIdByFood.isEmpty()) return emptyMap()
        val summaries = products.findAllById(productIdByFood.values.distinct()).associate { it.id to ProcessedFoodSummaryResponse.from(it) }
        return productIdByFood.mapNotNull { (foodId, productId) -> summaries[productId]?.let { foodId to it } }.toMap()
    }

    private fun responseOf(summary: ProcessedFoodSummaryResponse) =
        LinkedProcessedFoodResponse(summary.id, summary.name, summary.nameKo, summary.imageUrl)
}
