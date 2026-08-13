package com.kbap.infra.llm.embedding

import com.kbap.common.port.llm.TextEmbeddingClient
import com.kbap.infra.llm.config.LlmModelProperties
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.openai.OpenAiEmbeddingModel
import org.springframework.ai.openai.OpenAiEmbeddingOptions

class OpenAiTextEmbeddingClient(
    private val props: LlmModelProperties.EmbeddingProps,
    baseUrl: String = props.baseUrl ?: OPENAI_BASE_URL,
) : TextEmbeddingClient {
    private val embeddingModel: OpenAiEmbeddingModel by lazy {
        OpenAiEmbeddingModel(
            OpenAiEmbeddingOptions.builder()
                .apiKey(requiredApiKey())
                .baseUrl(baseUrl)
                .model(props.model)
                .dimensions(props.dimension)
                .timeout(props.timeout)
                .build(),
        )
    }

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val results = embeddingModel.call(EmbeddingRequest(texts, null)).results
        check(results.size == texts.size) {
            "임베딩 응답 개수가 입력과 다릅니다: 입력 ${texts.size}건, 응답 ${results.size}건"
        }
        val vectorsByIndex = results.associate { embedding ->
            check(embedding.output.size == props.dimension) {
                "임베딩 차원이 설정과 다릅니다: 설정 ${props.dimension}, 응답 ${embedding.output.size}"
            }
            embedding.index to embedding.output
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
        const val OPENAI_BASE_URL = "https://api.openai.com/v1"
    }
}
