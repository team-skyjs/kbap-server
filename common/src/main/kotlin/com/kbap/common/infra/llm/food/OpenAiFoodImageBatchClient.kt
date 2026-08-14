package com.kbap.common.infra.llm.food

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.llm.FoodImageBatchClient
import com.kbap.common.infra.llm.config.LlmModelProperties
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64

class OpenAiFoodImageBatchClient(
    private val props: LlmModelProperties.ImageProps,
    baseUrl: String = props.baseUrl ?: OPENAI_BASE_URL,
) : FoodImageBatchClient {
    private val objectMapper = jacksonObjectMapper()

    private val restClient: RestClient by lazy {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
            .apply { setReadTimeout(Duration.ofMinutes(5)) }
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .defaultHeaders { it.setBearerAuth(requiredApiKey()) }
            .build()
    }

    override fun submit(entries: List<FoodImageBatchClient.Entry>): String {
        val jsonl = entries.joinToString(separator = "\n") { requestLineOf(it) }
        val fileId = uploadBatchFile(jsonl.toByteArray(Charsets.UTF_8))
        return createBatch(fileId)
    }

    override fun status(openaiBatchId: String): FoodImageBatchClient.BatchPoll {
        val body = restClient.get()
            .uri("/v1/batches/{id}", openaiBatchId)
            .retrieve()
            .body(String::class.java)
            ?: error("배치 상태 응답이 비어 있습니다: $openaiBatchId")
        val node = objectMapper.readTree(body)
        return FoodImageBatchClient.BatchPoll(
            state = stateOf(node.path("status").asText()),
            outputFileId = node.path("output_file_id").takeIf { !it.isNull && it.asText().isNotBlank() }?.asText(),
            errorFileId = node.path("error_file_id").takeIf { !it.isNull && it.asText().isNotBlank() }?.asText(),
        )
    }

    override fun streamResults(fileId: String, onItem: (FoodImageBatchClient.Result) -> Unit) {
        restClient.get()
            .uri("/v1/files/{id}/content", fileId)
            .exchange { _, response ->
                check(response.statusCode.is2xxSuccessful) {
                    "결과 파일 다운로드 실패: HTTP ${response.statusCode} fileId=$fileId"
                }
                response.body.bufferedReader().useLines { lines ->
                    lines.filter { it.isNotBlank() }.forEach { onItem(parseResultLine(it)) }
                }
            }
    }

    internal fun requestLineOf(entry: FoodImageBatchClient.Entry): String {
        val bodyNode = objectMapper.createObjectNode().apply {
            put("prompt", entry.prompt)
            props.model?.let { put("model", it) }
            props.size?.let { put("size", it) }
            props.quality?.let { put("quality", it) }
            props.outputFormat?.let { put("output_format", it) }
            props.outputCompression?.let { put("output_compression", it) }
        }
        val line = objectMapper.createObjectNode().apply {
            put("custom_id", entry.customId)
            put("method", "POST")
            put("url", IMAGES_ENDPOINT)
            set<JsonNode>("body", bodyNode)
        }
        return objectMapper.writeValueAsString(line)
    }

    internal fun parseResultLine(line: String): FoodImageBatchClient.Result {
        val node = objectMapper.readTree(line)
        val customId = node.path("custom_id").asText()
        val error = node.path("error").takeIf { !it.isNull && !it.isMissingNode }
        val responseBody = node.path("response").path("body")
        val statusCode = node.path("response").path("status_code").asInt(0)
        if (error != null || statusCode !in 200..299) {
            val message = error?.path("message")?.asText()?.takeIf { it.isNotBlank() }
                ?: responseBody.path("error").path("message").asText().ifBlank { "status_code=$statusCode" }
            return FoodImageBatchClient.Result(customId, bytes = null, errorMessage = message, usage = null)
        }
        val b64 = responseBody.path("data").path(0).path("b64_json").asText()
        if (b64.isBlank()) {
            return FoodImageBatchClient.Result(customId, bytes = null, errorMessage = "b64 데이터 없음", usage = null)
        }
        val usageNode = responseBody.path("usage")
        val usage = usageNode.takeIf { !it.isMissingNode && !it.isNull }?.let {
            FoodImageBatchClient.Usage(
                inputTokens = it.path("input_tokens").asLong(0),
                outputTokens = it.path("output_tokens").asLong(0),
            )
        }
        return FoodImageBatchClient.Result(
            customId = customId,
            bytes = Base64.getDecoder().decode(b64),
            errorMessage = null,
            usage = usage,
        )
    }

    private fun uploadBatchFile(jsonlBytes: ByteArray): String {
        val fileResource = object : ByteArrayResource(jsonlBytes) {
            override fun getFilename(): String = "food-image-batch.jsonl"
        }
        val parts = LinkedMultiValueMap<String, Any>().apply {
            add("purpose", "batch")
            add("file", fileResource)
        }
        val body = restClient.post()
            .uri("/v1/files")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(parts)
            .retrieve()
            .body(String::class.java)
            ?: error("파일 업로드 응답이 비어 있습니다")
        return objectMapper.readTree(body).path("id").asText().also {
            require(it.isNotBlank()) { "파일 업로드 응답에 id 가 없습니다: $body" }
        }
    }

    private fun createBatch(inputFileId: String): String {
        val request = objectMapper.createObjectNode().apply {
            put("input_file_id", inputFileId)
            put("endpoint", IMAGES_ENDPOINT)
            put("completion_window", COMPLETION_WINDOW)
        }
        val body = restClient.post()
            .uri("/v1/batches")
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(request))
            .retrieve()
            .body(String::class.java)
            ?: error("배치 생성 응답이 비어 있습니다")
        return objectMapper.readTree(body).path("id").asText().also {
            require(it.isNotBlank()) { "배치 생성 응답에 id 가 없습니다: $body" }
        }
    }

    private fun requiredApiKey(): String {
        val apiKey = props.apiKey
        require(!apiKey.isNullOrBlank()) { "kbap.llm.image.api-key 가 비어 있습니다(배포 환경변수로 주입)." }
        return apiKey
    }

    private fun stateOf(status: String): FoodImageBatchClient.State =
        when (status) {
            "completed" -> FoodImageBatchClient.State.COMPLETED
            "failed", "cancelled", "cancelling" -> FoodImageBatchClient.State.FAILED
            "expired" -> FoodImageBatchClient.State.EXPIRED
            else -> FoodImageBatchClient.State.IN_PROGRESS
        }

    companion object {
        private const val OPENAI_BASE_URL = "https://api.openai.com"
        private const val IMAGES_ENDPOINT = "/v1/images/generations"
        private const val COMPLETION_WINDOW = "24h"
    }
}
