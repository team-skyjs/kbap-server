package com.kbap.common.infra.push

import com.kbap.common.port.push.PushMessage
import com.kbap.common.port.push.PushSender
import com.kbap.common.port.push.PushTicket
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.http.converter.HttpMessageConversionException
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.net.http.HttpClient
import java.time.Duration

internal data class ExpoMessage(
    val to: String,
    val title: String,
    val body: String,
    val data: Map<String, Any>,
    val sound: String = "default",
    val priority: String = "high",
    val channelId: String = "default",
)

internal data class ExpoTicket(
    val status: String = "",
    val id: String? = null,
    val message: String? = null,
    val details: Map<String, Any>? = null,
) {
    fun toTicket(): PushTicket =
        if (status == "ok" && id != null) {
            PushTicket.ok(id)
        } else {
            PushTicket.error((details?.get("error") as? String) ?: message ?: "unknown")
        }
}

internal data class ExpoSendResponse(
    val data: List<ExpoTicket> = emptyList(),
)

class ExpoPushSender internal constructor(
    private val restClient: RestClient,
) : PushSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(messages: List<PushMessage>): List<PushTicket> =
        messages.chunked(CHUNK_SIZE).flatMap { sendChunk(it) }

    private fun sendChunk(chunk: List<PushMessage>): List<PushTicket> {
        val tickets = try {
            restClient.post()
                .uri(SEND_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(chunk.map { ExpoMessage(it.to, it.title, it.body, it.data) })
                .retrieve()
                .body(ExpoSendResponse::class.java)
                ?.data
                .orEmpty()
                .map { it.toTicket() }
        } catch (e: RestClientException) {
            return failAll(chunk, e)
        } catch (e: HttpMessageConversionException) {
            return failAll(chunk, e)
        }
        return List(chunk.size) { i -> tickets.getOrNull(i) ?: PushTicket.error("ticket count mismatch") }
    }

    private fun failAll(chunk: List<PushMessage>, e: Exception): List<PushTicket> {
        log.warn("Expo push 청크 발송 실패: size={}", chunk.size, e)
        val error = PushTicket.error("${e::class.simpleName}: ${e.message}")
        return List(chunk.size) { error }
    }

    companion object {
        private const val CHUNK_SIZE = 100
        private const val SEND_PATH = "/--/api/v2/push/send"

        fun create(baseUrl: String, accessToken: String): ExpoPushSender {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(10)) }
            return create(baseUrl, accessToken, RestClient.builder().requestFactory(requestFactory))
        }

        internal fun create(baseUrl: String, accessToken: String, restClientBuilder: RestClient.Builder): ExpoPushSender {
            val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
            val builder = restClientBuilder
                .baseUrl(baseUrl)
                .configureMessageConverters { converters ->
                    converters.disableDefaults().withJsonConverter(JacksonJsonHttpMessageConverter(mapper))
                }
            if (accessToken.isNotBlank()) {
                builder.defaultHeaders { it.setBearerAuth(accessToken) }
            }
            return ExpoPushSender(builder.build())
        }
    }
}
