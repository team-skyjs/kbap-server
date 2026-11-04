package com.kbap.batch.notification.discovery

import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.RiskLevel
import kotlin.random.Random

class FoodDiscoveryCandidates(foods: Collection<Food>) {
    data class Candidate(
        val food: Food,
        val text: Map<LanguageCode, FoodText>,
    ) {
        val id: Long get() = food.id
    }

    data class FoodText(val name: String, val description: String)

    private val all: List<Candidate> = foods
        .filter { it.isReady() && it.ingredients != null }
        .map { food -> Candidate(food, LanguageCode.entries.mapNotNull { lang -> strictDescription(food, lang)?.let { lang to FoodText(food.displayName(lang), it) } }.toMap()) }

    private val memo = HashMap<Set<String>, List<Candidate>>()

    val memoSize: Int get() = memo.size

    val ids: Set<Long> get() = all.map { it.id }.toSet()

    fun safeFor(avoidedCodes: Set<String>): List<Candidate> =
        memo.getOrPut(avoidedCodes) { all.filter { it.food.overallRisk(avoidedCodes) == RiskLevel.SAFE } }

    fun pick(avoidedCodes: Set<String>, langs: Set<LanguageCode>, excludedFoodIds: Set<Long>, random: Random): Candidate? =
        safeFor(avoidedCodes)
            .filter { candidate -> langs.all(candidate.text::containsKey) && candidate.id !in excludedFoodIds }
            .randomOrNull(random)

    private fun strictDescription(food: Food, lang: LanguageCode): String? =
        (if (lang == LanguageCode.KO) food.description else food.descriptionTranslations[lang.code])?.takeIf { it.isNotBlank() }
}
