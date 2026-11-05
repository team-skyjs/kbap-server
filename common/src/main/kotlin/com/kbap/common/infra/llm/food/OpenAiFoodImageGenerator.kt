package com.kbap.common.infra.llm.food

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.infra.llm.config.LlmModelProperties
import com.kbap.common.port.llm.FoodImageGenerator
import com.kbap.common.port.llm.GeneratedFoodImage
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration
import java.util.Base64

class OpenAiFoodImageGenerator(
    private val props: LlmModelProperties.ImageProps,
    baseUrl: String = props.baseUrl ?: OPENAI_BASE_URL,
) : FoodImageGenerator {
    private val objectMapper = jacksonObjectMapper()

    private val restClient: RestClient by lazy {
        val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(Duration.ofMinutes(2)) })
            .defaultHeaders { it.setBearerAuth(requiredApiKey()) }
            .build()
    }

    override fun generate(prompt: String, quality: String): GeneratedFoodImage {
        val body = objectMapper.createObjectNode().apply {
            put("prompt", prompt)
            put("n", 1)
            put("quality", quality)
            props.model?.let { put("model", it) }
            props.size?.let { put("size", it) }
            props.outputFormat?.let { put("output_format", it) }
            props.outputCompression?.let { put("output_compression", it) }
        }
        val response = restClient.post()
            .uri(IMAGES_ENDPOINT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(String::class.java)
            ?: error("이미지 생성 응답이 비어 있습니다")
        val node = objectMapper.readTree(response)
        val b64 = node.path("data").path(0).path("b64_json").asText()
        check(b64.isNotBlank()) { "이미지 생성 응답에 b64 데이터가 없습니다" }
        return GeneratedFoodImage(
            bytes = Base64.getDecoder().decode(b64),
            inputTokens = node.path("usage").path("input_tokens").asLong(0),
            outputTokens = node.path("usage").path("output_tokens").asLong(0),
        )
    }

    private fun requiredApiKey(): String {
        val apiKey = props.apiKey
        require(!apiKey.isNullOrBlank()) { "kbap.llm.image.api-key 가 비어 있습니다(배포 환경변수로 주입)." }
        return apiKey
    }

    private companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com"
        const val IMAGES_ENDPOINT = "/v1/images/generations"
    }
}
