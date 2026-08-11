package com.kbap.api.scan

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.util.KoreanMenuNameNormalizer
import com.kbap.common.domain.food.model.RiskLevel
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.OcrItem
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.member.MemberService
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.api.image.ImageUploadService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScanService(
    private val foodService: FoodService,
    private val memberService: MemberService,
    private val imageUploadService: ImageUploadService,
    private val visionExtractor: MenuBoardVisionExtractor,
    private val similarFoodResolver: SimilarFoodResolver,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun scanMenuBoardImage(memberId: Long, imagePath: String, ocrItems: List<OcrItem>, lang: LanguageCode): ScanResult =
        scan(memberId, imagePath, ocrItems, lang, similarFoodFallback = false)

    fun scanMenuBoardImageV2(memberId: Long, imagePath: String, lang: LanguageCode): ScanResult =
        scan(memberId, imagePath, ocrItems = emptyList(), lang = lang, similarFoodFallback = true)

    private fun scan(
        memberId: Long,
        imagePath: String,
        ocrItems: List<OcrItem>,
        lang: LanguageCode,
        similarFoodFallback: Boolean,
    ): ScanResult {
        val member = memberService.getMember(memberId)

        val extracted = try {
            visionExtractor.extract(imagePath, ocrItems)
        } catch (e: Exception) {
            log.warn("메뉴판 비전 인식 실패 — imagePath={}", imagePath, e)
            throw BusinessException(ErrorCode.MENU_BOARD_RECOGNITION_FAILED)
        }

        val foodsByMatchKey = resolveFoods(extracted)
        val avoidedCodes = memberService.getAvoidedCodes(memberId).map { it.name }.toSet()
        val validIdxes = ocrItems.map { it.idx }.toSet()
        val usedIdxes = mutableSetOf<Int>()
        val similarFoodsByName = resolveSimilarFoods(similarFoodFallback, extracted, foodsByMatchKey)

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
                similarFood = if (matched) null else similarFoodsByName[menu.koreanName]?.let { toSimilarFood(it, lang) },
            )
        }

        recordHistory(memberId, imagePath, extracted, items)
        memberService.increaseScanCount(memberId)

        return ScanResult(items = items, degraded = false, currency = member.profile.currency)
    }

    private fun resolveSimilarFoods(
        enabled: Boolean,
        extracted: List<ExtractedMenu>,
        foodsByMatchKey: Map<String, Food>,
    ): Map<String, Food> {
        if (!enabled) return emptyMap()
        val missNames = extracted
            .filter { foodsByMatchKey[KoreanMenuNameNormalizer.matchKey(it.koreanName)]?.isReady() != true }
            .map { it.koreanName }
        return similarFoodResolver.resolveSimilarFoods(missNames)
    }

    private fun toSimilarFood(food: Food, lang: LanguageCode): ScanResult.SimilarFood {
        val name = food.displayName(lang)
        return ScanResult.SimilarFood(
            foodId = food.id,
            name = name,
            koreanName = food.displayName(LanguageCode.KO).takeIf { it != name },
            description = food.description(lang),
            imageRef = foodService.resolveImageUrl(food),
        )
    }

    @Transactional(readOnly = true)
    fun getRecentReadyFoodIds(memberId: Long, limit: Int): List<Long> =
        scanHistoryRepository.findRecentReadyFoodIds(memberId, limit)

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
