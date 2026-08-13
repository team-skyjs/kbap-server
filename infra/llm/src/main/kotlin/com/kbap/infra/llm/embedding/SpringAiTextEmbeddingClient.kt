package com.kbap.infra.llm.embedding

import com.kbap.common.port.llm.TextEmbeddingClient
import org.springframework.ai.embedding.EmbeddingModel

class SpringAiTextEmbeddingClient(
    private val embeddingModel: EmbeddingModel,
    private val expectedDimension: Int,
) : TextEmbeddingClient {

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val vectors = embeddingModel.embed(texts)
        check(vectors.size == texts.size) {
            "임베딩 개수(${vectors.size})가 입력 개수(${texts.size})와 다릅니다"
        }
        vectors.forEach { vector ->
            check(vector.size == expectedDimension) {
                "임베딩 차원(${vector.size})이 기대 차원($expectedDimension)과 다릅니다"
            }
        }
        return vectors
    }
}
