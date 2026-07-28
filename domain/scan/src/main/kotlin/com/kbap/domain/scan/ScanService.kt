package com.kbap.domain.scan

import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.core.lang.LanguageCode
import com.kbap.common.core.menu.KoreanMenuNameNormalizer
import com.kbap.common.core.risk.RiskLevel
import com.kbap.common.core.scan.ExtractedMenu
import com.kbap.common.core.scan.MenuBoardVisionExtractor
import com.kbap.common.core.scan.OcrItem
import com.kbap.common.domain.food.FoodService
import com.kbap.common.domain.food.model.Food
import com.kbap.domain.image.ImageUploadService
import com.kbap.common.domain.member.MemberService
import com.kbap.domain.scan.dto.ScanResult
import com.kbap.domain.scan.model.ScanHistory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScanService(
    private val foodService: FoodService,
    private val memberService: MemberService,
    private val imageUploadService: ImageUploadService,
    private val visionExtractor: MenuBoardVisionExtractor,
    private val scanHistoryRepository: ScanHistoryJpaRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    // 의도적 무트랜잭션 — 비전 인식(외부 호출)을 트랜잭션 밖에 두고(헌법: 외부 호출 tx 밖),
    // 매칭·이력 저장·스캔 카운트는 각 도메인 서비스·리포지토리의 트랜잭션에 위임한다.
    fun scanMenuBoardImage(memberId: Long, imagePath: String, ocrItems: List<OcrItem>, lang: LanguageCode): ScanResult {
    // TODO     imageUploadService.verifyImageAccess(memberId, imagePath)
    // TODO        ?: throw BusinessException(ErrorCode.SCAN_IMAGE_NOT_VERIFIED)

        memberService.getMember(memberId)

        val extracted = try {
            visionExtractor.extract(imagePath, ocrItems)
        } catch (e: Exception) {
            log.warn("메뉴판 비전 인식 실패 — imagePath={}", imagePath, e)
            throw BusinessException(ErrorCode.MENU_BOARD_RECOGNITION_FAILED)
        }

        val foodsByMatchKey = resolveFoods(extracted)
        val avoidedCodes = memberService.getAvoidedCodes(memberId).map { it.name }.toSet()
        val validIdxes = ocrItems.map { it.idx }.toSet()

        val items = extracted.map { menu ->
            val food = foodsByMatchKey[KoreanMenuNameNormalizer.matchKey(menu.koreanName)]
            val matched = food?.isReady() == true
            val koreanName = if (matched) food!!.koreanName() else menu.koreanName
            ScanResult.ItemRiskResult(
                idx = menu.matchedIdx?.takeIf { it in validIdxes },
                riskLevel = (food?.overallRisk(avoidedCodes) ?: RiskLevel.UNKNOWN).name,
                matched = matched,
                foodId = food?.id,
                name = if (matched) food!!.displayName(lang) else koreanName,
                koreanName = koreanName,
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

    // 저장·조회 모두 정규화된 이름 기준 — korean_name 은 항상 정규화 상태를 유지한다
    private fun resolveFoods(extracted: List<ExtractedMenu>): Map<String, Food> {
        val matchKeys = extracted
            .map { KoreanMenuNameNormalizer.matchKey(it.koreanName) }
            .filter { it.isNotBlank() }
            .toSet()
        val known = foodService.getFoodsByKoreanNames(matchKeys)
        val registered = foodService.createIncomplete(matchKeys - known.keys)
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
