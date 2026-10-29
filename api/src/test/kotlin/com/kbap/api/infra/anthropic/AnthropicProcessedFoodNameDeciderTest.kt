package com.kbap.api.infra.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.ObjectMapper
import com.kbap.common.port.llm.NameCandidate
import com.kbap.common.port.llm.NameDecisionImageUnavailable
import com.kbap.common.port.llm.NameDecisionRefused
import com.kbap.common.port.llm.NameDecisionUnparsable
import com.kbap.common.port.llm.NameDecisionUsage
import com.sun.net.httpserver.HttpServer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress

class AnthropicProcessedFoodNameDeciderTest : BehaviorSpec({
    given("후보 목록 줄") {
        `when`("후보를 넘기면") {
            then("1부터 매긴 id·제품명·제조사를 탭으로 잇고 제조사가 없으면 빈칸이다") {
                AnthropicProcessedFoodNameDecider.candidateLines(listOf(NameCandidate("새우깡", "농심"), NameCandidate("포카칩", null))) shouldBe
                    "1\t새우깡\t농심\n2\t포카칩\t"
            }
        }
    }

    given("구조화 출력 해석") {
        fun parse(json: String) = AnthropicProcessedFoodNameDecider.parseJson(json, "claude-haiku-5-5", 1500, 30)

        `when`("후보 id 를 고르면") {
            then("모델이 답한 순번·확신도·읽은 이름·토큰을 돌려준다") {
                val decision = parse("""{"choice":2,"confidence":0.81,"readName":" 새우깡 "}""")

                decision.choice shouldBe 2
                decision.confidence shouldBe 0.81
                decision.readName shouldBe "새우깡"
                decision.inputTokens shouldBe 1500
                decision.outputTokens shouldBe 30
            }
        }

        `when`("choice 가 null 이거나 후보 범위 밖이면") {
            then("null 은 null, 범위 밖 순번은 그대로 돌려줘 호출부가 outside 로 남긴다") {
                parse("""{"choice":null,"confidence":0.6,"readName":"꼬북칩"}""").choice shouldBe null
                parse("""{"choice":4,"confidence":0.6,"readName":"꼬북칩"}""").choice shouldBe 4
            }
        }

        `when`("확신도가 0~1 밖이거나 읽은 이름이 비어 있으면") {
            then("확신도는 0~1 로 자르고 읽은 이름은 null 이다") {
                val decision = parse("""{"choice":1,"confidence":1.7,"readName":"  "}""")

                decision.confidence shouldBe 1.0
                decision.readName shouldBe null
            }
        }
    }

    given("SDK 로 실제 요청을 보내면") {
        fun serve(stopReason: String, text: String, status: Int = 200, error: String? = null, block: (String, () -> String) -> Unit) {
            var body = ""
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/v1/messages") { exchange ->
                body = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                val response = ObjectMapper().writeValueAsBytes(
                    if (error != null) mapOf("type" to "error", "error" to mapOf("type" to "invalid_request_error", "message" to error)) else mapOf(
                        "id" to "msg_1", "type" to "message", "role" to "assistant", "model" to "claude-haiku-5-5",
                        "content" to listOf(mapOf("type" to "text", "text" to text)),
                        "stop_reason" to stopReason, "stop_sequence" to null,
                        "usage" to mapOf("input_tokens" to 1960, "output_tokens" to 42),
                    ),
                )
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            try {
                block("http://127.0.0.1:${server.address.port}") { body }
            } finally {
                server.stop(0)
            }
        }

        fun decider(baseUrl: String) = AnthropicProcessedFoodNameDecider(
            AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(baseUrl).maxRetries(0).build(),
            "claude-haiku-5-5",
        )

        `when`("모델이 구조화 출력으로 답하면") {
            then("사진 URL·후보 줄·출력 스키마를 담아 보내고 답을 해석한다") {
                serve("end_turn", """{"choice":1,"confidence":0.9,"readName":"새우깡"}""") { baseUrl, body ->
                    val decision = decider(baseUrl).decide("https://cdn.test/a.jpg", listOf(NameCandidate("새우깡", "농심")))

                    decision.choice shouldBe 1
                    decision.model shouldBe "claude-haiku-5-5"
                    decision.inputTokens shouldBe 1960
                    val sent = ObjectMapper().readTree(body())
                    sent.path("model").asText() shouldBe "claude-haiku-5-5"
                    sent.path("messages")[0].path("content")[0].path("source").path("url").asText() shouldBe "https://cdn.test/a.jpg"
                    sent.path("messages")[0].path("content")[1].path("text").asText() shouldBe "1\t새우깡\t농심"
                    sent.path("output_config").path("format").path("type").asText() shouldBe "json_schema"
                    sent.path("output_config").path("format").path("schema").path("required").map { it.asText() } shouldBe listOf("choice", "confidence", "readName")
                }
            }
        }

        `when`("사진 URL 을 가져오지 못해 400 이 오면") {
            then("사진 없음 예외를 던진다") {
                serve("end_turn", "", status = 400, error = "Unable to download the file. Please verify the URL and try again.") { baseUrl, _ ->
                    shouldThrow<NameDecisionImageUnavailable> { decider(baseUrl).decide("https://cdn.test/a.jpg", listOf(NameCandidate("새우깡", null))) }
                }
            }
        }

        `when`("이미지와 무관한 400 이면") {
            then("사진 없음으로 보지 않는다") {
                serve("end_turn", "", status = 400, error = "max_tokens: Field required") { baseUrl, _ ->
                    val e = shouldThrow<RuntimeException> { decider(baseUrl).decide("https://cdn.test/a.jpg", listOf(NameCandidate("새우깡", null))) }
                    (e is NameDecisionImageUnavailable) shouldBe false
                }
            }
        }

        `when`("모델이 거절하면") {
            then("거절 예외를 던진다") {
                serve("refusal", "") { baseUrl, _ ->
                    val e = shouldThrow<NameDecisionRefused> { decider(baseUrl).decide("https://cdn.test/a.jpg", listOf(NameCandidate("새우깡", null))) }
                    e.usage shouldBe NameDecisionUsage("claude-haiku-5-5", 1960, 42)
                }
            }
        }

        `when`("토큰 상한에 잘려 JSON 이 깨지면") {
            then("해석 실패 예외에 사용량을 실어 던진다") {
                serve("max_tokens", """{"choice":1,"confid""") { baseUrl, _ ->
                    val e = shouldThrow<NameDecisionUnparsable> { decider(baseUrl).decide("https://cdn.test/a.jpg", listOf(NameCandidate("새우깡", null))) }
                    e.usage shouldBe NameDecisionUsage("claude-haiku-5-5", 1960, 42)
                }
            }
        }
    }
})
