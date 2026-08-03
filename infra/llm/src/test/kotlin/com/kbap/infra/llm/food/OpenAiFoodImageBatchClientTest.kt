package com.kbap.infra.llm.food

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.kbap.common.port.llm.FoodImageBatchClient
import com.kbap.infra.llm.config.LlmModelProperties
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.util.Base64

class OpenAiFoodImageBatchClientTest : BehaviorSpec({
    val mapper = jacksonObjectMapper()
    val client = OpenAiFoodImageBatchClient(
        LlmModelProperties.ImageProps(
            apiKey = "test-key",
            model = "gpt-image-2",
            size = "1024x1024",
            quality = "medium",
            outputFormat = "webp",
            outputCompression = 80,
        ),
    )

    given("JSONL 요청 줄 조립") {
        `when`("entry(customId=food PK, prompt) 로 요청 줄을 만들면") {
            then("Batch API 스펙(custom_id·method·url·body)대로 직렬화된다") {
                val line = client.requestLineOf(FoodImageBatchClient.Entry("42", "한국 음식 \"김치찌개\" 사진"))

                val node = mapper.readTree(line)
                node.path("custom_id").asText() shouldBe "42"
                node.path("method").asText() shouldBe "POST"
                node.path("url").asText() shouldBe "/v1/images/generations"
                node.path("body").path("prompt").asText() shouldBe "한국 음식 \"김치찌개\" 사진"
                node.path("body").path("model").asText() shouldBe "gpt-image-2"
                node.path("body").path("size").asText() shouldBe "1024x1024"
                node.path("body").path("quality").asText() shouldBe "medium"
            }
        }

        `when`("출력 포맷이 설정돼 있으면") {
            then("body 에 output_format·output_compression 을 실어 webp 원본을 받는다") {
                val node = mapper.readTree(client.requestLineOf(FoodImageBatchClient.Entry("42", "p")))

                node.path("body").path("output_format").asText() shouldBe "webp"
                node.path("body").path("output_compression").asInt() shouldBe 80
            }
        }

        `when`("model·size·quality·출력 포맷 미설정이면") {
            then("body 에 해당 필드를 넣지 않는다 — OpenAI 기본값 사용") {
                val bare = OpenAiFoodImageBatchClient(LlmModelProperties.ImageProps(apiKey = "k"))

                val node = mapper.readTree(bare.requestLineOf(FoodImageBatchClient.Entry("1", "p")))

                node.path("body").has("model") shouldBe false
                node.path("body").has("size") shouldBe false
                node.path("body").has("output_format") shouldBe false
                node.path("body").has("output_compression") shouldBe false
            }
        }
    }

    given("결과 줄 파싱") {
        `when`("성공 줄(b64_json + usage)을 파싱하면") {
            then("디코딩된 bytes 와 usage 를 돌려준다") {
                val imageBytes = byteArrayOf(9, 8, 7)
                val line = mapper.writeValueAsString(
                    mapOf(
                        "custom_id" to "42",
                        "response" to mapOf(
                            "status_code" to 200,
                            "body" to mapOf(
                                "data" to listOf(mapOf("b64_json" to Base64.getEncoder().encodeToString(imageBytes))),
                                "usage" to mapOf("input_tokens" to 120, "output_tokens" to 4160),
                            ),
                        ),
                    ),
                )

                val result = client.parseResultLine(line)

                result.customId shouldBe "42"
                result.errorMessage.shouldBeNull()
                result.bytes!!.toList() shouldBe imageBytes.toList()
                result.usage.shouldNotBeNull()
                result.usage!!.inputTokens shouldBe 120
                result.usage!!.outputTokens shouldBe 4160
            }
        }

        `when`("error 필드가 있는 실패 줄을 파싱하면") {
            then("bytes 없이 에러 메시지를 돌려준다") {
                val line = mapper.writeValueAsString(
                    mapOf(
                        "custom_id" to "43",
                        "error" to mapOf("message" to "rate limit exceeded"),
                    ),
                )

                val result = client.parseResultLine(line)

                result.bytes.shouldBeNull()
                result.errorMessage shouldBe "rate limit exceeded"
            }
        }

        `when`("status_code 가 4xx 인 응답 줄을 파싱하면") {
            then("body.error.message 를 에러로 돌려준다") {
                val line = mapper.writeValueAsString(
                    mapOf(
                        "custom_id" to "44",
                        "response" to mapOf(
                            "status_code" to 400,
                            "body" to mapOf("error" to mapOf("message" to "invalid prompt")),
                        ),
                    ),
                )

                val result = client.parseResultLine(line)

                result.bytes.shouldBeNull()
                result.errorMessage shouldBe "invalid prompt"
            }
        }

        `when`("성공 status 인데 b64 데이터가 비어 있으면") {
            then("데이터 없음 에러로 처리한다") {
                val line = mapper.writeValueAsString(
                    mapOf(
                        "custom_id" to "45",
                        "response" to mapOf("status_code" to 200, "body" to mapOf("data" to emptyList<Any>())),
                    ),
                )

                client.parseResultLine(line).errorMessage shouldBe "b64 데이터 없음"
            }
        }
    }
})
