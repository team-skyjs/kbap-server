package com.kbap.common.infra.slack

import com.kbap.common.port.teamchannel.TeamChannelSender
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

class SlackWebhookSender(
    private val webhookUrl: String,
    private val restClient: RestClient = defaultRestClient(),
) : TeamChannelSender {
    override fun send(text: String) {
        restClient.post()
            .uri(webhookUrl)
            .contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("text" to text))
            .retrieve()
            .toBodilessEntity()
    }

    private companion object {
        fun defaultRestClient(): RestClient {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(10)) }
            return RestClient.builder().requestFactory(requestFactory).build()
        }
    }
}
