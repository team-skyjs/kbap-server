package com.kbap.api.food

import com.kbap.common.domain.food.dto.FoodSearchName
import com.kbap.common.util.KoreanMenuNameNormalizer
import java.text.Normalizer

data class RankedFood(val id: Long, val grade: Int, val scans: Long) {
    fun cursor(): FoodSearchCursor = FoodSearchCursor(grade, scans, id)
}

object FoodSearchRanker {
    const val EXACT = 0
    const val PREFIX = 1
    const val CONTAINS = 2

    val ORDER: Comparator<RankedFood> = compareBy<RankedFood> { it.grade }.thenByDescending { it.scans }.thenByDescending { it.id }

    fun rank(keyword: String, names: List<FoodSearchName>, scansByFoodId: (Collection<Long>) -> Map<Long, Long>): List<RankedFood> {
        val graded = grade(keyword, names)
        if (graded.isEmpty()) return emptyList()
        val scans = scansByFoodId(graded.keys)
        return graded.map { (id, grade) -> RankedFood(id, grade, scans[id] ?: 0L) }.sortedWith(ORDER)
    }

    fun matchingIds(keyword: String, names: List<FoodSearchName>): Set<Long> = grade(keyword, names).keys

    private fun grade(keyword: String, names: List<FoodSearchName>): Map<Long, Int> {
        val koreanKey = KoreanMenuNameNormalizer.matchKey(keyword)
        val looseKey = loose(keyword)
        if (koreanKey.isEmpty() && looseKey.isEmpty()) return emptyMap()
        return names.mapNotNull { name -> gradeOf(name, koreanKey, looseKey)?.let { grade -> name.id to grade } }.toMap()
    }

    fun after(ranked: List<RankedFood>, cursor: FoodSearchCursor?): List<RankedFood> {
        if (cursor == null) return ranked
        val boundary = RankedFood(cursor.id, cursor.grade, cursor.scans)
        return ranked.filter { ORDER.compare(it, boundary) > 0 }
    }

    private fun gradeOf(name: FoodSearchName, koreanKey: String, looseKey: String): Int? {
        val candidates = listOf(name.koreanName, name.displayName) + name.nameTranslations.values.filterNotNull()
        val grades = if (koreanKey.isNotEmpty()) {
            candidates.map { KoreanMenuNameNormalizer.matchKey(it) }.filter { it.isNotEmpty() }.mapNotNull { gradeOf(it, koreanKey) }
        } else {
            candidates.map { loose(it) }.filter { it.isNotEmpty() }.mapNotNull { gradeOf(it, looseKey) }
        }
        return grades.minOrNull()
    }

    private fun gradeOf(candidate: String, key: String): Int? = when {
        candidate == key -> EXACT
        candidate.startsWith(key) -> PREFIX
        candidate.contains(key) -> CONTAINS
        else -> null
    }

    private fun loose(raw: String): String =
        Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
}
