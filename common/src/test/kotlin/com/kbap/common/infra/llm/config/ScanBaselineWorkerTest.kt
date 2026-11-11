package com.kbap.common.infra.llm.config

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.sun.net.httpserver.HttpServer
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.net.InetSocketAddress

class ScanBaselineWorkerTest : BehaviorSpec({
    given("무힌트 기준선 실행기") {
        `when`("로컬 HTTP 대역이 판정과 usage를 응답하면") {
            then("실제 어댑터가 정답 없는 요청을 보내고 결과와 토큰을 전달한다") {
                val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
                var body = ""
                val path = "eval/scan-hint/v1/" + "a".repeat(64) + ".jpg"
                server.createContext("/v1/chat/completions") { ex ->
                    body = ex.requestBody.bufferedReader().readText()
                    val response = """{"id":"test","object":"chat.completion","created":1,"model":"gpt-5.6-luna","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"{\"kind\":\"MENU\"}"}}],"usage":{"prompt_tokens":2000,"completion_tokens":500,"total_tokens":2500}}""".toByteArray()
                    ex.responseHeaders.add("Content-Type", "application/json")
                    ex.sendResponseHeaders(200, response.size.toLong())
                    ex.responseBody.use { it.write(response) }
                }
                server.start()
                try {
                    val lines = mutableListOf<String>()
                    ScanBaselineWorker.evaluate("local-fixture", "http://127.0.0.1:${server.address.port}/v1", sequenceOf(path), lines::add)
                    lines.single().split("\t").take(5) shouldBe listOf("RESULT", "MENU", "2000", "500", "gpt-5.6-luna")
                    val req = jacksonObjectMapper().readTree(body)
                    req["model"].asText() shouldBe "gpt-5.6-luna"
                    req["messages"].size() shouldBe 2
                    body.contains("https://d29c1cr2ng7w0.cloudfront.net/$path") shouldBe true
                    body.contains("deviceHint") shouldBe false
                } finally {
                    server.stop(0)
                }
            }
        }
    }
})
