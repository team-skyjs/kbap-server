package com.kbap.api.scan

import com.kbap.api.member.MemberService
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ScanConfirmService(
    private val memberService: MemberService,
    private val eventPublisher: ApplicationEventPublisher,
) {
    @Transactional
    fun confirmScan(memberId: Long, reservationKey: String) {
        memberService.increaseScanCount(memberId)
        eventPublisher.publishEvent(ScanConfirmed(memberId, reservationKey))
    }
}
