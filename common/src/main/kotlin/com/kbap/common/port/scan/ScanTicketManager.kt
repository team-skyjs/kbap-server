package com.kbap.common.port.scan

interface ScanTicketManager {
    fun issue(memberId: Long): IssuedScanTicket

    fun verify(ticket: String, memberId: Long): String
}

data class IssuedScanTicket(
    val ticket: String,
    val expiresInSeconds: Long,
)
