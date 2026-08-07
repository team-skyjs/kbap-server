package com.kbap.infra.llm.embedding

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse

private const val DIMENSION = 1024

private class FakeEmbeddingModel(
    private val vectorFor: (String) -> FloatArray,
) : EmbeddingModel {
    var callCount = 0

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        callCount++
        val embeddings = request.instructions.mapIndexed { index, text -> Embedding(vectorFor(text), index) }
        return EmbeddingResponse(embeddings)
    }

    override fun embed(document: Document): FloatArray = error("문서 임베딩은 계약 밖 — 테스트에서 호출되면 안 된다")
}

private fun markedVector(marker: Float, size: Int = DIMENSION): FloatArray =
    FloatArray(size) { 0f }.also { it[0] = marker }

class SpringAiTextEmbeddingClientTest : BehaviorSpec({

    given("텍스트별로 구분 가능한 1024차원 벡터를 돌려주는 페이크 EmbeddingModel") {
        val fake = FakeEmbeddingModel { text ->
            when (text) {
                "김치찌개" -> markedVector(1f)
                "된장찌개" -> markedVector(2f)
                "순대국밥" -> markedVector(3f)
                else -> error("예상 밖 입력: $text")
            }
        }
        val client = SpringAiTextEmbeddingClient(fake, DIMENSION)

        `when`("텍스트 3건을 embed 하면") {
            val vectors = client.embed(listOf("김치찌개", "된장찌개", "순대국밥"))

            then("3건의 벡터가 입력 순서 그대로 반환된다") {
                vectors shouldHaveSize 3
                vectors[0][0] shouldBe 1f
                vectors[1][0] shouldBe 2f
                vectors[2][0] shouldBe 3f
            }

            then("모든 벡터가 1024차원이다") {
                vectors.forEach { it.size shouldBe DIMENSION }
            }
        }
    }

    given("빈 텍스트 목록") {
        val fake = FakeEmbeddingModel { markedVector(1f) }
        val client = SpringAiTextEmbeddingClient(fake, DIMENSION)

        `when`("embed 하면") {
            val vectors = client.embed(emptyList())

            then("외부 호출 없이 빈 목록을 반환한다") {
                vectors.shouldBeEmpty()
                fake.callCount shouldBe 0
            }
        }
    }

    given("기대 차원(1024)과 다른 512차원 벡터를 반환하는 페이크 EmbeddingModel") {
        val fake = FakeEmbeddingModel { markedVector(1f, size = 512) }
        val client = SpringAiTextEmbeddingClient(fake, DIMENSION)

        `when`("embed 하면") {
            then("계약 위반으로 예외를 던진다(잘못된 벡터가 하류로 흐르지 않는다)") {
                shouldThrow<IllegalStateException> {
                    client.embed(listOf("김치찌개"))
                }
            }
        }
    }

    given("호출이 실패하는 페이크 EmbeddingModel") {
        val failure = RuntimeException("bedrock 호출 실패")
        val fake = object : EmbeddingModel {
            override fun call(request: EmbeddingRequest): EmbeddingResponse = throw failure
            override fun embed(document: Document): FloatArray = error("미사용")
        }
        val client = SpringAiTextEmbeddingClient(fake, DIMENSION)

        `when`("embed 하면") {
            then("실패가 호출자에게 그대로 전파된다(부분 성공 반환 없음)") {
                val thrown = shouldThrow<RuntimeException> {
                    client.embed(listOf("김치찌개", "된장찌개"))
                }
                thrown shouldBe failure
            }
        }
    }
})
