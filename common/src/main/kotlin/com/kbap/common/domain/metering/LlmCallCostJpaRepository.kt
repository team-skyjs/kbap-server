package com.kbap.common.domain.metering

import com.kbap.common.domain.metering.dto.DailyModelCostSum
import com.kbap.common.domain.metering.model.LlmCallCost
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface LlmCallCostJpaRepository : JpaRepository<LlmCallCost, Long> {
    @Query(
        """
        select new com.kbap.common.domain.metering.dto.DailyModelCostSum(
            cast(c.createdAt as LocalDate), c.modelName, count(c),
            sum(c.inputTokens), sum(c.outputTokens), sum(c.costUsd), sum(c.costKrw))
        from LlmCallCost c
        where c.createdAt >= :from
        group by cast(c.createdAt as LocalDate), c.modelName
        """,
    )
    fun sumDailyByModelSince(@Param("from") from: LocalDateTime): List<DailyModelCostSum>
}
