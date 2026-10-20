package com.kbap.common.infra.publicdata

import com.kbap.common.port.publicdata.PublicDataCallCounter
import com.kbap.common.port.publicdata.PublicDataCallException
import org.springframework.core.retry.RetryException
import org.springframework.core.retry.RetryPolicy
import org.springframework.core.retry.RetryTemplate
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class DataGoKrClient internal constructor(
    private val restClient: RestClient,
    private val serviceKey: String,
    retryPolicy: RetryPolicy,
) : PublicDataCallCounter {
    private val retryTemplate = RetryTemplate(retryPolicy)
    private val attempts = AtomicLong()

    override fun httpAttempts(): Long = attempts.get()
    private val mapper = JsonMapper.builder().build()

    fun getJson(operation: String, path: String, keyParam: String, params: Map<String, Any>): JsonNode {
        val body = try {
            retryTemplate.execute<String> { fetch(path, keyParam, params) }
        } catch (e: RetryException) {
            throw PublicDataCallException("$operation 호출 실패(재시도 ${e.retryCount}회): ${describe(e.cause ?: e)}")
        }
        val json = try {
            mapper.readTree(body)
        } catch (e: Exception) {
            throw PublicDataCallException("$operation 응답이 JSON 이 아닙니다(${redact(body).take(BODY_SNIPPET)})")
        }
        json.path("OpenAPI_ServiceResponse").path("cmmMsgHeader").path("errMsg").takeIf { !it.isMissingNode }?.let {
            throw PublicDataCallException("$operation 거절: ${it.asString()}")
        }
        return json
    }

    private fun fetch(path: String, keyParam: String, params: Map<String, Any>): String {
        val variables = linkedMapOf<String, Any>(KEY_VARIABLE to serviceKey)
        params.keys.forEachIndexed { i, name -> variables["p$i"] = params.getValue(name) }
        val template = buildString {
            append(path).append('?').append(keyParam).append("={").append(KEY_VARIABLE).append('}')
            params.keys.forEachIndexed { i, name -> append('&').append(name).append("={p").append(i).append('}') }
        }
        attempts.incrementAndGet()
        return restClient.get().uri(template, variables).retrieve().body(String::class.java).orEmpty()
    }

    private fun redact(text: String): String {
        val encoded = URLEncoder.encode(serviceKey, Charsets.UTF_8)
        return listOf(serviceKey, encoded, encoded.lowercase())
            .fold(KEY_QUERY_VALUE.replace(text) { "${it.groupValues[1]}$MASK" }) { acc, form -> acc.replace(form, MASK, ignoreCase = true) }
    }

    private fun describe(e: Throwable): String = when (e) {
        is HttpClientErrorException -> "HTTP ${e.statusCode.value()} ${errMsgOf(e)}".trim()
        is RestClientResponseException -> "HTTP ${e.statusCode.value()}"
        is ResourceAccessException -> "연결 실패(${e.rootCause?.javaClass?.simpleName ?: e.javaClass.simpleName})"
        else -> e.javaClass.simpleName
    }

    private fun errMsgOf(e: HttpClientErrorException): String =
        runCatching { mapper.readTree(e.responseBodyAsString).path("OpenAPI_ServiceResponse").path("cmmMsgHeader").path("errMsg").asString() }
            .getOrNull().orEmpty()

    companion object {
        private const val KEY_VARIABLE = "serviceKey"
        private const val MASK = "***"
        private const val BODY_SNIPPET = 120
        private val KEY_QUERY_VALUE = Regex("(?i)(service_?key=)[^&\\s\"'<>]*")

        fun defaultRetryPolicy(maxRetries: Long, initialDelay: Duration): RetryPolicy =
            RetryPolicy.builder()
                .maxRetries(maxRetries)
                .delay(initialDelay)
                .multiplier(2.0)
                .predicate { it is ResourceAccessException || it is HttpServerErrorException || it is HttpClientErrorException.TooManyRequests }
                .build()

        fun create(baseUrl: String, serviceKey: String, retryPolicy: RetryPolicy): DataGoKrClient =
            create(baseUrl, serviceKey, defaultBuilder(), retryPolicy)

        internal fun create(baseUrl: String, serviceKey: String, builder: RestClient.Builder, retryPolicy: RetryPolicy): DataGoKrClient {
            require(serviceKey.isNotBlank()) { "data.go.kr 인증키(DATA_GO_KR_SERVICE_KEY)가 비어 있습니다" }
            return DataGoKrClient(builder.baseUrl(baseUrl).build(), serviceKey, retryPolicy)
        }

        private fun defaultBuilder(): RestClient.Builder {
            val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofSeconds(60)) }
            return RestClient.builder().requestFactory(requestFactory)
        }
    }
}
