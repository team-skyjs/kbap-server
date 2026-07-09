package com.meogo.application.client.scan.usecase

import com.meogo.application.client.food.usecase.FoodRiskEvaluator
import com.meogo.application.client.scan.dto.SubmitMenuScanInput
import com.meogo.application.client.scan.dto.SubmitMenuScanResult
import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.menu.KoreanMenuNameNormalizer
import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import com.meogo.core.scan.MenuItemMatch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class SubmitMenuScanUseCase(
    private val foodRepository: FoodRepository,
    private val foodRiskEvaluator: FoodRiskEvaluator,
    @Autowired(required = false)
    private val interpreter: ScannedNameInterpreter? = null,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun submit(input: SubmitMenuScanInput): SubmitMenuScanResult {
        val resolutions = resolveItems(input)
        val risks = foodRiskEvaluator.risksOf(resolutions.mapNotNull { it.food })

        val items = input.items.mapIndexedNotNull { index, item ->
            val resolution = resolutions[index]
            if (resolution.match is MenuItemMatch.NotFood) return@mapIndexedNotNull null
            SubmitMenuScanResult.ItemRiskResult(
                itemId = item.itemId,
                riskLevel = riskOf(resolution, risks).name,
                matchStatus = statusOf(resolution.match),
                foodId = foodIdOf(resolution.match),
            )
        }

        return SubmitMenuScanResult(items)
    }

    private fun riskOf(resolution: Resolution, risks: Map<Long, RiskLevel>): RiskLevel {
        val food = resolution.food ?: return RiskLevel.UNKNOWN
        return risks[food.id] ?: RiskLevel.UNKNOWN
    }


    private fun statusOf(match: MenuItemMatch): String =
        when (match) {
            is MenuItemMatch.Matched -> "MATCHED"
            else -> "UNMATCHED"
        }

    private fun foodIdOf(match: MenuItemMatch): Long? =
        when (match) {
            is MenuItemMatch.Matched -> match.foodId
            is MenuItemMatch.Unmatched -> match.foodId
            MenuItemMatch.NotFood -> null
        }

    private fun resolveItems(input: SubmitMenuScanInput): List<Resolution> {
        val keys = input.items.map { KoreanMenuNameNormalizer.matchKey(it.rawMenuName) }
        val interpreted = interpretTargets(input, keys)

        val lookups = input.items.indices.map { index ->
            lookupNameOf(keys[index], input.items[index].rawMenuName, interpreted, index)
        }
        val foundByKey = foodRepository.findByKoreanMatchKeys(lookups.filterNotNull().map { it.matchKey }.toSet())
        val createdByName = mutableMapOf<String, Food>()

        return lookups.map { lookup ->
            if (lookup == null) return@map Resolution(MenuItemMatch.NotFood, null)

            val existing = foundByKey[lookup.matchKey]
            if (existing != null) return@map Resolution(matchOf(existing), existing)

            if (!lookup.confirmedFood) return@map Resolution(MenuItemMatch.Unmatched(), null)

            val created = createdByName.getOrPut(lookup.koreanName) { foodRepository.createIncomplete(lookup.koreanName) }
            Resolution(matchOf(created), created)
        }
    }

    private fun matchOf(food: Food): MenuItemMatch {
        val foodId = requireNotNull(food.id) { "매칭된 food 에 id 가 없습니다" }
        return if (food.isReady()) MenuItemMatch.Matched(foodId) else MenuItemMatch.Unmatched(foodId)
    }

    private fun lookupNameOf(
        key: String,
        rawMenuName: String,
        interpreted: Map<Int, InterpretedName>?,
        index: Int,
    ): LookupName? {
        if (key.isBlank()) return null
        if (interpreted == null) {
            return LookupName(koreanName = rawMenuName, matchKey = key, confirmedFood = false)
        }
        return when (val interpretedName = interpreted.getValue(index)) {
            is InterpretedName.StandardName -> LookupName(
                koreanName = interpretedName.korean,
                matchKey = KoreanMenuNameNormalizer.matchKey(interpretedName.korean),
                confirmedFood = true,
            )
            InterpretedName.NotFood -> null
        }
    }

    private fun interpretTargets(
        input: SubmitMenuScanInput,
        keys: List<String>,
    ): Map<Int, InterpretedName>? {
        if (interpreter == null) return null
        val targetIndexes = keys.indices.filter { keys[it].isNotBlank() }
        if (targetIndexes.isEmpty()) return null

        val texts = targetIndexes.map { input.items[it].rawMenuName }
        return try {
            val interpreted = interpreter.interpret(texts)
            require(interpreted.size == targetIndexes.size) {
                "정제 결과 개수(${interpreted.size})가 요청(${targetIndexes.size})과 다릅니다"
            }
            targetIndexes.zip(interpreted).toMap()
        } catch (e: Exception) {
            log.warn("정제 서비스 호출 실패 — 정규화 exact 매치 폴백", e)
            null
        }
    }

    private data class LookupName(val koreanName: String, val matchKey: String, val confirmedFood: Boolean)

    private data class Resolution(val match: MenuItemMatch, val food: Food?)

}
