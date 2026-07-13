package com.kbap.application.food.usecase

import com.kbap.application.food.dto.FoodPage
import com.kbap.application.food.dto.FoodSummaryView
import com.kbap.application.food.dto.SearchFoodsInput
import com.kbap.domain.food.AvoidanceSubstanceCodeRef
import com.kbap.domain.food.FoodService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SearchFoodsUseCase(
    private val foodService: FoodService,
    private val languageResolver: LanguageResolver,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
) {
    @Transactional(readOnly = true)
    fun search(input: SearchFoodsInput): FoodPage {
        val keyword = resolveKeyword(input.keyword)
        val lang = languageResolver.resolve(input.lang)

        val rows = foodService.searchFoodPage(keyword, lang, input.cursor, PAGE_SIZE + 1)
        val hasNext = rows.size > PAGE_SIZE
        val items = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) items.last().id else null

        val userAvoidedCodes = avoidedSubstanceProvider.avoidedCodes(input.memberId)
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
