package com.kbap.common.infra.llm.embedding

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.infra.llm.config.LlmModelProperties
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress

private const val MODEL = "text-embedding-3-small"

private const val DIMENSION = 256

private const val API_KEY = "test-key"

private class CapturedRequest(
    val path: String,
    val authorization: String?,
    val body: JsonNode,
)

private class FakeOpenAi(private val respond: (JsonNode) -> String) {
    val requests = mutableListOf<CapturedRequest>()
    private val server = HttpServer.create(InetSocketAddress(0), 0)

    val baseUrl: String
        get() = "http://localhost:${server.address.port}"

    fun start(): FakeOpenAi {
        server.createContext("/") { exchange ->
            val body = jacksonObjectMapper().readTree(exchange.requestBody.readBytes())
            requests += CapturedRequest(
                path = exchange.requestURI.path,
                authorization = exchange.requestHeaders.getFirst("Authorization"),
                body = body,
            )
            val response = respond(body).toByteArray()
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        return this
    }

    fun stop() = server.stop(0)
}

private fun embeddingResponse(vararg indexedVectors: Pair<Int, FloatArray>): String {
    val data = indexedVectors.joinToString(",") { (index, vector) ->
        """{"object":"embedding","index":$index,"embedding":[${vector.joinToString(",")}]}"""
    }
    return """{"object":"list","model":"$MODEL","data":[$data],"usage":{"prompt_tokens":1,"total_tokens":1}}"""
}

private fun markedVector(marker: Float, size: Int = DIMENSION): FloatArray =
    FloatArray(size) { 0f }.also { it[0] = marker }

private fun client(baseUrl: String) = OpenAiTextEmbeddingClient(
    LlmModelProperties.EmbeddingProps(
        enabled = true,
        apiKey = API_KEY,
        model = MODEL,
        dimension = DIMENSION,
    ),
    "$baseUrl/v1",
)

class OpenAiTextEmbeddingClientTest : BehaviorSpec({

    given("임베딩을 돌려주는 페이크 OpenAI") {
        `when`("텍스트 두 건을 embed 하면") {
            then("한 번의 호출에 모델·입력 배열·차원·인증 헤더를 실어 보낸다") {
                val fake = FakeOpenAi {
                    embeddingResponse(0 to markedVector(1f), 1 to markedVector(2f))
                }.start()

                try {
                    client(fake.baseUrl).embed(listOf("마라샹궈", "김치찌개"))
                } finally {
                    fake.stop()
                }

                fake.requests.size shouldBe 1
                val request = fake.requests.first()
                request.path shouldBe "/v1/embeddings"
                request.authorization shouldBe "Bearer $API_KEY"
                request.body["model"].asText() shouldBe MODEL
                request.body["input"].map { it.asText() } shouldBe listOf("마라샹궈", "김치찌개")
                request.body["dimensions"].asInt() shouldBe DIMENSION
            }
        }

        `when`("응답의 순서가 입력과 뒤바뀌어 오면") {
            then("index 기준으로 입력 순서에 맞춰 돌려준다") {
                val fake = FakeOpenAi {
                    embeddingResponse(1 to markedVector(2f), 0 to markedVector(1f))
                }.start()

                val vectors = try {
                    client(fake.baseUrl).embed(listOf("마라샹궈", "김치찌개"))
                } finally {
                    fake.stop()
                }

                vectors.size shouldBe 2
                vectors[0][0] shouldBe 1f
                vectors[1][0] shouldBe 2f
                vectors.forEach { it.size shouldBe DIMENSION }
            }
        }

        `when`("응답 차원이 설정과 다르면") {
            then("계약 위반으로 예외를 던진다 — 잘못된 벡터가 하류로 흐르지 않는다") {
                val fake = FakeOpenAi {
                    embeddingResponse(0 to markedVector(1f, size = 1536))
                }.start()

                try {
                    shouldThrow<IllegalStateException> { client(fake.baseUrl).embed(listOf("마라샹궈")) }
                } finally {
                    fake.stop()
                }
            }
        }

        `when`("빈 목록을 embed 하면") {
            then("외부 호출 없이 빈 목록을 돌려준다") {
                val fake = FakeOpenAi { embeddingResponse(0 to markedVector(1f)) }.start()

                val vectors = try {
                    client(fake.baseUrl).embed(emptyList())
                } finally {
                    fake.stop()
                }

                vectors shouldBe emptyList()
                fake.requests.size shouldBe 0
            }
        }
    }
})
