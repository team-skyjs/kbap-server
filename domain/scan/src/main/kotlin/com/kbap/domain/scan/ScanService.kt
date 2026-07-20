package com.kbap.domain.scan

import com.kbap.core.error.BusinessException
import com.kbap.core.error.ErrorCode
import com.kbap.core.lang.LanguageCode
import com.kbap.core.menu.KoreanMenuNameNormalizer
import com.kbap.core.risk.RiskLevel
import com.kbap.core.scan.ExtractedMenu
import com.kbap.core.scan.MenuBoardVisionExtractor
import com.kbap.core.scan.OcrItem
import com.kbap.domain.food.FoodService
import com.kbap.domain.food.model.Food
import com.kbap.domain.image.ImageUploadService
import com.kbap.domain.member.MemberService
import com.kbap.domain.scan.dto.ScanResult
import com.kbap.domain.scan.model.ScanHistory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScanService internal constructor(
    private val foodService: FoodService,
    private val memberService: MemberService,
    private val imageUploadService: ImageUploadService,
    private val visionExtractor: MenuBoardVisionExtractor,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 의도적 무트랜잭션 — 비전 인식(외부 호출)을 트랜잭션 밖에 두고(헌법: 외부 호출 tx 밖),
    // 매칭·이력 저장·스캔 카운트는 각 도메인 서비스·리포지토리의 트랜잭션에 위임한다.
    fun scanMenuBoardImage(memberId: Long, imagePath: String, ocrItems: List<OcrItem>): ScanResult {
    // TODO     imageUploadService.verifyImageAccess(memberId, imagePath)
    // TODO        ?: throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)

        val extracted = try {
            visionExtractor.extract(imagePath, ocrItems)
        } catch (e: Exception) {
            log.warn("메뉴판 비전 인식 실패 — imagePath={}", imagePath, e)
            throw BusinessException(ErrorCode.MENU_BOARD_RECOGNITION_FAILED)
        }

        val foodsByMatchKey = resolveFoods(extracted)
        val avoidedCodes = memberService.getAvoidedCodes(memberId).map { it.name }.toSet()
        val lang = memberService.getMember(memberId).profile.appLanguage ?: LanguageCode.KO
        val validIdxes = ocrItems.map { it.idx }.toSet()

        val items = extracted.map { menu ->
            val food = foodsByMatchKey[KoreanMenuNameNormalizer.matchKey(menu.koreanName)]
            val matched = food?.isReady() == true
            ScanResult.ItemRiskResult(
                // LLM 이 목록에 없는 idx 를 반환하면(할루시네이션) 매칭 없음으로 처리한다.
                idx = menu.matchedIdx?.takeIf { it in validIdxes },
                riskLevel = (food?.overallRisk(avoidedCodes) ?: RiskLevel.UNKNOWN).name,
                matched = matched,
                foodId = food?.id,
                name = if (matched) food!!.displayName(lang) else menu.name,
                koreanName = food?.koreanName() ?: menu.koreanName,
                price = menu.priceKrw,
            )
        }

        recordHistory(memberId, imagePath, extracted, items)
        memberService.increaseScanCount(memberId)

        return ScanResult(items = items, degraded = false)
    }

    @Transactional(readOnly = true)
    fun getRecentReadyFoodIds(memberId: Long, limit: Int): List<Long> =
        scanHistoryRepository.findRecentReadyFoodIds(memberId, limit)

    private fun resolveFoods(extracted: List<ExtractedMenu>): Map<String, Food> {
        val matchKeys = extracted
            .map { KoreanMenuNameNormalizer.matchKey(it.koreanName) }
            .filter { it.isNotBlank() }
            .toSet()
        val known = foodService.getFoodsByKoreanMatchKeys(matchKeys)

        val unknownNames = extracted
            .filter { KoreanMenuNameNormalizer.matchKey(it.koreanName).let { key -> key.isNotBlank() && key !in known } }
            .map { it.koreanName }
            .toSet()
        val registered = foodService.createIncomplete(unknownNames)

        val byMatchKey = known.toMutableMap()
        registered.forEach { (koreanName, food) -> byMatchKey[KoreanMenuNameNormalizer.matchKey(koreanName)] = food }
        return byMatchKey
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
