package com.kbap.common.domain.scan

import com.kbap.common.domain.scan.model.ScanAttempt
import org.springframework.data.jpa.repository.JpaRepository

interface ScanAttemptJpaRepository : JpaRepository<ScanAttempt, Long>
