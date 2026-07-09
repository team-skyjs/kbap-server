package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.BrowseMenusInput
import com.meogo.application.client.food.dto.BrowseMenusResult
import com.meogo.core.food.FoodRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BrowseMenusUseCase(
    private val foodRepository: FoodRepository,
    private val languageResolver: LanguageResolver,
    private val foodRiskEvaluator: FoodRiskEvaluator,
) {
    @Transactional(readOnly = true)
    fun browse(input: BrowseMenusInput): BrowseMenusResult {
        val lang = languageResolver.resolve(input.lang)

        val rows = foodRepository.findMenuPage(input.cursor, PAGE_SIZE + 1)
        val hasNext = rows.size > PAGE_SIZE
        val items = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) items.last().id else null

        val risks = foodRiskEvaluator.risksOf(items)

        val views = items.map { food ->
            val localizedName = food.displayName(lang)
            BrowseMenusResult.MenuSummaryView(
                foodId = food.id!!,
                name = localizedName,
                koreanName = food.koreanName().takeIf { it != localizedName },
                imageRef = food.imageRef,
                spiciness = food.spiciness.value,
                overallRiskStatus = risks.getValue(food.id!!),
            )
        }

        return BrowseMenusResult(items = views, nextCursor = nextCursor, hasNext = hasNext)
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
