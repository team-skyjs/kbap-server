package com.kbap.app.batch.content

import com.kbap.domain.food.FoodContentBatchService
import com.kbap.domain.food.model.Food
import org.springframework.batch.infrastructure.item.ItemProcessor

// 작업 본문은 골격 단계에선 비어 있다 — KB-183(설명·번역)·KB-184(사진)·KB-209(기피성분)가 채운다.
class FoodContentItemProcessor(
    private val foodContentBatchService: FoodContentBatchService,
) : ItemProcessor<Food, Food> {
    override fun process(item: Food): Food {
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
        if (item.needsAvoidanceMapping()) {
            mapAvoidance(item)
            foodContentBatchService.saveProgress(item)
        }
        return item
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
