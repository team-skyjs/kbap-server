package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import com.kbap.common.infra.llm.claude.ClaudeChat
import com.kbap.common.infra.llm.translation.OpenAiTextTranslator.Format
import com.kbap.common.port.llm.TextTranslator
import com.kbap.common.port.llm.TranslatedText
import org.slf4j.LoggerFactory
import java.util.UUID

// 프롬프트·출력 상한·응답 해석은 OpenAI 구현과 공유한다. 끝까지 생성됐다는 표식은 stop_reason=end_turn 이다.
class ClaudeTextTranslator(
    private val chat: ClaudeChat,
    private val format: Format = Format.SOURCE_LANGUAGE_HEADER,
    private val newMarker: () -> String = { "LANG-" + UUID.randomUUID().toString().take(8) },
) : TextTranslator {
    override fun translate(text: String, target: LanguageCode): TranslatedText {
        val marker = newMarker()
        val reply = chat.ask(
            system = OpenAiTextTranslator.systemPrompt(format, target, text, marker),
            userText = OpenAiTextTranslator.userText(format, text),
            maxTokens = OpenAiTextTranslator.maxOutputTokens(text).toLong(),
        )
        log.info("번역 LLM 호출 — model={}, target={}, sourceChars={}, inputTokens={}, outputTokens={}", reply.model, target.code, text.length, reply.inputTokens, reply.outputTokens)
        check(reply.finished) {
            "번역이 끝까지 생성됐다는 표식이 없다(stopReason=${reply.stopReason?.asString()}) — 잘렸거나 확인할 수 없는 번역은 쓰지 않는다"
        }
        return OpenAiTextTranslator.parseOutput(format, reply.text, text, target, marker)
    }

    companion object {
        private val log = LoggerFactory.getLogger(ClaudeTextTranslator::class.java)
    }
}
