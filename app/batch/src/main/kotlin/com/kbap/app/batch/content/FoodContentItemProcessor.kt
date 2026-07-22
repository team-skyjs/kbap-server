package com.kbap.app.batch.content

import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.model.Food
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

// 작업 본문은 골격 단계에선 비어 있다 — KB-183(설명·번역)·KB-184(사진)·KB-209(기피성분)가 채운다.
class FoodContentItemProcessor(
    private val foodRepository: FoodJpaRepository,
    transactionManager: PlatformTransactionManager,
) : ItemProcessor<Food, Food> {
    // REQUIRES_NEW — 청크 트랜잭션과 분리해 즉시 커밋. 뒤 작업이 실패해도 이 작업 결과는 유지(재실행 시 실패 작업만 재시도).
    private val progressTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun process(item: Food): Food {
        if (item.needsImage()) {
            generateImage(item)
            saveProgress(item)
        }
        if (item.needsDescription()) {
            generateDescription(item)
            saveProgress(item)
        }
        if (item.needsNameTranslations() || item.needsDescriptionTranslations()) {
            translateContent(item)
            saveProgress(item)
        }
        if (item.needsAvoidanceMapping()) {
            mapAvoidance(item)
            saveProgress(item)
        }
        return item
    }

    fun saveProgress(food: Food) {
        progressTransaction.executeWithoutResult { foodRepository.save(food) }
    }

    private fun generateImage(food: Food) {
    }

    private fun generateDescription(food: Food) {
    }

    private fun translateContent(food: Food) {
    }

    // KB-209: API 3개 호출·종합으로 food.avoidanceSubstances 를 채운다.
    private fun mapAvoidance(food: Food) {
    }
}
