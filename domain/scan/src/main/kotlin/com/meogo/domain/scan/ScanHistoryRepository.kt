package com.meogo.domain.scan

interface ScanHistoryRepository {
    fun saveAll(records: List<ScanHistory>)

    fun findRecentReadyFoodIds(memberId: Long, limit: Int): List<Long>
}
