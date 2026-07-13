package com.meogo.domain.scan

import com.meogo.core.id.FoodId
import com.meogo.core.id.MemberId
import org.springframework.stereotype.Service

@Service
class ScanHistoryService internal constructor(
    private val scanHistoryJpaRepository: ScanHistoryJpaRepository,
) {
    fun saveAll(records: List<ScanHistory>) {
        scanHistoryJpaRepository.saveAll(records.map { ScanHistoryJpaEntity.from(it) })
    }

    fun findRecentReadyFoodIds(memberId: MemberId, limit: Int): List<FoodId> =
        scanHistoryJpaRepository.findRecentReadyFoodIds(memberId.value, limit).map(::FoodId)
}
