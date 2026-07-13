package com.meogo.infra.persistence.scan

import com.meogo.domain.scan.ScanHistory
import com.meogo.domain.scan.ScanHistoryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

@Repository
class ScanHistoryRepositoryAdapter(
    private val scanHistoryJpaRepository: ScanHistoryJpaRepository,
) : ScanHistoryRepository {
    override fun saveAll(records: List<ScanHistory>) {
        scanHistoryJpaRepository.saveAll(records.map { ScanHistoryJpaEntity.from(it) })
    }

    override fun findRecentReadyFoodIds(memberId: Long, limit: Int): List<Long> =
        scanHistoryJpaRepository.findRecentReadyFoodIds(memberId, PageRequest.of(0, limit))
}
