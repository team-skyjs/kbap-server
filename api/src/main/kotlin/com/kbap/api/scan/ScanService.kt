package com.kbap.api.scan

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.util.KoreanMenuNameNormalizer
import com.kbap.common.domain.food.model.RiskLevel
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.MenuBoardVisionQuotaExhaustedException
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.OcrItem
import com.kbap.api.food.FoodService
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.ingredient.model.Ingredient
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.api.member.MemberService
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScanService(
    private val foodService: FoodService,
    private val visionExtractor: MenuBoardVisionExtractor,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val ingredientRepository: IngredientJpaRepository,
    private val similarFoodService: SimilarFoodService,
    private val memberService: MemberService,
    @Value("\${kbap.scan.vector-substitution.enabled:false}") private val vectorSubstitutionEnabled: Boolean = false,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun scan(
        member: Member,
        imagePath: String,
        ocrItems: List<OcrItem>,
        lang: LanguageCode,
        requireDetectedMenu: Boolean,
        substituteSimilar: Boolean,
    ): ScanResult {
        val extracted = try {
            visionExtractor.extract(imagePath, ocrItems)
        } catch (e: MenuBoardVisionRateLimitedException) {
            log.warn(
                "메뉴판 비전 rate-limit — kind={} retryAfterSeconds={} limits={} imagePath={}",
                if (e.exhausted) "EXHAUSTED" else "IMMEDIATE",
                e.retryAfterSeconds,
                e.message,
                imagePath,
                e,
            )
            throw BusinessException(
                ErrorCode.SCAN_RATE_LIMITED,
                payload = e.retryAfterSeconds?.let { mapOf("retryAfterSeconds" to it) },
            )
        } catch (e: MenuBoardVisionQuotaExhaustedException) {
            log.error("메뉴판 비전 quota 소진 — code={} imagePath={}", e.code, imagePath, e)
            throw BusinessException(ErrorCode.SCAN_VISION_UNAVAILABLE)
        } catch (e: MenuBoardVisionUnavailableException) {
            log.warn("메뉴판 비전 서버 장애 — imagePath={}", imagePath, e)
            throw BusinessException(ErrorCode.SCAN_VISION_UNAVAILABLE)
        } catch (e: Exception) {
            log.warn("메뉴판 비전 인식 실패 — imagePath={}", imagePath, e)
            throw BusinessException(ErrorCode.MENU_BOARD_RECOGNITION_FAILED)
        }
        if (requireDetectedMenu && extracted.isEmpty()) {
            throw BusinessException(ErrorCode.MENU_BOARD_NOT_DETECTED)
        }

        val resolved = resolveFoods(extracted)
        val foodsByMatchKey = resolved.foods
        val similarByMatchKey = if (substituteSimilar && vectorSubstitutionEnabled) {
            similarFoodService.resolveSimilarFoods(resolved.registeredNames).mapKeys { (name, _) -> KoreanMenuNameNormalizer.matchKey(name) }
        } else {
            emptyMap()
        }
        val avoidance = member.profile.avoidance()
        val orderedAvoidedCodes = avoidance.chosen.sortedBy { it.ordinal }
        val avoidedCodes = avoidance.codeNames
        val avoidanceCatalog = loadAvoidanceCatalog(orderedAvoidedCodes)
        val validIdxes = ocrItems.map { it.idx }.toSet()
        val usedIdxes = mutableSetOf<Int>()

        val items = mergeOptions(extracted).map { (menu, priceOptions) ->
            val key = KoreanMenuNameNormalizer.matchKey(menu.koreanName)
            val notMenu = key in resolved.notMenuKeys
            val similar = resolved.baseSubstitutes[key] ?: similarByMatchKey[key]
            val food = if (notMenu) null else similar ?: foodsByMatchKey[key]
            val matched = food?.isReady() == true
            val koreanName = if (matched) food!!.displayName(LanguageCode.KO) else menu.koreanName
            ScanResult.ItemRiskResult(
                idx = menu.matchedIdx?.takeIf { it in validIdxes && usedIdxes.add(it) },
                riskLevel = (food?.overallRisk(avoidedCodes) ?: RiskLevel.UNKNOWN).name,
                matched = matched,
                foodId = food?.id,
                name = if (matched) food!!.displayName(lang) else koreanName,
                koreanName = koreanName,
                price = menu.priceKrw,
                imageRef = foodService.resolveImageUrlOrDefault(food.takeIf { matched }),
                avoidances = toAvoidances(member, matched, food, avoidance, avoidanceCatalog, lang),
                substituted = similar != null,
                originalScannedName = menu.koreanName.takeIf { similar != null },
                substitutedFromFoodId = foodsByMatchKey[KoreanMenuNameNormalizer.matchKey(menu.koreanName)]?.id?.takeIf { similar != null && it != food?.id },
                pendingNameKey = key.takeIf { !matched && similar == null && !notMenu && it.isNotBlank() },
                notMenu = notMenu,
                spiciness = food?.spiciness?.takeIf { matched },
                priceOptions = priceOptions,
            )
        }

        return ScanResult(items = items, degraded = false)
    }

    private fun loadAvoidanceCatalog(avoidedCodes: List<IngredientCode>): Map<IngredientCode, Ingredient> {
        if (avoidedCodes.isEmpty()) return emptyMap()
        return ingredientRepository.findByCodeIn(avoidedCodes.toSet()).associateBy { it.code }
    }

    private fun toAvoidances(
        member: Member,
        matched: Boolean,
        food: Food?,
        avoidance: Avoidance,
        catalog: Map<IngredientCode, Ingredient>,
        lang: LanguageCode,
    ): List<ScanResult.AvoidanceOverlap>? {
        if (!member.onboardingCompleted) return null
        if (!matched || food == null) return emptyList()
        return avoidance.chosen.sortedBy { it.ordinal }.map { code ->
            val overlapped = food.overlappedIngredients(avoidance.codeNamesCoveredBy(code))
            ScanResult.AvoidanceOverlap(
                code = code.name,
                name = catalog[code]?.displayName(lang) ?: code.label,
                overlapped = overlapped.isNotEmpty(),
                riskLevel = overlapped.takeIf { it.isNotEmpty() }?.let { RiskLevel.aggregate(it.map { i -> i.riskLevel() }) }?.name,
            )
        }
    }

    private fun mergeOptions(extracted: List<ExtractedMenu>): List<Pair<ExtractedMenu, List<ScanResult.PriceOption>?>> {
        val groups = extracted.groupBy { KoreanMenuNameNormalizer.matchKey(it.koreanName).ifBlank { it.koreanName } }
        val merged = groups.mapValues { (_, group) ->
            group.takeIf { members -> members.all { it.optionLabel != null } }
                ?.map { ScanResult.PriceOption(it.optionLabel, it.priceKrw) }?.distinct()?.takeIf { it.size > 1 }
        }
        val emitted = mutableSetOf<String>()
        return extracted.mapNotNull { menu ->
            val key = KoreanMenuNameNormalizer.matchKey(menu.koreanName).ifBlank { menu.koreanName }
            val options = merged.getValue(key) ?: return@mapNotNull menu to null
            if (!emitted.add(key)) return@mapNotNull null
            menu.copy(matchedIdx = groups.getValue(key).firstNotNullOfOrNull { it.matchedIdx }) to options
        }
    }

    private data class ResolvedFoods(
        val foods: Map<String, Food>,
        val registeredNames: List<String>,
        val baseSubstitutes: Map<String, Food> = emptyMap(),
        val notMenuKeys: Set<String> = emptySet(),
    )

    private fun resolveFoods(extracted: List<ExtractedMenu>): ResolvedFoods {
        val menusByMatchKey = extracted
            .map { KoreanMenuNameNormalizer.matchKey(it.koreanName) to it }
            .filter { (matchKey, _) -> matchKey.isNotBlank() }
            .groupBy({ it.first }, { it.second })
        val baseKeyByMatchKey = menusByMatchKey.mapValues { (matchKey, menus) ->
            menus.firstNotNullOfOrNull { it.baseName }?.let(KoreanMenuNameNormalizer::matchKey)?.takeIf { it.isNotBlank() && it != matchKey }
        }
        val known = foodService.getFoodsByKoreanNames(menusByMatchKey.keys + baseKeyByMatchKey.values.filterNotNull())
        val baseSubstitutes = mutableMapOf<String, Food>()
        val notMenuKeys = mutableSetOf<String>()
        val toRegister = mutableMapOf<String, String>()
        menusByMatchKey.forEach { (matchKey, menus) ->
            if (known[matchKey]?.isReady() == true) return@forEach
            val baseFood = baseKeyByMatchKey[matchKey]?.let { known[it] }?.takeIf { it.isReady() }
            when {
                baseFood != null -> baseSubstitutes[matchKey] = baseFood
                NotMenuFilter.isNotMenu(menus.first().koreanName) -> notMenuKeys += matchKey
                else -> toRegister[matchKey] = menus.first().koreanName
            }
        }
        val newlyRegistered = toRegister - known.keys
        return ResolvedFoods(
            foods = known + foodService.createIncomplete(newlyRegistered),
            registeredNames = toRegister.values.toList(),
            baseSubstitutes = baseSubstitutes,
            notMenuKeys = notMenuKeys,
        )
    }

    @Transactional
    fun recordHistory(memberId: Long, items: List<ScanResult.ItemRiskResult>) {
        if (items.isEmpty()) return
        scanHistoryRepository.saveAll(items.map { ScanHistory.record(memberId = memberId, price = it.price, foodId = it.foodId) })
    }

    @Transactional
    fun recordChargedScan(memberId: Long, items: List<ScanResult.ItemRiskResult>) {
        memberService.increaseScanCountWithinLimit(memberId)
        recordHistory(memberId, items)
    }
}
