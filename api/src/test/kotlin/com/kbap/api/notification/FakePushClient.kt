package com.kbap.api.notification

import com.kbap.common.port.push.PushMessage
import com.kbap.common.port.push.PushClient
import com.kbap.common.port.push.PushTicket
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

class FakePushClient : PushClient {
    val sent: MutableList<PushMessage> = mutableListOf()
    var errorFor: (PushMessage) -> String? = { null }

    override fun send(messages: List<PushMessage>): List<PushTicket> {
        sent += messages
        return messages.mapIndexed { i, message ->
            errorFor(message)?.let { PushTicket.error(it) } ?: PushTicket.ok("ticket-${sent.size - messages.size + i}")
        }
    }

    fun reset() {
        sent.clear()
        errorFor = { null }
    }
}

@Configuration
class FakePushClientConfig {
    @Bean
    @Primary
    fun fakePushClient(): FakePushClient = FakePushClient()
}
