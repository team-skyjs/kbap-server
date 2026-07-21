package com.kbap.app.batch.content

import com.kbap.domain.food.FoodContentBatchService
import com.kbap.domain.food.model.Food
import org.springframework.batch.infrastructure.item.ItemProcessor

// 작업 본문은 골격 단계에선 비어 있다 — KB-183(설명·번역)·KB-184(사진)·KB-209(기피성분)가 채운다.
class FoodContentItemProcessor(
    private val foodContentBatchService: FoodContentBatchService,
) : ItemProcessor<Food, ProcessedFood> {
    override fun process(item: Food): ProcessedFood {
        if (item.needsImage()) {
            generateImage(item)
            foodContentBatchService.saveProgress(item)
        }
        if (item.needsDescription()) {
            generateDescription(item)
            foodContentBatchService.saveProgress(item)
        }
        if (item.needsNameTranslations() || item.needsDescriptionTranslations()) {
            translateContent(item)
            foodContentBatchService.saveProgress(item)
        }
        val hasAvoidanceMapping = mapAvoidance(item)
        return ProcessedFood(item, hasAvoidanceMapping)
    }

    private fun generateImage(food: Food) {
    }

    private fun generateDescription(food: Food) {
    }

    private fun translateContent(food: Food) {
    }

    // KB-209: 매핑 있으면 skip, 없으면 API 3개 호출·종합 후 존재 여부 반환.
    private fun mapAvoidance(food: Food): Boolean = false
}

data class ProcessedFood(
    val food: Food,
    val hasAvoidanceMapping: Boolean,
)
