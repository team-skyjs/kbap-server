package com.meogo.core.scan

interface ScanHistoryRepository {
    fun saveAll(records: List<ScanHistory>)

    fun findRecentReadyFoodIds(memberId: Long, limit: Int): List<Long>
}
