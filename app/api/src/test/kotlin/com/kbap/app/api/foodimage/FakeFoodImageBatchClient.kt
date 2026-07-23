package com.kbap.app.api.foodimage

import com.kbap.core.food.FoodImageBatchClient
import com.kbap.core.llm.LlmCallCostIncurred
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import java.util.concurrent.CopyOnWriteArrayList

// 테스트용 페이크 OpenAI 배치 seam — 제출을 기록하고, 상태·결과를 시나리오별로 주입한다.
class FakeFoodImageBatchClient : FoodImageBatchClient {
    val submitted: MutableList<List<FoodImageBatchClient.Entry>> = mutableListOf()
    val polls: MutableMap<String, FoodImageBatchClient.BatchPoll> = mutableMapOf()
    val results: MutableMap<String, List<FoodImageBatchClient.Result>> = mutableMapOf()
    private var sequence = 0

    override fun submit(entries: List<FoodImageBatchClient.Entry>): String {
        submitted.add(entries)
        return "batch_${++sequence}"
    }

    override fun status(openaiBatchId: String): FoodImageBatchClient.BatchPoll =
        polls[openaiBatchId]
            ?: FoodImageBatchClient.BatchPoll(FoodImageBatchClient.State.IN_PROGRESS, null, null)

    override fun streamResults(fileId: String, onItem: (FoodImageBatchClient.Result) -> Unit) {
        results[fileId].orEmpty().forEach(onItem)
    }

    fun reset() {
        submitted.clear()
        polls.clear()
        results.clear()
    }
}

// LlmCallCostIncurred 발행 검증용 동기 리스너(실 @Async 리스너와 별개로 기록만 한다).
class RecordingLlmCostListener {
    val events: CopyOnWriteArrayList<LlmCallCostIncurred> = CopyOnWriteArrayList()

    @EventListener
    fun on(event: LlmCallCostIncurred) {
        events.add(event)
    }
}

// 실 어댑터(OpenAiFoodImageBatchClient) 빈은 infra 스캔으로 항상 존재 — 테스트에선 @Primary 로 페이크가 이긴다.
@Configuration
class FakeFoodImageBatchConfig {
    @Bean
    @Primary
    fun fakeFoodImageBatchClient(): FakeFoodImageBatchClient = FakeFoodImageBatchClient()

    @Bean
    fun recordingLlmCostListener(): RecordingLlmCostListener = RecordingLlmCostListener()
}
