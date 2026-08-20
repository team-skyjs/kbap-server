package com.kbap.api.scan

import com.kbap.api.member.MemberService
import com.kbap.common.core.error.BusinessException
import com.kbap.common.core.error.ErrorCode
import com.kbap.common.port.scan.IssuedScanTicket
import com.kbap.common.port.scan.ScanTicketCodec
import org.springframework.stereotype.Service

@Service
class ScanTicketService(
    private val memberService: MemberService,
    private val ticketCodec: ScanTicketCodec,
) {
    fun issueScanTicket(memberId: Long): IssuedScanTicket {
        val member = memberService.getMember(memberId)
        if (!member.isScanAllowed()) {
            throw BusinessException(ErrorCode.SCAN_LIMIT_EXCEEDED)
        }
        return ticketCodec.issue(memberId)
    }
}
