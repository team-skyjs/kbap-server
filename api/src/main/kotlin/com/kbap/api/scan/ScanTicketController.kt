package com.kbap.api.scan

import com.kbap.api.core.ApiPaths
import com.kbap.api.core.BaseResponse
import com.kbap.api.core.auth.AuthMemberId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping(ApiPaths.API + "/scans/tickets")
class ScanTicketController(
    private val scanTicketIssueService: ScanTicketIssueService,
) : ScanTicketApi {
    @PostMapping
    override fun issueTicket(
        @AuthMemberId memberId: Long,
    ): ResponseEntity<BaseResponse<ScanTicketResponse>> {
        val issued = scanTicketIssueService.issueScanTicket(memberId)
        return ResponseEntity.ok(BaseResponse.ok(ScanTicketResponse.from(issued)))
    }
}
