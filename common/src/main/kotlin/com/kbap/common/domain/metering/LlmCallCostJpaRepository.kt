package com.kbap.common.domain.metering

import com.kbap.common.domain.metering.dto.DailyCostSum
import com.kbap.common.domain.metering.model.LlmCallCost
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface LlmCallCostJpaRepository : JpaRepository<LlmCallCost, Long> {
    @Query(
        """
        select new com.kbap.common.domain.metering.dto.DailyCostSum(cast(c.createdAt as LocalDate), sum(c.costUsd))
        from LlmCallCost c
        where c.createdAt >= :from
        group by cast(c.createdAt as LocalDate)
        """,
    )
    fun sumDailyCostUsdSince(@Param("from") from: LocalDateTime): List<DailyCostSum>
}
