package com.kbap.api.scan

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.util.KoreanMenuNameNormalizer
import com.kbap.common.domain.food.model.RiskLevel
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.OcrItem
import com.kbap.api.food.FoodService
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.ingredient.model.Ingredient
import com.kbap.common.domain.ingredient.model.IngredientCode
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun scan(
        member: Member,
        imagePath: String,
        ocrItems: List<OcrItem>,
        lang: LanguageCode,
        requireDetectedMenu: Boolean,
    ): ScanResult {
        val extracted = try {
            visionExtractor.extract(imagePath, ocrItems)
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

        val foodsByMatchKey = resolveFoods(extracted)
        val orderedAvoidedCodes = member.profile.avoidedCodes().sortedBy { it.ordinal }
        val avoidedCodes = orderedAvoidedCodes.map { it.name }.toSet()
        val avoidanceCatalog = loadAvoidanceCatalog(orderedAvoidedCodes)
        val validIdxes = ocrItems.map { it.idx }.toSet()
        val usedIdxes = mutableSetOf<Int>()

        val items = extracted.map { menu ->
            val food = foodsByMatchKey[KoreanMenuNameNormalizer.matchKey(menu.koreanName)]
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
                avoidances = toAvoidances(member, matched, food, orderedAvoidedCodes, avoidanceCatalog, lang),
            )
        }

        recordHistory(member.id, imagePath, extracted, items)

        return ScanResult(items = items, degraded = false)
    }

    @Transactional(readOnly = true)
    fun getRecentReadyFoodIds(memberId: Long, limit: Int): List<Long> =
        scanHistoryRepository.findRecentReadyFoodIds(memberId, limit)

    private fun loadAvoidanceCatalog(avoidedCodes: List<IngredientCode>): Map<IngredientCode, Ingredient> {
        if (avoidedCodes.isEmpty()) return emptyMap()
        return ingredientRepository.findByCodeIn(avoidedCodes.toSet()).associateBy { it.code }
    }

    private fun toAvoidances(
        member: Member,
        matched: Boolean,
        food: Food?,
        orderedAvoidedCodes: List<IngredientCode>,
        catalog: Map<IngredientCode, Ingredient>,
        lang: LanguageCode,
    ): List<ScanResult.AvoidanceOverlap>? {
        if (!member.onboardingCompleted) return null
        if (!matched || food == null) return emptyList()
        val overlappedByCode = food.overlappedIngredients(orderedAvoidedCodes.map { it.name }.toSet())
            .associateBy { it.code }
        return orderedAvoidedCodes.map { code ->
            val overlapped = overlappedByCode[code.name]
            ScanResult.AvoidanceOverlap(
                code = code.name,
                name = catalog[code]?.displayName(lang) ?: code.label,
                overlapped = overlapped != null,
                riskLevel = overlapped?.riskLevel()?.name,
            )
        }
    }

    private fun resolveFoods(extracted: List<ExtractedMenu>): Map<String, Food> {
        val displayNamesByMatchKey = extracted
            .map { KoreanMenuNameNormalizer.matchKey(it.koreanName) to it.koreanName }
            .filter { (matchKey, _) -> matchKey.isNotBlank() }
            .distinctBy { (matchKey, _) -> matchKey }
            .toMap()
        val known = foodService.getFoodsByKoreanNames(displayNamesByMatchKey.keys)
        val registered = foodService.createIncomplete(displayNamesByMatchKey - known.keys)
        return known + registered
    }

    private fun recordHistory(
        memberId: Long,
        imagePath: String,
        extracted: List<ExtractedMenu>,
        items: List<ScanResult.ItemRiskResult>,
    ) {
        if (extracted.isEmpty()) return
        val histories = extracted.mapIndexed { index, menu ->
            ScanHistory.record(
                memberId = memberId,
                imagePath = imagePath,
                menuName = menu.name,
                koreanName = menu.koreanName,
                price = menu.priceKrw,
                foodId = items[index].foodId,
            )
        }
        scanHistoryRepository.saveAll(histories)
    }
}
