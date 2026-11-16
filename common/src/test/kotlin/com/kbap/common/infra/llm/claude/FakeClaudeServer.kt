package com.kbap.common.infra.llm.claude

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.kbap.common.domain.metering.LlmCallCostIncurred
import com.kbap.common.infra.llm.model.LlmPricing
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

// 실제 Anthropic 을 부르지 않고 SDK 를 로컬 HTTP 서버에 붙인다 — 요청 본문(wire 형식)과 응답 해석을 함께 검증한다.
class FakeClaudeServer : AutoCloseable {
    data class Response(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

    val requests = CopyOnWriteArrayList<JsonNode>()
    val events = CopyOnWriteArrayList<LlmCallCostIncurred>()
    private val queue = ArrayDeque<Response>()
    private val handlers = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = handlers
        createContext("/") { exchange ->
            requests.add(mapper.readTree(exchange.requestBody.readBytes()))
            val response = synchronized(queue) { if (queue.size > 1) queue.removeFirst() else queue.first() }
            val bytes = response.body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            response.headers.forEach { (k, v) -> exchange.responseHeaders.add(k, v) }
            exchange.sendResponseHeaders(response.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}"

    fun respond(vararg responses: Response) = synchronized(queue) {
        queue.clear()
        queue.addAll(responses)
    }

    fun chat(effort: String? = null, model: String = "claude-test", pricing: LlmPricing = PRICING): ClaudeChat =
        ClaudeChat(
            AnthropicOkHttpClient.builder().apiKey("test-key").baseUrl(baseUrl).maxRetries(0).build(),
            model,
            effort,
            pricing,
        ) { events.add(it as LlmCallCostIncurred) }

    override fun close() {
        server.stop(0)
        handlers.shutdownNow()
    }

    companion object {
        private val mapper = ObjectMapper()
        val PRICING = LlmPricing(inputUsdPerMillionTokens = 2.0, outputUsdPerMillionTokens = 10.0, usdToKrw = 1500.0)

        fun message(text: String, stopReason: String = "end_turn", input: Long = 1_000, output: Long = 500, model: String = "claude-test"): Response =
            Response(
                200,
                mapper.writeValueAsString(
                    mapOf(
                        "id" to "msg_test",
                        "type" to "message",
                        "role" to "assistant",
                        "model" to model,
                        "content" to listOf(mapOf("type" to "thinking", "thinking" to "", "signature" to "sig"), mapOf("type" to "text", "text" to text)),
                        "stop_reason" to stopReason,
                        "stop_sequence" to null,
                        "usage" to mapOf("input_tokens" to input, "output_tokens" to output),
                    ),
                ),
            )

        fun error(status: Int, type: String, message: String = "x", headers: Map<String, String> = emptyMap()): Response =
            Response(status, mapper.writeValueAsString(mapOf("type" to "error", "error" to mapOf("type" to type, "message" to message))), headers)
    }
}
