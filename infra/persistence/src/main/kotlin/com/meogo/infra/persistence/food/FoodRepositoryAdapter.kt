package com.meogo.infra.persistence.food

import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.lang.LanguageCode
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class FoodRepositoryAdapter(
    private val foodJpaRepository: FoodJpaRepository,
) : FoodRepository {
    override fun findById(id: Long): Food? =
        foodJpaRepository.findByIdInWithAvoidanceSubstances(listOf(id)).firstOrNull()?.toDomain()

    override fun findMenuPage(cursor: Long?, size: Int): List<Food> {
        val ids = foodJpaRepository.findMenuPageIds(cursor, PageRequest.of(0, size))
        return loadDescending(ids)
    }

    override fun searchMenuPage(keyword: String, lang: LanguageCode, cursor: Long?, size: Int): List<Food> {
        val jsonPath = if (lang == LanguageCode.KO) null else "$.\"${lang.code}\""
        val ids = foodJpaRepository.searchMenuPageIds(escapeLikeWildcards(keyword), jsonPath, cursor, size)
        return loadDescending(ids)
    }

    private fun escapeLikeWildcards(keyword: String): String =
        keyword
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun loadDescending(ids: List<Long>): List<Food> {
        if (ids.isEmpty()) return emptyList()
        return foodJpaRepository.findByIdInWithAvoidanceSubstancesDesc(ids).map { it.toDomain() }
    }
}
