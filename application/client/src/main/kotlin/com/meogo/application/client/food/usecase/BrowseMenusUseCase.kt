package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.BrowseMenusInput
import com.meogo.application.client.food.dto.BrowseMenusResult
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BrowseMenusUseCase(
    private val foodRepository: FoodRepository,
    private val languageResolver: LanguageResolver,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
) {
    @Transactional(readOnly = true)
    fun browse(input: BrowseMenusInput): BrowseMenusResult {
        val lang = languageResolver.resolve(input.lang)

        val rows = foodRepository.findMenuPage(input.cursor, PAGE_SIZE + 1)
        val hasNext = rows.size > PAGE_SIZE
        val items = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) items.last().id else null

        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes()
            .map { AvoidanceSubstanceCodeRef(it.name) }
            .toSet()
        val catalogCodes = catalogCodes(items)

        val views = items.map { food ->
            val resolvableAvoidedCodes = food.avoidanceSubstances
                .map { it.substanceCode }
                .filter { it in catalogCodes }
                .toSet()
            BrowseMenusResult.MenuSummaryView(
                foodId = food.id!!,
                name = food.displayName(lang),
                imageRef = food.imageRef,
                spiciness = food.spiciness.value,
                overallRiskStatus = food.overallRisk(avoidedCodes intersect resolvableAvoidedCodes),
            )
        }

        return BrowseMenusResult(items = views, nextCursor = nextCursor, hasNext = hasNext)
    }

    private fun catalogCodes(foods: List<Food>): Set<AvoidanceSubstanceCodeRef> {
        val codes = foods
            .flatMap { food -> food.avoidanceSubstances.map { it.substanceCode } }
            .map { AvoidanceSubstanceCode.valueOf(it.value) }
            .toSet()
        return avoidanceSubstanceRepository.findByCodes(codes)
            .map { AvoidanceSubstanceCodeRef(it.code.name) }
            .toSet()
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
