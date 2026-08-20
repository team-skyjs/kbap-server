package com.kbap.api.scan

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.member.model.Member
import com.kbap.common.port.llm.OcrItem
import com.kbap.common.port.scan.IssuedScanTicket
import com.kbap.common.port.scan.ScanReservationResult
import com.kbap.common.port.scan.ScanReservationStore
import com.kbap.common.port.scan.ScanTicketCodec
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.event.TransactionalEventListener

@Service
class ScanFacade(
    private val scanService: ScanService,
    private val memberService: MemberService,
    private val reservationStore: ScanReservationStore,
    private val ticketCodec: ScanTicketCodec,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun issueScanTicket(memberId: Long): IssuedScanTicket {
        val member = memberService.getMember(memberId)
        if (!member.isScanAllowed()) {
            throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
        }
        return ticketCodec.issue(memberId)
    }

    fun scanMenuBoardImage(
        memberId: Long,
        imagePath: String,
        ocrItems: List<OcrItem>,
        lang: LanguageCode,
    ): ScanResult = scan(memberId, imagePath, ocrItems, lang, requireDetectedMenu = false, reservationId = null)

    fun scanMenuBoardImageV2(memberId: Long, imagePath: String, lang: LanguageCode, scanTicket: String): ScanResult {
        val jti = ticketCodec.verify(scanTicket, memberId)
        return scan(memberId, imagePath, ocrItems = emptyList(), lang = lang, requireDetectedMenu = true, reservationId = jti)
    }

    private fun scan(
        memberId: Long,
        imagePath: String,
        ocrItems: List<OcrItem>,
        lang: LanguageCode,
        requireDetectedMenu: Boolean,
        reservationId: String?,
    ): ScanResult {
        val member = memberService.getMember(memberId)
        if (member.scanUnlocked) {
            val result = scanService.scan(member, imagePath, ocrItems, lang, requireDetectedMenu)
            memberService.increaseScanCount(memberId)
            return result
        }

        val reservationKey = reservationId ?: UUID.randomUUID().toString()
        when (reservationStore.reserve(memberId, reservationKey, member.scanCount, Member.FREE_SCAN_LIMIT)) {
            ScanReservationResult.LIMIT_EXCEEDED -> throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
            ScanReservationResult.DUPLICATE_REQUEST -> throw BusinessException(ErrorCode.DUPLICATE_SCAN_REQUEST)
            ScanReservationResult.RESERVED -> Unit
        }
        try {
            val result = scanService.scan(member, imagePath, ocrItems, lang, requireDetectedMenu)
            scanService.confirmScan(memberId, reservationKey)
            return result
        } catch (e: Exception) {
            releaseReservationQuietly(memberId, reservationKey)
            throw e
        }
    }

    @TransactionalEventListener
    fun releaseReservationOnCommit(event: ScanConfirmed) {
        releaseReservationQuietly(event.memberId, event.reservationKey)
    }

    private fun releaseReservationQuietly(memberId: Long, reservationId: String) {
        runCatching { reservationStore.release(memberId, reservationId) }
            .onFailure { log.warn("스캔 예약 해제 실패 — TTL 만료로 회수됨, memberId={}", memberId, it) }
    }
}
