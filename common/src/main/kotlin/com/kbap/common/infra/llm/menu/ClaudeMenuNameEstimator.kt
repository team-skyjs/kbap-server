package com.kbap.common.infra.llm.menu

import com.kbap.common.infra.llm.claude.ClaudeChat
import com.kbap.common.port.llm.MenuNameEstimation
import com.kbap.common.port.llm.MenuNameEstimator

// 프롬프트·스키마·파싱은 OpenAI 구현과 공유한다 — 공급자만 다르다.
class ClaudeMenuNameEstimator(private val chat: ClaudeChat) : MenuNameEstimator {
    override fun estimate(koreanNames: List<String>): List<MenuNameEstimation> {
        if (koreanNames.isEmpty()) return emptyList()
        val reply = chat.ask(
            system = OpenAiMenuNameEstimator.SYSTEM_PROMPT,
            userText = OpenAiMenuNameEstimator.userMessage(koreanNames),
            jsonSchema = OpenAiMenuNameEstimator.SCHEMA,
        )
        return OpenAiMenuNameEstimator.parse(reply.text, koreanNames)
    }
}
