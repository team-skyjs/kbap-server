package com.meogo.application.client.scan.usecase

import com.meogo.application.client.food.usecase.AvoidedSubstanceProvider
import com.meogo.application.client.food.usecase.LanguageResolver
import com.meogo.application.client.scan.dto.SubmitMenuScanInput
import com.meogo.application.client.scan.dto.SubmitMenuScanResult
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.menu.KoreanMenuNameNormalizer
import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import com.meogo.core.scan.MenuItemMatch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class SubmitMenuScanUseCase(
    private val foodRepository: FoodRepository,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
    private val languageResolver: LanguageResolver,
    private val interpreter: ScannedNameInterpreter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun submit(input: SubmitMenuScanInput): SubmitMenuScanResult {
        val keys = input.items.map { KoreanMenuNameNormalizer.matchKey(it.rawMenuName) }
        val interpretation = interpretTargets(input, keys)
        val resolutions = resolveItems(input, keys, interpretation.byIndex)
        val lang = languageResolver.resolve(input.lang)
        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes()
            .map { AvoidanceSubstanceCodeRef(it.name) }
            .toSet()

        val items = input.items.mapIndexedNotNull { index, item ->
            val resolution = resolutions[index] ?: return@mapIndexedNotNull null
            SubmitMenuScanResult.ItemRiskResult(
                itemId = item.itemId,
                riskLevel = riskOf(resolution, avoidedCodes).name,
                matchStatus = statusOf(resolution.match),
                foodId = foodIdOf(resolution.match),
                name = resolution.food?.displayName(lang),
                koreanName = resolution.food?.koreanName(),
            )
        }

        return SubmitMenuScanResult(items = items, degraded = interpretation.degraded)
    }

    private fun riskOf(resolution: Resolution, avoidedCodes: Set<AvoidanceSubstanceCodeRef>): RiskLevel =
        resolution.food?.overallRisk(avoidedCodes) ?: RiskLevel.UNKNOWN

    private fun statusOf(match: MenuItemMatch): String =
        when (match) {
            is MenuItemMatch.Matched -> "MATCHED"
            is MenuItemMatch.Unmatched -> "UNMATCHED"
        }

    private fun foodIdOf(match: MenuItemMatch): Long? =
        when (match) {
            is MenuItemMatch.Matched -> match.foodId
            is MenuItemMatch.Unmatched -> match.foodId
        }

    private fun resolveItems(
        input: SubmitMenuScanInput,
        keys: List<String>,
        interpreted: Map<Int, InterpretedName>?,
    ): List<Resolution?> {
        val lookups = input.items.indices.map { index ->
            lookupNameOf(keys[index], input.items[index].rawMenuName, interpreted, index)
        }
        val foundByKey = foodRepository.findByKoreanMatchKeys(lookups.filterNotNull().map { it.matchKey }.toSet())
        val createdByName = foodRepository.createIncomplete(namesToRegister(lookups, foundByKey))

        return lookups.map { lookup ->
            if (lookup == null) return@map null

            val existing = foundByKey[lookup.matchKey]
            if (existing != null) return@map Resolution(matchOf(existing), existing)

            if (!lookup.confirmedFood) return@map Resolution(MenuItemMatch.Unmatched(), null)

            val created = createdByName[lookup.koreanName] ?: return@map Resolution(MenuItemMatch.Unmatched(), null)
            Resolution(matchOf(created), created)
        }
    }

    private fun namesToRegister(lookups: List<LookupName?>, foundByKey: Map<String, Food>): Set<String> =
        lookups
            .filterNotNull()
            .filter { it.confirmedFood && it.matchKey !in foundByKey }
            .map { it.koreanName }
            .toSet()

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

    private fun interpretTargets(input: SubmitMenuScanInput, keys: List<String>): Interpretation {
        val targetIndexes = keys.indices.filter { keys[it].isNotBlank() }
        if (targetIndexes.isEmpty()) return Interpretation(byIndex = null, degraded = false)

        val texts = targetIndexes.map { input.items[it].rawMenuName }
        return try {
            val interpreted = interpreter.interpret(texts)
            require(interpreted.size == targetIndexes.size) {
                "정제 결과 개수(${interpreted.size})가 요청(${targetIndexes.size})과 다릅니다"
            }
            Interpretation(byIndex = targetIndexes.zip(interpreted).toMap(), degraded = false)
        } catch (e: Exception) {
            log.warn("정제 서비스 호출 실패 — 정규화 exact 매치 폴백", e)
            Interpretation(byIndex = null, degraded = true)
        }
    }

    private data class Interpretation(val byIndex: Map<Int, InterpretedName>?, val degraded: Boolean)

    private data class LookupName(val koreanName: String, val matchKey: String, val confirmedFood: Boolean)

    private data class Resolution(val match: MenuItemMatch, val food: Food?)

}
