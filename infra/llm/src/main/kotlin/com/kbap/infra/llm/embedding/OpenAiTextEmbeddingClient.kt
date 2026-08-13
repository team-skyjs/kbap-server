package com.kbap.infra.llm.embedding

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.llm.TextEmbeddingClient
import com.kbap.infra.llm.config.LlmModelProperties
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

class OpenAiTextEmbeddingClient(
    private val props: LlmModelProperties.EmbeddingProps,
    baseUrl: String = props.baseUrl ?: OPENAI_BASE_URL,
) : TextEmbeddingClient {
    private val objectMapper = jacksonObjectMapper()

    private val restClient: RestClient by lazy {
        val httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
            .apply { setReadTimeout(props.timeout) }
        RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(requestFactory)
            .defaultHeaders { it.setBearerAuth(requiredApiKey()) }
            .build()
    }

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val body = restClient.post()
            .uri("/v1/embeddings")
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                objectMapper.writeValueAsString(
                    mapOf(
                        "model" to props.model,
                        "input" to texts,
                        "dimensions" to props.dimension,
                    ),
                ),
            )
            .retrieve()
            .body(String::class.java)
            ?: error("임베딩 응답이 비어 있습니다: model=${props.model}")
        val data = objectMapper.readTree(body).path("data")
        check(data.isArray && data.size() == texts.size) {
            "임베딩 응답 개수가 입력과 다릅니다: 입력 ${texts.size}건, 응답 ${data.size()}건"
        }
        val vectorsByIndex = data.associate { item ->
            val embedding = item.path("embedding")
            check(embedding.size() == props.dimension) {
                "임베딩 차원이 설정과 다릅니다: 설정 ${props.dimension}, 응답 ${embedding.size()}"
            }
            item.path("index").asInt() to FloatArray(embedding.size()) { embedding[it].floatValue() }
        }
        return texts.indices.map {
            vectorsByIndex[it] ?: error("임베딩 응답에 index=$it 가 없습니다")
        }
    }

    private fun requiredApiKey(): String {
        val apiKey = props.apiKey
        require(!apiKey.isNullOrBlank()) {
            "kbap.llm.embedding.api-key 가 비어 있습니다(배포 환경변수로 주입)."
        }
        return apiKey
    }

    companion object {
        const val OPENAI_BASE_URL = "https://api.openai.com"
    }
}
