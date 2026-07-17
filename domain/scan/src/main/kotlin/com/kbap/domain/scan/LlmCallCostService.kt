package com.kbap.domain.scan

import com.kbap.core.llm.LlmCallCostIncurred
import com.kbap.domain.scan.model.LlmCallCost
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class LlmCallCostService internal constructor(
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
