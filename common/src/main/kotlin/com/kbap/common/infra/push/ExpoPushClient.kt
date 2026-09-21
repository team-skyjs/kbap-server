package com.kbap.common.infra.push

import com.fasterxml.jackson.annotation.JsonInclude
import com.kbap.common.port.push.PushMessage
import com.kbap.common.port.push.PushClient
import com.kbap.common.port.push.PushTicket
import org.slf4j.LoggerFactory
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.springframework.http.MediaType
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import java.time.Duration

internal data class ExpoMessage(
    val to: String,
    val title: String,
    val body: String,
    val data: Map<String, Any>,
    val sound: String = "default",
    val priority: String = "high",
    val channelId: String,
    @field:JsonInclude(JsonInclude.Include.NON_NULL)
    val ttl: Int? = null,
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

class ExpoPushClient internal constructor(
    private val restClient: RestClient,
    retryPolicy: RetryPolicy,
) : PushClient {
    private val log = LoggerFactory.getLogger(javaClass)
    private val retryTemplate = RetryTemplate(retryPolicy)

    override fun send(messages: List<PushMessage>): List<PushTicket> =
        messages.chunked(CHUNK_SIZE).flatMap(::sendChunk)

    private fun sendChunk(chunk: List<PushMessage>): List<PushTicket> {
        val tickets = try {
            retryTemplate.execute<List<PushTicket>> { post(chunk) }
        } catch (e: RetryException) {
            return failAll(chunk, e.cause ?: e, e.retryCount)
        }
        return List(chunk.size) { i -> tickets.getOrNull(i) ?: PushTicket.error("ticket count mismatch") }
    }

    private fun post(chunk: List<PushMessage>): List<PushTicket> =
        restClient.post()
            .uri(SEND_PATH)
            .contentType(MediaType.APPLICATION_JSON)
            .accept(MediaType.APPLICATION_JSON)
            .body(chunk.map { ExpoMessage(it.to, it.title, it.body, it.data, channelId = it.channelId, ttl = it.ttlSeconds) })
            .retrieve()
            .body(ExpoSendResponse::class.java)
            ?.data
            .orEmpty()
            .map { it.toTicket() }

    private fun failAll(chunk: List<PushMessage>, e: Throwable, retries: Int): List<PushTicket> {
        log.warn("Expo push 청크 발송 실패: size={} retries={}", chunk.size, retries, e)
        val error = PushTicket.error("${e::class.simpleName}: ${e.message}")
        return List(chunk.size) { error }
    }

    companion object {
        private const val CHUNK_SIZE = 100
        private const val SEND_PATH = "/--/api/v2/push/send"

        fun defaultRetryPolicy(maxRetries: Long, initialDelay: Duration, multiplier: Double): RetryPolicy =
            RetryPolicy.builder()
                .maxRetries(maxRetries)
                .delay(initialDelay)
                .multiplier(multiplier)
                .predicate(::isTransient)
                .build()

        private fun isTransient(e: Throwable): Boolean =
            e is ResourceAccessException || e is HttpServerErrorException || e is HttpClientErrorException.TooManyRequests

        fun create(baseUrl: String, accessToken: String, retryPolicy: RetryPolicy): ExpoPushClient =
            create(baseUrl, accessToken, expoRestClientBuilder(), retryPolicy)

        internal fun create(baseUrl: String, accessToken: String, builder: RestClient.Builder, retryPolicy: RetryPolicy): ExpoPushClient =
            ExpoPushClient(expoRestClient(baseUrl, accessToken, builder), retryPolicy)
    }
}
