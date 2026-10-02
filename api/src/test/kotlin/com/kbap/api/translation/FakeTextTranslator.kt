package com.kbap.api.translation

import com.kbap.common.domain.LanguageCode
import com.kbap.common.port.llm.TextTranslator
import com.kbap.common.port.llm.TranslatedText
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.CopyOnWriteArrayList

class FakeTextTranslator : TextTranslator {
    val calls: MutableList<Pair<String, LanguageCode>> = CopyOnWriteArrayList()
    val transactionActiveDuringCalls: MutableList<Boolean> = CopyOnWriteArrayList()

    @Volatile
    var reply: (String, LanguageCode) -> String = DEFAULT_REPLY

    @Volatile
    var sourceLanguageTag: String? = null

    override fun translate(text: String, target: LanguageCode): TranslatedText {
        calls += text to target
        transactionActiveDuringCalls += TransactionSynchronizationManager.isActualTransactionActive()
        return TranslatedText(reply(text, target), sourceLanguageTag)
    }

    fun reset() {
        calls.clear()
        transactionActiveDuringCalls.clear()
        reply = DEFAULT_REPLY
        sourceLanguageTag = null
    }

    private companion object {
        val DEFAULT_REPLY: (String, LanguageCode) -> String = { text, target -> "[${target.code}] $text" }
    }
}

@Configuration
class FakeTextTranslatorConfig {
    @Bean
    fun fakeTextTranslator(): FakeTextTranslator = FakeTextTranslator()
}
