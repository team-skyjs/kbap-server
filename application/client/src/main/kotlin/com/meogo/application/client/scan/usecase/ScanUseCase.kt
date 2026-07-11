package com.meogo.application.client.scan.usecase

import com.meogo.application.client.food.usecase.AvoidedSubstanceProvider
import com.meogo.application.client.scan.dto.ScanInput
import com.meogo.application.client.scan.dto.ScanResult
import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.menu.KoreanMenuNameNormalizer
import com.meogo.core.kernel.risk.RiskLevel
import com.meogo.core.kernel.scan.InterpretedName
import com.meogo.core.kernel.scan.ScannedNameInterpreter
import com.meogo.core.scan.ScanHistory
import com.meogo.core.scan.ScanHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ScanUseCase(
    private val foodRepository: FoodRepository,
    private val avoidedSubstanceProvider: AvoidedSubstanceProvider,
    private val interpreter: ScannedNameInterpreter,
    private val scanHistoryRepository: ScanHistoryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun assessMenuBoard(input: ScanInput): ScanResult {
        val matchKeys = input.items.map { KoreanMenuNameNormalizer.matchKey(it.rawMenuName) }
        val refinement = refineMenuNames(input, matchKeys)
        val resolvedItems = resolveFoods(input, matchKeys, refinement.byItemIndex)

        // TODO: 회원 설정값(MemberProfile.appLanguage)에서 언어를 가져와 번역된 메뉴명을 내려준다
        val lang = LanguageCode.KO
        val avoidedCodes = avoidedSubstanceProvider.avoidedCodes()
            .map { AvoidanceSubstanceCodeRef(it.name) }
            .toSet()

        val items = input.items.mapIndexedNotNull { index, item ->
            val resolved = resolvedItems[index] ?: return@mapIndexedNotNull null
            ScanResult.ItemRiskResult(
                idx = item.idx,
                riskLevel = (resolved.food?.overallRisk(avoidedCodes) ?: RiskLevel.UNKNOWN).name,
                matched = resolved.food?.isReady() == true,
                foodId = resolved.food?.id,
                name = resolved.food?.displayName(lang),
                koreanName = resolved.food?.koreanName(),
            )
        }

        recordHistory(input.memberId, items)

        return ScanResult(items = items, degraded = refinement.degraded)
    }

    private fun recordHistory(memberId: Long, items: List<ScanResult.ItemRiskResult>) {
        val readyFoodIds = items.filter { it.matched }.mapNotNull { it.foodId }.distinct()
        if (readyFoodIds.isEmpty()) return
        scanHistoryRepository.saveAll(readyFoodIds.map { ScanHistory.record(memberId, it) })
    }

    private fun resolveFoods(
        input: ScanInput,
        matchKeys: List<String>,
        refinedNames: Map<Int, InterpretedName>?,
    ): List<ResolvedItem?> {
        val lookups = input.items.indices.map { index ->
            lookupNameFor(matchKeys[index], input.items[index].rawMenuName, refinedNames, index)
        }
        val foodsByMatchKey = foodRepository.findByKoreanMatchKeys(lookups.filterNotNull().map { it.matchKey }.toSet())
        val registeredFoodsByName = foodRepository.createIncomplete(unregisteredFoodNames(lookups, foodsByMatchKey))

        return lookups.map { lookup ->
            if (lookup == null) return@map null

            val known = foodsByMatchKey[lookup.matchKey]
            if (known != null) return@map resolvedFrom(known)

            if (!lookup.confirmedByInterpreter) return@map UNRESOLVED

            val registered = registeredFoodsByName[lookup.koreanName] ?: return@map UNRESOLVED
            resolvedFrom(registered)
        }
    }

    private fun unregisteredFoodNames(lookups: List<MenuNameLookup?>, foodsByMatchKey: Map<String, Food>): Set<String> =
        lookups
            .filterNotNull()
            .filter { it.confirmedByInterpreter && it.matchKey !in foodsByMatchKey }
            .map { it.koreanName }
            .toSet()

    private fun resolvedFrom(food: Food): ResolvedItem {
        requireNotNull(food.id) { "매칭된 food 에 id 가 없습니다" }
        return ResolvedItem(food)
    }

    private fun lookupNameFor(
        matchKey: String,
        rawMenuName: String,
        refinedNames: Map<Int, InterpretedName>?,
        index: Int,
    ): MenuNameLookup? {
        if (matchKey.isBlank()) return null
        if (refinedNames == null) {
            return MenuNameLookup(koreanName = rawMenuName, matchKey = matchKey, confirmedByInterpreter = false)
        }
        return when (val refined = refinedNames.getValue(index)) {
            is InterpretedName.StandardName -> MenuNameLookup(
                koreanName = refined.korean,
                matchKey = KoreanMenuNameNormalizer.matchKey(refined.korean),
                confirmedByInterpreter = true,
            )
            InterpretedName.NotFood -> null
        }
    }

    private fun refineMenuNames(input: ScanInput, matchKeys: List<String>): Refinement {
        val refinableIndexes = matchKeys.indices.filter { matchKeys[it].isNotBlank() }
        if (refinableIndexes.isEmpty()) return Refinement(byItemIndex = null, degraded = false)

        val texts = refinableIndexes.map { input.items[it].rawMenuName }
        return try {
            val refined = interpreter.interpret(texts)
            require(refined.size == refinableIndexes.size) {
                "정제 결과 개수(${refined.size})가 요청(${refinableIndexes.size})과 다릅니다"
            }
            Refinement(byItemIndex = refinableIndexes.zip(refined).toMap(), degraded = false)
        } catch (e: Exception) {
            log.warn("정제 서비스 호출 실패 — 정규화 exact 매치 폴백", e)
            Refinement(byItemIndex = null, degraded = true)
        }
    }

    private data class Refinement(val byItemIndex: Map<Int, InterpretedName>?, val degraded: Boolean)

    private data class MenuNameLookup(
        val koreanName: String,
        val matchKey: String,
        val confirmedByInterpreter: Boolean,
    )

    private data class ResolvedItem(val food: Food?)

    companion object {
        private val UNRESOLVED = ResolvedItem(null)
    }
}
