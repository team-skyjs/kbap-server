package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanOperationResult
import org.springframework.data.jpa.repository.JpaRepository

interface ScanOperationResultJpaRepository : JpaRepository<ScanOperationResult, Long>
