package com.kbap.common.port.scan

import java.time.Duration

interface ScanReservationStore {
    fun reserve(memberId: Long, requestId: String, confirmedCount: Int, limit: Int, ttl: Duration? = null): ScanReservationResult

    fun release(memberId: Long, requestId: String)
}

enum class ScanReservationResult {
    RESERVED,
    LIMIT_EXCEEDED,
    DUPLICATE_REQUEST,
}
