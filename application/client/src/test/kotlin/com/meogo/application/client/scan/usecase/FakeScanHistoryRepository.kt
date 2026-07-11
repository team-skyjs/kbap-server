package com.meogo.application.client.scan.usecase

import com.meogo.core.scan.ScanHistory
import com.meogo.core.scan.ScanHistoryRepository

class FakeScanHistoryRepository : ScanHistoryRepository {
    val saved = mutableListOf<ScanHistory>()
    var saveAllCallCount = 0
        private set

    override fun saveAll(records: List<ScanHistory>) {
        saveAllCallCount++
        saved += records
    }

    override fun findRecentReadyFoodIds(memberId: Long, limit: Int): List<Long> =
        saved.filter { it.memberId == memberId }.map { it.foodId }.distinct().takeLast(limit).reversed()
}
