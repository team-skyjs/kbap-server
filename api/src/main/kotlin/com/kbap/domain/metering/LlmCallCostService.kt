package com.kbap.domain.metering

import com.kbap.common.core.llm.LlmCallCostIncurred
import com.kbap.domain.metering.model.LlmCallCost
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LlmCallCostService(
    private val repository: LlmCallCostJpaRepository,
) {
    @Transactional
    fun record(event: LlmCallCostIncurred) {
        repository.save(
            LlmCallCost(
                modelName = event.modelName,
                inputTokens = event.inputTokens,
                outputTokens = event.outputTokens,
                costUsd = event.costUsd,
                costKrw = event.costKrw,
            ),
        )
    }
}
