package com.kbap.common.infra.llm.review

import com.kbap.common.infra.llm.claude.ClaudeChat
import com.kbap.common.port.llm.ProcessedFoodReviewDraft
import com.kbap.common.port.llm.ProcessedFoodReviewDraftRequest
import com.kbap.common.port.llm.ReviewDraftRequest
import com.kbap.common.port.llm.ReviewTextGenerator

// 프롬프트·파싱은 OpenAI 구현과 공유한다 — 공급자만 다르다.
class ClaudeReviewTextGenerator(private val chat: ClaudeChat) : ReviewTextGenerator {
    override fun generate(request: ReviewDraftRequest): String =
        chat.ask(OpenAiReviewTextGenerator.SYSTEM_PROMPT, OpenAiReviewTextGenerator.userPrompt(request)).text.trim()

    override fun generateProcessedFood(request: ProcessedFoodReviewDraftRequest): ProcessedFoodReviewDraft =
        OpenAiReviewTextGenerator.parseProcessedDraft(
            chat.ask(OpenAiReviewTextGenerator.PROCESSED_SYSTEM_PROMPT, OpenAiReviewTextGenerator.processedUserPrompt(request)).text,
        )
}
