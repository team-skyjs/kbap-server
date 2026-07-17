package com.kbap.app.api.scan

import com.kbap.core.llm.LlmCallCostIncurred
import com.kbap.domain.scan.LlmCallCostService
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class LlmCallCostEventListener(
    private val llmCallCostService: LlmCallCostService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async
    @EventListener
    fun handle(event: LlmCallCostIncurred) {
        try {
            llmCallCostService.record(event)
        } catch (e: Exception) {
            log.error("LLM 호출 비용 기록 실패 modelName={}", event.modelName, e)
        }
    }
}
