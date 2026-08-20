package com.kbap.common.port.scan

interface ScanReservationStore {
    fun reserve(memberId: Long, requestId: String, confirmedCount: Int, limit: Int): ScanReservationResult

    fun release(memberId: Long, requestId: String)
}

enum class ScanReservationResult {
    RESERVED,
    LIMIT_EXCEEDED,
    DUPLICATE_REQUEST,
}
