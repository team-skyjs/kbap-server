package com.kbap.domain.metering

import com.kbap.domain.metering.model.LlmCallCost
import org.springframework.data.jpa.repository.JpaRepository

interface LlmCallCostJpaRepository : JpaRepository<LlmCallCost, Long>
