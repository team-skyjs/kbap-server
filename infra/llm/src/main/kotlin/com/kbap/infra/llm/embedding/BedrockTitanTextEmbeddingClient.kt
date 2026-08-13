package com.kbap.infra.llm.embedding

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.llm.TextEmbeddingClient
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest

class BedrockTitanTextEmbeddingClient(
    private val bedrockRuntimeClient: BedrockRuntimeClient,
    private val modelId: String,
    private val dimension: Int,
) : TextEmbeddingClient {
    private val objectMapper = jacksonObjectMapper()

    override fun embed(texts: List<String>): List<FloatArray> = texts.map(::embedSingle)

    private fun embedSingle(text: String): FloatArray {
        val requestBody = objectMapper.writeValueAsString(
            mapOf("inputText" to text, "dimensions" to dimension, "normalize" to true),
        )
        val response = bedrockRuntimeClient.invokeModel(
            InvokeModelRequest.builder()
                .modelId(modelId)
                .body(SdkBytes.fromUtf8String(requestBody))
                .build(),
        )
        val embedding = objectMapper.readTree(response.body().asUtf8String())["embedding"]
        check(embedding != null && embedding.size() == dimension) {
            "Titan 임베딩 차원이 설정과 다릅니다: expected=$dimension actual=${embedding?.size()}"
        }
        return FloatArray(dimension) { embedding[it].floatValue() }
    }
}
