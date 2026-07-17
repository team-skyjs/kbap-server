package com.kbap.domain.scan

import com.kbap.domain.scan.model.LlmCallCost
import org.springframework.data.jpa.repository.JpaRepository

internal interface LlmCallCostJpaRepository : JpaRepository<LlmCallCost, Long>
