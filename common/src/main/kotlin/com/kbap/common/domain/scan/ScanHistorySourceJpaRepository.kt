package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanHistorySource
import org.springframework.data.jpa.repository.JpaRepository

interface ScanHistorySourceJpaRepository : JpaRepository<ScanHistorySource, Long>
