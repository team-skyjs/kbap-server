package com.kbap.api.scan

import com.kbap.common.port.scan.IssuedScanTicket
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "스캔 티켓 발급 응답 — 스캔 1회 시도의 자격 증명")
data class ScanTicketResponse(
    @field:Schema(
        description = "서버 서명 스캔 티켓. 이후 스캔 요청의 X-Scan-Ticket 헤더에 그대로 싣는다. 1회 시도용 — 시도마다 새로 발급받는다.",
        example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature",
    )
    val ticket: String,
    @field:Schema(description = "티켓 유효 시간(초). 만료 후 사용하면 400(SCAN-007) — 다시 발급받는다.", example = "300")
    val expiresInSeconds: Long,
) {
    companion object {
        fun from(issued: IssuedScanTicket): ScanTicketResponse =
            ScanTicketResponse(ticket = issued.ticket, expiresInSeconds = issued.expiresInSeconds)
    }
}
