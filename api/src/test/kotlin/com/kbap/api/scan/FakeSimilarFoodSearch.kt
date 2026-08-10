package com.kbap.api.scan

import com.kbap.common.port.llm.TextEmbeddingClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// 테스트용 페이크 임베딩+벡터 검색 — 임베딩 벡터에 질의 텍스트의 등록 순번을 실어, 검색이 이름별로 프로그래밍한
// 결과를 돌려주게 한다(실 Bedrock·DocumentDB 없음. DocumentDB $search 는 로컬 재현 불가).
class FakeSimilarFoodSearch : TextEmbeddingClient, SimilarFoodSearcher {
    private val documentsByName = mutableMapOf<String, SimilarFoodDocument>()
    private val embeddedNames = mutableListOf<String>()
    private var failSearch = false

    fun program(koreanName: String, foodId: Long, score: Double) {
        documentsByName[koreanName] = SimilarFoodDocument(foodId, score)
    }

    fun failSearch() {
        failSearch = true
    }

    fun reset() {
        documentsByName.clear()
        embeddedNames.clear()
        failSearch = false
    }

    override fun embed(texts: List<String>): List<FloatArray> =
        texts.map { text ->
            embeddedNames.add(text)
            floatArrayOf((embeddedNames.size - 1).toFloat())
        }

    override fun search(embedding: FloatArray, limit: Int): List<SimilarFoodDocument> {
        if (failSearch) throw RuntimeException("vector 검색 실패(테스트)")
        val name = embeddedNames[embedding[0].toInt()]
        return listOfNotNull(documentsByName[name])
    }
}

// 실 빈은 kbap.llm.embedding.enabled·kbap.vector.enabled 로 꺼져 있어 충돌 없음(FakeVisionConfig 선례).
// 빈 하나가 두 seam 타입을 모두 만족한다 — 타입별 위임 빈을 따로 등록하면 중복 빈 충돌.
@Configuration
class FakeSimilarFoodSearchConfig {
    @Bean
    fun fakeSimilarFoodSearch(): FakeSimilarFoodSearch = FakeSimilarFoodSearch()
}
