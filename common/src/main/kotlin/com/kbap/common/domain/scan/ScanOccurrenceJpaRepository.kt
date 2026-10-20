package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanOccurrence
import org.springframework.data.jpa.repository.JpaRepository

interface ScanOccurrenceJpaRepository : JpaRepository<ScanOccurrence, Long> {
    fun findByOperationId(operationId: Long): List<ScanOccurrence>
}
