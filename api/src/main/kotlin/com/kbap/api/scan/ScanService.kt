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
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScanService(
    private val foodService: FoodService,
    private val visionExtractor: MenuBoardVisionExtractor,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val ingredientRepository: IngredientJpaRepository,
    private val menuMatchingService: MenuMatchingService,
    private val memberService: MemberService,
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

        val matches = menuMatchingService.matchMenus(extracted, substituteSimilar)
        val avoidance = member.profile.avoidance()
        val orderedAvoidedCodes = avoidance.chosen.sortedBy { it.ordinal }
        val avoidedCodes = avoidance.codeNames
        val avoidanceCatalog = loadAvoidanceCatalog(orderedAvoidedCodes)
        val validIdxes = ocrItems.map { it.idx }.toSet()
        val usedIdxes = mutableSetOf<Int>()

        val items = mergeOptions(extracted).map { (menu, priceOptions) ->
            val key = KoreanMenuNameNormalizer.matchKey(menu.koreanName)
            val match = matches[key]
            val food = match?.food
            val substituted = match?.substituted == true
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
                substituted = substituted,
                originalScannedName = menu.koreanName.takeIf { substituted },
                substitutedFromFoodId = match?.substitutedFromFoodId,
                pendingNameKey = key.takeIf { !matched && match?.source == MenuMatchSource.PENDING_AI_ESTIMATE },
                notMenu = match?.source == MenuMatchSource.NOT_MENU,
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
