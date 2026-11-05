package com.kbap.api.infra.telegram

import com.kbap.common.port.alert.OperatorAlertSender
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

class TelegramOperatorAlertSender(
    private val botToken: String,
    private val chatId: String,
    baseUrl: String = TELEGRAM_BASE_URL,
) : OperatorAlertSender {
    private val restClient: RestClient = RestClient.builder()
        .baseUrl(baseUrl)
        .requestFactory(
            JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build())
                .apply { setReadTimeout(Duration.ofSeconds(10)) },
        )
        .build()

    override fun send(text: String) {
        restClient.post()
            .uri("/bot{token}/sendMessage", botToken)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("chat_id" to chatId, "text" to text.take(MAX_TEXT_LENGTH), "disable_web_page_preview" to true))
            .retrieve()
            .toBodilessEntity()
    }

    private companion object {
        const val TELEGRAM_BASE_URL = "https://api.telegram.org"
        const val MAX_TEXT_LENGTH = 4000
    }
}
