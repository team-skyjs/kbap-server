package com.meogo.domain.scan

import org.springframework.stereotype.Service

@Service
class ScanHistoryService internal constructor(
    private val scanHistoryJpaRepository: ScanHistoryJpaRepository,
) {
    fun saveAll(records: List<ScanHistory>) {
        scanHistoryJpaRepository.saveAll(records.map { ScanHistoryJpaEntity.from(it) })
    }

    fun findRecentReadyFoodIds(memberId: Long, limit: Int): List<Long> =
        scanHistoryJpaRepository.findRecentReadyFoodIds(memberId, limit)
}
