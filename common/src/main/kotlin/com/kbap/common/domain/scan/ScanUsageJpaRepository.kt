package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanUsage
import org.springframework.data.jpa.repository.JpaRepository

interface ScanUsageJpaRepository : JpaRepository<ScanUsage, Long>
