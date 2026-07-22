package com.kbap.app.batch.content

import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.model.Food
import com.kbap.domain.food.model.FoodAvoidanceItem
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

class FoodContentItemProcessor(
    private val foodRepository: FoodJpaRepository,
    transactionManager: PlatformTransactionManager,
    private val avoidanceClient: FoodAvoidanceAssessmentClient,
    private val candidateCodes: () -> Set<String>,
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

    private fun mapAvoidance(food: Food) {
        val codes = candidateCodes()
        if (codes.isEmpty()) return
        // 포함률 0 은 미포함 판단이라 버린다 — RiskLevel 은 1..100 만 허용(0 저장 시 조회에서 예외).
        val substances = avoidanceClient.call(food.koreanName, codes)
            .filter { it.inclusionPercent > 0 }
            .map { FoodAvoidanceItem(code = it.code, inclusionPercent = it.inclusionPercent) }
        food.assessAvoidance(substances)
    }
}
