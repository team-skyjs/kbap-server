package com.kbap.api.chat

import com.kbap.common.port.alert.OperatorAlertSender
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.util.concurrent.CopyOnWriteArrayList

class FakeOperatorAlertSender : OperatorAlertSender {
    val sent: MutableList<String> = CopyOnWriteArrayList()

    override fun send(text: String) {
        sent += text
    }
}

@Configuration
class FakeOperatorAlertSenderConfig {
    @Bean
    @Primary
    fun fakeOperatorAlertSender(): FakeOperatorAlertSender = FakeOperatorAlertSender()
}
