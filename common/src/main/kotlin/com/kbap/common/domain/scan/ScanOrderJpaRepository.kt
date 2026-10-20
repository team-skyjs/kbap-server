package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanOrder
import org.springframework.data.jpa.repository.JpaRepository

interface ScanOrderJpaRepository : JpaRepository<ScanOrder, Long> {
    fun existsByOperationId(operationId: Long): Boolean
}
