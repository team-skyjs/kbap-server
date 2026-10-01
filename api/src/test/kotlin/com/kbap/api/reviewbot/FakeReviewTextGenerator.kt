package com.kbap.api.reviewbot

import com.kbap.common.port.llm.ReviewDraftRequest
import com.kbap.common.port.llm.ReviewTextGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class FakeReviewTextGenerator : ReviewTextGenerator {
    val requests: MutableList<ReviewDraftRequest> = mutableListOf()
    var reply: (ReviewDraftRequest) -> String = { "The ${it.foodName} was warm and comforting, with a deep savory broth I kept going back to." }

    override fun generate(request: ReviewDraftRequest): String {
        requests += request
        return reply(request)
    }

    fun reset() {
        requests.clear()
        reply = { "The ${it.foodName} was warm and comforting, with a deep savory broth I kept going back to." }
    }
}

@Configuration
class FakeReviewTextGeneratorConfig {
    @Bean
    fun fakeReviewTextGenerator(): FakeReviewTextGenerator = FakeReviewTextGenerator()
}
