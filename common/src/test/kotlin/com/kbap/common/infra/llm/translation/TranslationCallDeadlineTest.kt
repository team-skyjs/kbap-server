package com.kbap.common.infra.llm.translation

import com.kbap.common.domain.LanguageCode
import com.kbap.common.infra.llm.config.LlmConfiguration
import com.kbap.common.infra.llm.config.LlmModelProperties
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.context.ApplicationEventPublisher
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class TranslationCallDeadlineTest : BehaviorSpec({

    val reply = (
        """{"id":"chatcmpl-test","object":"chat.completion","created":1,"model":"gpt-test",""" +
            """"choices":[{"index":0,"message":{"role":"assistant","content":"안녕하세요"},"finish_reason":"stop"}],""" +
            """"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}"""
        ).toByteArray()

    data class Outcome(val text: String?, val elapsedMillis: Long, val requests: Int)

    fun translateAgainst(respond: (HttpExchange, Int) -> Unit): Outcome {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/") { exchange ->
            exchange.requestBody.readBytes()
            runCatching { respond(exchange, requests.incrementAndGet()) }
        }
        server.start()
        try {
            val props = LlmModelProperties.VisionProps(
                enabled = true,
                apiKey = "test-key",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                model = "gpt-test",
                timeout = Duration.ofSeconds(1),
            )
            val translator = LlmConfiguration().textTranslator(LlmModelProperties(translation = props), ApplicationEventPublisher { })
            val started = System.nanoTime()
            val text = runCatching { translator.translate("hello there", LanguageCode.KO).text }.getOrNull()
            return Outcome(text, (System.nanoTime() - started) / 1_000_000, requests.get())
        } finally {
            server.stop(0)
        }
    }

    fun HttpExchange.send(status: Int, body: ByteArray) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(status, body.size.toLong())
        responseBody.use { it.write(body) }
    }

    given("번역 엔진 호출의 시간 제한(운영과 같은 조립, 제한 1초)") {
        `when`("엔진이 제때 응답하면") {
            then("번역문을 받는다") {
                translateAgainst { exchange, _ -> exchange.send(200, reply) }.text shouldBe "안녕하세요"
            }
        }

        `when`("엔진이 제한을 넘겨 3초 뒤에 응답하면") {
            then("제한 시각 부근에서 실패한다 — 제한은 호출 전체 시간에 걸린다") {
                val outcome = translateAgainst { exchange, _ ->
                    Thread.sleep(3_000)
                    exchange.send(200, reply)
                }

                outcome.text shouldBe null
                outcome.elapsedMillis shouldBeLessThan 2_500L
            }
        }

        `when`("엔진이 응답을 조금씩 흘려보내 4초에 걸쳐 끝내면") {
            then("그래도 제한 시각 부근에서 실패한다 — 바이트가 올 때마다 제한이 새로 시작되지 않는다") {
                val outcome = translateAgainst { exchange, _ ->
                    exchange.responseHeaders.add("Content-Type", "application/json")
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.use { out ->
                        reply.toList().chunked((reply.size / 10).coerceAtLeast(1)).forEach { chunk ->
                            out.write(chunk.toByteArray())
                            out.flush()
                            Thread.sleep(400)
                        }
                    }
                }

                outcome.text shouldBe null
                outcome.elapsedMillis shouldBeLessThan 2_500L
            }
        }

        `when`("엔진이 5xx·429 로 답하면") {
            then("다시 보내지 않는다 — 재시도가 제한 시간을 여러 번 쓰지 않는다") {
                listOf(500, 503, 429).forEach { status ->
                    val outcome = translateAgainst { exchange, _ -> exchange.send(status, """{"error":{"message":"x","type":"server_error"}}""".toByteArray()) }

                    outcome.text shouldBe null
                    outcome.requests shouldBe 1
                }
            }
        }
    }
})
