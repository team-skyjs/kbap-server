package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.BrowseFoodsInput
import com.meogo.application.client.food.dto.FoodPage
import com.meogo.application.client.food.dto.FoodSummaryView
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.FoodRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BrowseFoodsUseCase(
    private val foodRepository: FoodRepository,
    private val languageResolver: LanguageResolver,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
) {
    @Transactional(readOnly = true)
    fun browse(input: BrowseFoodsInput): FoodPage {
        val lang = languageResolver.resolve(input.lang)

        val rows = foodRepository.findFoodPage(input.cursor, PAGE_SIZE + 1)
        val hasNext = rows.size > PAGE_SIZE
        val items = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) items.last().id else null

        val userAvoidedCodes = avoidedSubstanceProvider.avoidedCodes()
            .map { AvoidanceSubstanceCodeRef(it.name) }
            .toSet()

        return FoodPage(
            items = items.map { FoodSummaryView.from(it, lang, userAvoidedCodes) },
            nextCursor = nextCursor,
            hasNext = hasNext,
        )
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
