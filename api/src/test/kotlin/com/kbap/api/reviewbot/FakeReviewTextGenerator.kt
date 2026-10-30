package com.kbap.api.reviewbot

import com.kbap.common.port.llm.ReviewDraftRequest
import com.kbap.common.port.llm.ReviewTextGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class FakeReviewTextGenerator : ReviewTextGenerator {
    val requests: MutableList<ReviewDraftRequest> = mutableListOf()
    var reply: (ReviewDraftRequest) -> String = { DEFAULT_REPLY }

    override fun generate(request: ReviewDraftRequest): String {
        requests += request
        return reply(request)
    }

    fun reset() {
        requests.clear()
        reply = { DEFAULT_REPLY }
    }

    companion object {
        const val DEFAULT_REPLY = "Warm, savory and so good lol"
    }
}

@Configuration
class FakeReviewTextGeneratorConfig {
    @Bean
    fun fakeReviewTextGenerator(): FakeReviewTextGenerator = FakeReviewTextGenerator()
}
