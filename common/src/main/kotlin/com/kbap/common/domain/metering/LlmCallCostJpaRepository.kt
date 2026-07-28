package com.kbap.common.domain.metering

import com.kbap.common.domain.metering.model.LlmCallCost
import org.springframework.data.jpa.repository.JpaRepository

interface LlmCallCostJpaRepository : JpaRepository<LlmCallCost, Long>
