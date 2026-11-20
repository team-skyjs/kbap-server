package com.kbap.api.scan

import com.kbap.api.food.FoodService
import com.kbap.api.member.MemberService
import com.kbap.api.scan.MenuNameEstimateService.PendingItem
import com.kbap.api.scan.MenuNameEstimateService.PendingScan
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.RiskLevel
import com.kbap.common.domain.ingredient.IngredientJpaRepository
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.Ingredient
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.domain.member.model.Member
import com.kbap.common.domain.scan.ScanHistoryJpaRepository
import com.kbap.common.domain.scan.model.ScanHistory
import com.kbap.common.port.llm.ExtractedMenu
import com.kbap.common.port.llm.MenuBoardImageClient
import com.kbap.common.port.llm.MenuBoardVisionQuotaExhaustedException
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.scan.ScanTicketManager
import com.kbap.common.util.KoreanMenuNameNormalizer
import java.time.Duration
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

@Service
class ScanService(
    private val ticketManager: ScanTicketManager,
    private val memberService: MemberService,
    private val foodService: FoodService,
    private val menuBoardImageClient: MenuBoardImageClient,
    private val ingredientRepository: IngredientJpaRepository,
    private val menuMatchingService: MenuMatchingService,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
    private val menuNameEstimates: MenuNameEstimateService,
    @Value("\${kbap.scan.menu-estimate.wait:45s}") private val pendingWait: Duration,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun scan(memberId: Long, imagePath: String, lang: LanguageCode, scanTicket: String): ScanResult {
        // 1. 티켓 검증 및 DB 이용 횟수 선차감
        ticketManager.verify(scanTicket, memberId)
        val member = memberService.getMember(memberId)
        memberService.increaseScanCountWithinLimit(memberId)

        val items: List<ScanResult.ItemRiskResult>
        try {
            // 2. 메뉴판 판독 및 음식 매칭
            val menus = readMenuItemsFromImage(imagePath)
            val matches = menuMatchingService.matchMenus(menus, allowVectorMatch = true)

            // 3. 결과 구성 및 스캔 이력 저장
            items = buildScanItems(member, menus, matches, lang)
            saveScanHistory(memberId, items)
        } catch (e: Exception) {
            memberService.refundScan(memberId)
            throw e
        }

        // 4. 미등록 메뉴 추정 준비
        return preparePendingItems(member, ScanResult(items = items, degraded = false))
    }

    private fun saveScanHistory(memberId: Long, items: List<ScanResult.ItemRiskResult>) {
        if (items.isEmpty()) return
        val historyRows = mutableListOf<ScanHistory>()
        for (item in items) {
            historyRows.add(ScanHistory.record(memberId = memberId, price = item.price, foodId = item.foodId))
        }
        scanHistoryRepository.saveAll(historyRows)
    }

    private fun preparePendingItems(member: Member, result: ScanResult): ScanResult {
        val pendingItems = mutableListOf<PendingItem>()
        val responseItems = result.items.toMutableList()
        for ((index, item) in result.items.withIndex()) {
            val nameKey = item.pendingNameKey ?: continue
            val pendingId = "p$index"
            pendingItems.add(
                PendingItem(
                    pendingId = pendingId,
                    nameKey = nameKey,
                    koreanName = item.koreanName.orEmpty(),
                    foodId = item.foodId,
                    priceKrw = item.price,
                    priceOptions = item.priceOptions,
                ),
            )
            responseItems[index] = item.copy(pendingId = pendingId)
        }
        if (pendingItems.isEmpty()) return result

        val scanId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val state = PendingScan(
            memberId = member.id,
            deadlineMillis = now + pendingWait.toMillis(),
            items = pendingItems,
            imageDeadlineMillis = now + Duration.ofSeconds(60).toMillis(),
        )
        try {
            menuNameEstimates.savePendingScan(scanId, state)
        } catch (e: RuntimeException) {
            log.warn("메뉴 추정 대기 상태 저장 실패 — scanId 없이 현행 응답", e)
            return result
        }
        if (!member.isBot) {
            try {
                menuNameEstimates.schedule(member.id, pendingItems)
            } catch (e: RuntimeException) {
                log.warn("메뉴 추정 예약 실패 — 추정 없이 마감 뒤 UNKNOWN 으로 끝난다 scanId={}", scanId, e)
            }
        }
        return result.copy(scanId = scanId, items = responseItems)
    }

    private fun readMenuItemsFromImage(imagePath: String): List<ExtractedMenu> {
        val menuItems = callMenuBoardVision(imagePath)
        if (menuItems.isEmpty()) {
            throw BusinessException(ErrorCode.MENU_BOARD_NOT_DETECTED)
        }

        return menuItems
    }

    private fun callMenuBoardVision(imagePath: String): List<ExtractedMenu> {
        return try {
            menuBoardImageClient.readMenuItems(imagePath, ocrItems = emptyList())
        } catch (e: MenuBoardVisionRateLimitedException) {
            log.warn(
                "메뉴판 비전 rate-limit — kind={} retryAfterSeconds={} limits={} imagePath={}",
                if (e.exhausted) "EXHAUSTED" else "IMMEDIATE",
                e.retryAfterSeconds,
                e.message,
                imagePath,
                e,
            )
            val seconds = e.retryAfterSeconds
            val retryHint = if (seconds == null) null else mapOf("retryAfterSeconds" to seconds)
            throw BusinessException(ErrorCode.SCAN_RATE_LIMITED, payload = retryHint)
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
    }

    private fun buildScanItems(
        member: Member,
        extracted: List<ExtractedMenu>,
        matches: Map<String, MenuMatch>,
        lang: LanguageCode,
    ): List<ScanResult.ItemRiskResult> {
        val avoidance = member.profile.avoidance()
        val orderedAvoidedCodes = avoidance.chosen.sortedBy { it.ordinal }
        val avoidedCodes = avoidance.codeNames
        val avoidanceCatalog = loadAvoidanceCatalog(orderedAvoidedCodes)

        return mergeOptions(extracted).map { (menu, priceOptions) ->
            val key = KoreanMenuNameNormalizer.matchKey(menu.koreanName)
            val match = matches[key]
            val food = match?.food
            val substituted = match?.substituted == true
            val matched = food?.isReady() == true
            val koreanName = if (matched) food!!.displayName(LanguageCode.KO) else menu.koreanName
            ScanResult.ItemRiskResult(
                idx = null,
                riskLevel = (food?.overallRisk(avoidedCodes) ?: RiskLevel.UNKNOWN).name,
                matched = matched,
                foodId = food?.id,
                name = if (matched) food!!.displayName(lang) else koreanName,
                koreanName = koreanName,
                price = menu.priceKrw,
                imageRef = foodService.resolveImageUrlOrDefault(food.takeIf { matched }),
                avoidances = toAvoidances(member, matched, food, avoidance, avoidanceCatalog, lang),
                substituted = substituted,
                originalScannedName = menu.koreanName.takeIf { substituted || match?.source == MenuMatchSource.BASE_NAME },
                substitutedFromFoodId = match?.substitutedFromFoodId,
                pendingNameKey = key.takeIf { !matched && match?.source == MenuMatchSource.PENDING_AI_ESTIMATE },
                notMenu = match?.source == MenuMatchSource.NOT_MENU,
                spiciness = food?.spiciness?.takeIf { matched },
                priceOptions = priceOptions,
            )
        }
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
}
