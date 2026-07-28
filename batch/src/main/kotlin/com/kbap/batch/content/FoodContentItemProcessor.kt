package com.kbap.batch.content

import com.kbap.common.port.llm.FoodAvoidanceAssessmentClient
import com.kbap.common.port.llm.FoodDescriptionClient
import com.kbap.common.port.llm.FoodNameTranslationClient
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

class FoodContentItemProcessor(
    private val foodRepository: FoodJpaRepository,
    transactionManager: PlatformTransactionManager,
    private val avoidanceClient: FoodAvoidanceAssessmentClient,
    private val descriptionClient: FoodDescriptionClient? = null,
    private val nameTranslationClient: FoodNameTranslationClient? = null,
    private val candidateCodes: () -> Set<String>,
) : ItemProcessor<Food, Food> {
    // REQUIRES_NEW — 작업별 독립 커밋. 각 작업 결과를 즉시 커밋해 뒤 작업이 실패해도 유지된다(재실행 시 실패 작업만 재시도).
    private val progressTransaction = TransactionTemplate(transactionManager).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    override fun process(item: Food): Food {
        var food = item
        if (food.needsNameTranslations()) {
            translateName(food)
            food = saveProgress(food)
        }
        if (food.needsDescription() || food.needsDescriptionTranslations()) {
            generateDescription(food)
            food = saveProgress(food)
        }
        if (food.needsAvoidanceAssessment()) {
            mapAvoidance(food)
            food = saveProgress(food)
        }
        return food
    }

    fun saveProgress(food: Food): Food =
        progressTransaction.execute { foodRepository.save(food) }!!

    private fun generateDescription(food: Food) {
        val client = descriptionClient
            ?: throw FoodContentClientNotConfiguredException("설명 생성 클라이언트가 구성되지 않았습니다: foodId=${food.id}")
        val content = client.call(food.koreanName)
        food.updateDescription(content.description, content.translations.byCode())
    }

    private fun translateName(food: Food) {
        val client = nameTranslationClient
            ?: throw FoodContentClientNotConfiguredException("이름 번역 클라이언트가 구성되지 않았습니다: foodId=${food.id}")
        food.updateNameTranslations(client.call(food.koreanName).byCode())
    }

    private fun mapAvoidance(food: Food) {
        val codes = candidateCodes()
        if (codes.isEmpty()) return
        val result = avoidanceClient.call(food.koreanName, codes)
        val substances = result.substances
            .filter { it.inclusionPercent > 0 }
            .map { FoodAvoidanceItem(code = it.code, inclusionPercent = it.inclusionPercent) }
        food.assessAvoidance(substances, result.spiciness)
    }
}
