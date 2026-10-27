package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanMergedItem
import org.springframework.data.jpa.repository.JpaRepository

interface ScanMergedItemJpaRepository : JpaRepository<ScanMergedItem, Long> {
    fun findByOperationIdAndMergeKey(operationId: Long, mergeKey: ByteArray): ScanMergedItem?

    fun findByOperationId(operationId: Long): List<ScanMergedItem>
}
