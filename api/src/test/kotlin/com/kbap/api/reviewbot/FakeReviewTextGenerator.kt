package com.kbap.api.reviewbot

import com.kbap.common.port.llm.ProcessedFoodReviewDraft
import com.kbap.common.port.llm.ProcessedFoodReviewDraftRequest
import com.kbap.common.port.llm.ReviewDraftRequest
import com.kbap.common.port.llm.ReviewTextGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class FakeReviewTextGenerator : ReviewTextGenerator {
    val requests: MutableList<ReviewDraftRequest> = mutableListOf()
    var reply: (ReviewDraftRequest) -> String = { DEFAULT_REPLY }
    val processedRequests: MutableList<ProcessedFoodReviewDraftRequest> = mutableListOf()
    var processedReply: (ProcessedFoodReviewDraftRequest) -> ProcessedFoodReviewDraft = { DEFAULT_PROCESSED_DRAFT }

    override fun generate(request: ReviewDraftRequest): String {
        requests += request
        return reply(request)
    }

    override fun generateProcessedFood(request: ProcessedFoodReviewDraftRequest): ProcessedFoodReviewDraft {
        processedRequests += request
        return processedReply(request)
    }

    fun reset() {
        requests.clear()
        reply = { DEFAULT_REPLY }
        processedRequests.clear()
        processedReply = { DEFAULT_PROCESSED_DRAFT }
    }

    companion object {
        const val DEFAULT_REPLY = "Warm, savory and so good lol"
        val DEFAULT_PROCESSED_DRAFT = ProcessedFoodReviewDraft("Crunchy, sweet snack, so good lol", spiciness = null, sweetness = 3)
    }
}

@Configuration
class FakeReviewTextGeneratorConfig {
    @Bean
    fun fakeReviewTextGenerator(): FakeReviewTextGenerator = FakeReviewTextGenerator()
}
