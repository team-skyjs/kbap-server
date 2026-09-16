package com.kbap.common.domain.report

import com.kbap.common.domain.report.model.Report
import org.springframework.data.jpa.repository.JpaRepository

interface ReportJpaRepository : JpaRepository<Report, Long>
