package com.kbap.infra.llm.embedding

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelRequest
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelResponse

private const val MODEL_ID = "amazon.titan-embed-text-v2:0"

private const val DIMENSION = 256

private class FakeBedrockRuntimeClient(
    private val vectorFor: (String) -> FloatArray,
) : BedrockRuntimeClient {
    val requests = mutableListOf<InvokeModelRequest>()

    override fun invokeModel(request: InvokeModelRequest): InvokeModelResponse {
        requests += request
        val inputText = jacksonObjectMapper().readTree(request.body().asUtf8String())["inputText"].asText()
        val embedding = vectorFor(inputText).joinToString(",")
        return InvokeModelResponse.builder()
            .body(SdkBytes.fromUtf8String("""{"embedding":[$embedding]}"""))
            .build()
    }

    override fun serviceName(): String = "bedrock-runtime"

    override fun close() = Unit
}

private fun markedVector(marker: Float, size: Int = DIMENSION): FloatArray =
    FloatArray(size) { 0f }.also { it[0] = marker }

private fun requestBody(request: InvokeModelRequest) =
    jacksonObjectMapper().readTree(request.body().asUtf8String())

class BedrockTitanTextEmbeddingClientTest : BehaviorSpec({

    given("Titan 임베딩을 돌려주는 페이크 Bedrock 런타임") {
        `when`("텍스트 한 건을 embed 하면") {
            then("설정한 모델로 inputText·dimensions·normalize 를 실어 호출한다") {
                val fake = FakeBedrockRuntimeClient { markedVector(1f) }

                BedrockTitanTextEmbeddingClient(fake, MODEL_ID, DIMENSION).embed(listOf("마라샹궈"))

                fake.requests.size shouldBe 1
                fake.requests.first().modelId() shouldBe MODEL_ID
                val body = requestBody(fake.requests.first())
                body["inputText"].asText() shouldBe "마라샹궈"
                body["dimensions"].asInt() shouldBe DIMENSION
                body["normalize"].asBoolean() shouldBe true
            }
        }

        `when`("텍스트 두 건을 embed 하면") {
            then("건당 한 번씩 호출하고 입력 순서대로 벡터를 돌려준다") {
                val fake = FakeBedrockRuntimeClient { text ->
                    when (text) {
                        "김치찌개" -> markedVector(1f)
                        "된장찌개" -> markedVector(2f)
                        else -> error("예상 밖 입력: $text")
                    }
                }

                val vectors = BedrockTitanTextEmbeddingClient(fake, MODEL_ID, DIMENSION)
                    .embed(listOf("김치찌개", "된장찌개"))

                fake.requests.size shouldBe 2
                vectors.size shouldBe 2
                vectors[0][0] shouldBe 1f
                vectors[1][0] shouldBe 2f
                vectors.forEach { it.size shouldBe DIMENSION }
            }
        }
    }

    given("설정과 다른 차원을 돌려주는 페이크 Bedrock 런타임") {
        `when`("embed 하면") {
            then("계약 위반으로 예외를 던진다 — 잘못된 벡터가 하류로 흐르지 않는다") {
                val fake = FakeBedrockRuntimeClient { markedVector(1f, size = 1024) }

                shouldThrow<IllegalStateException> {
                    BedrockTitanTextEmbeddingClient(fake, MODEL_ID, DIMENSION).embed(listOf("마라샹궈"))
                }
            }
        }
    }
})
