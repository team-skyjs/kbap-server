package com.kbap.api.core.config

import com.kbap.api.infra.telegram.TelegramOperatorAlertSender
import com.kbap.common.port.alert.OperatorAlertSender
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ChatAlertConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    @Bean
    fun operatorAlertSender(
        @Value("\${kbap.chat.telegram.bot-token:}") botToken: String,
        @Value("\${kbap.chat.telegram.chat-id:}") chatId: String,
    ): OperatorAlertSender {
        if (botToken.isBlank() || chatId.isBlank()) {
            log.info("텔레그램 봇 토큰·채팅 id 가 없어 운영진 채팅 알림을 보내지 않는다")
            return OperatorAlertSender { }
        }
        return TelegramOperatorAlertSender(botToken, chatId)
    }
}
