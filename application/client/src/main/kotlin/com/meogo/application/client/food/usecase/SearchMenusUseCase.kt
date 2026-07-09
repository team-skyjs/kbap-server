package com.meogo.application.client.food.usecase

import com.meogo.application.client.food.dto.MenuPage
import com.meogo.application.client.food.dto.SearchMenusInput
import com.meogo.core.food.FoodRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class SearchMenusUseCase(
    private val foodRepository: FoodRepository,
    private val languageResolver: LanguageResolver,
    private val menuSummaryAssembler: MenuSummaryAssembler,
) {
    @Transactional(readOnly = true)
    fun search(input: SearchMenusInput): MenuPage {
        val keyword = resolveKeyword(input.keyword)
        val lang = languageResolver.resolve(input.lang)

        val rows = foodRepository.searchMenuPage(keyword, lang, input.cursor, PAGE_SIZE + 1)
        val hasNext = rows.size > PAGE_SIZE
        val items = rows.take(PAGE_SIZE)
        val nextCursor = if (hasNext) items.last().id else null

        return MenuPage(
            items = menuSummaryAssembler.assemble(items, lang),
            nextCursor = nextCursor,
            hasNext = hasNext,
        )
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
