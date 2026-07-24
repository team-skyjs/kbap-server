package com.kbap.app.api.foodimage

import com.kbap.core.food.FoodImageBatchClient
import com.kbap.core.llm.LlmCallCostIncurred
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import java.util.concurrent.CopyOnWriteArrayList

class FakeFoodImageBatchClient : FoodImageBatchClient {
    val submitted: MutableList<List<FoodImageBatchClient.Entry>> = mutableListOf()
    val polls: MutableMap<String, FoodImageBatchClient.BatchPoll> = mutableMapOf()
    val results: MutableMap<String, List<FoodImageBatchClient.Result>> = mutableMapOf()
    val failingFiles: MutableSet<String> = mutableSetOf()
    var submitFailure: RuntimeException? = null
    private var sequence = 0

    override fun submit(entries: List<FoodImageBatchClient.Entry>): String {
        submitFailure?.let { throw it }
        submitted.add(entries)
        return "batch_${++sequence}"
    }

    override fun status(openaiBatchId: String): FoodImageBatchClient.BatchPoll =
        polls[openaiBatchId]
            ?: FoodImageBatchClient.BatchPoll(FoodImageBatchClient.State.IN_PROGRESS, null, null)

    override fun streamResults(fileId: String, onItem: (FoodImageBatchClient.Result) -> Unit) {
        check(fileId !in failingFiles) { "결과 파일 다운로드 실패: HTTP 500 fileId=$fileId" }
        results[fileId].orEmpty().forEach(onItem)
    }

    fun reset() {
        submitted.clear()
        polls.clear()
        results.clear()
        failingFiles.clear()
        submitFailure = null
    }
}

class RecordingLlmCostListener {
    val events: CopyOnWriteArrayList<LlmCallCostIncurred> = CopyOnWriteArrayList()

    @EventListener
    fun on(event: LlmCallCostIncurred) {
        events.add(event)
    }
}

@Configuration
class FakeFoodImageBatchConfig {
    @Bean
    @Primary
    fun fakeFoodImageBatchClient(): FakeFoodImageBatchClient = FakeFoodImageBatchClient()

    @Bean
    fun recordingLlmCostListener(): RecordingLlmCostListener = RecordingLlmCostListener()
}
