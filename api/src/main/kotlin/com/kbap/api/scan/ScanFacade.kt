package com.kbap.api.scan

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.Member
import com.kbap.common.port.llm.OcrItem
import com.kbap.common.port.scan.ScanReservationResult
import com.kbap.common.port.scan.ScanReservationStore
import com.kbap.common.port.scan.ScanTicketManager
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class ScanFacade(
    private val scanService: ScanService,
    private val memberService: MemberService,
    private val reservationStore: ScanReservationStore,
    private val ticketManager: ScanTicketManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun scanMenuBoardImage(
        memberId: Long,
        imagePath: String,
        ocrItems: List<OcrItem>,
        lang: LanguageCode,
    ): ScanResult {
        val member = memberService.getMember(memberId)
        val result = scanService.scan(member, imagePath, ocrItems, lang, requireDetectedMenu = false)
        memberService.increaseScanCount(memberId)
        return result
    }

    fun scanMenuBoardImageV2(memberId: Long, imagePath: String, lang: LanguageCode, scanTicket: String): ScanResult {
        val jti = ticketManager.verify(scanTicket, memberId)
        val member = memberService.getMember(memberId)
        when (reservationStore.reserve(memberId, jti, member.scanCount, limitFor(member))) {
            ScanReservationResult.LIMIT_EXCEEDED -> throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
            ScanReservationResult.DUPLICATE_REQUEST -> throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
            ScanReservationResult.RESERVED -> Unit
        }
        try {
            if (!member.scanUnlocked && !memberService.getMember(memberId).isScanAllowed()) {
                throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
            }
            val result = scanService.scan(member, imagePath, ocrItems = emptyList(), lang = lang, requireDetectedMenu = true)
            memberService.increaseScanCount(memberId)
            releaseReservationQuietly(memberId, jti)
            return result
        } catch (e: Exception) {
            releaseReservationQuietly(memberId, jti)
            throw e
        }
    }

    private fun limitFor(member: Member): Int =
        if (member.scanUnlocked) Int.MAX_VALUE else Member.FREE_SCAN_LIMIT

    private fun releaseReservationQuietly(memberId: Long, reservationId: String) {
        runCatching { reservationStore.release(memberId, reservationId) }
            .onFailure { log.warn("스캔 예약 해제 실패 — TTL 만료로 회수됨, memberId={}", memberId, it) }
    }
}
