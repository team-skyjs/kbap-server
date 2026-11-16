package com.kbap.common.infra.llm.menu

import com.kbap.common.infra.llm.claude.FakeClaudeServer
import com.kbap.common.infra.llm.claude.FakeClaudeServer.Companion.error
import com.kbap.common.infra.llm.claude.FakeClaudeServer.Companion.message
import com.kbap.common.port.llm.MenuBoardVisionQuotaExhaustedException
import com.kbap.common.port.llm.MenuBoardVisionRateLimitedException
import com.kbap.common.port.llm.MenuBoardVisionUnavailableException
import com.kbap.common.port.llm.OcrItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.time.Duration
import java.time.Instant

class ClaudeMenuBoardVisionExtractorTest : BehaviorSpec({
    // 가짜 시계 — sleep 이 시계를 앞으로 민다(실제로 기다리지 않는다).
    fun extractor(server: FakeClaudeServer, budget: Duration = Duration.ofSeconds(10)): Pair<ClaudeMenuBoardVisionExtractor, MutableList<Duration>> {
        var clock = Instant.parse("2026-11-15T00:00:00Z")
        val sleeps = mutableListOf<Duration>()
        val extractor = ClaudeMenuBoardVisionExtractor(
            chat = server.chat(effort = "medium"),
            parser = MenuBoardResultParser(),
            imageBaseUrl = "https://cdn.test/",
            retryBudget = budget,
            sleep = { sleeps += it; clock = clock.plus(it) },
            now = { clock },
        )
        return extractor to sleeps
    }

    given("Claude 메뉴판 비전") {
        `when`("정상 응답이면") {
            then("CDN URL 이미지를 보내고 기존 파서로 메뉴를 읽는다") {
                FakeClaudeServer().use { server ->
                    server.respond(message("""{"results":[{"name":"김치찌개","koreanName":"김치찌개","price":9000,"matchedIdx":0,"optionLabel":null,"baseName":"김치찌개"}]}"""))
                    val (vision, _) = extractor(server)

                    val menus = vision.extract("/scans/a.png", listOf(OcrItem(0, "김치찌개")))

                    menus.single().priceKrw shouldBe 9000
                    val content = server.requests.single()["messages"][0]["content"]
                    content[0]["source"]["url"].asText() shouldBe "https://cdn.test/scans/a.png"
                    server.requests.single()["system"].asText() shouldBe OpenAiMenuBoardVisionExtractor.SYSTEM_PROMPT
                    server.events.size shouldBe 1
                }
            }
        }

        `when`("크레딧 소진(billing_error)이면") {
            then("재시도 없이 한도 소진으로 알린다") {
                FakeClaudeServer().use { server ->
                    server.respond(error(400, "billing_error", "Your credit balance is too low"))
                    val (vision, sleeps) = extractor(server)
                    shouldThrow<MenuBoardVisionQuotaExhaustedException> { vision.extractLines("a.jpg") }.code shouldBe "billing_error"
                    server.requests.size shouldBe 1
                    sleeps.size shouldBe 0
                }
            }
        }

        `when`("지출 한도 초과가 invalid_request_error 로 오면") {
            then("문구로 한도 소진을 알아본다") {
                FakeClaudeServer().use { server ->
                    server.respond(error(400, "invalid_request_error", "You have reached your specified API usage limits."))
                    val (vision, _) = extractor(server)
                    shouldThrow<MenuBoardVisionQuotaExhaustedException> { vision.read("a.jpg") }
                }
            }
        }

        `when`("429 가 retry-after 와 함께 오고 예산 안이면") {
            then("기다렸다 다시 보내 성공한다") {
                FakeClaudeServer().use { server ->
                    server.respond(error(429, "rate_limit_error", headers = mapOf("retry-after" to "2")), message("""{"results":[]}"""))
                    val (vision, sleeps) = extractor(server)
                    vision.extractLines("a.jpg") shouldBe emptyList()
                    sleeps shouldBe listOf(Duration.ofSeconds(2))
                    server.requests.size shouldBe 2
                }
            }
        }

        `when`("429 의 retry-after 가 예산을 넘으면") {
            then("속도 제한(예산 소진)으로 알리고 한도 헤더를 남긴다") {
                FakeClaudeServer().use { server ->
                    server.respond(
                        error(429, "rate_limit_error", headers = mapOf("retry-after" to "30", "anthropic-ratelimit-requests-remaining" to "0")),
                    )
                    val (vision, _) = extractor(server)
                    val e = shouldThrow<MenuBoardVisionRateLimitedException> { vision.extractLines("a.jpg") }
                    e.exhausted shouldBe true
                    e.retryAfterSeconds shouldBe 30
                    e.message!! shouldContain "requests-remaining=0"
                }
            }
        }

        `when`("429 에 x-should-retry: false 가 오면") {
            then("기다리지 않고 속도 제한(예산 미소진)으로 알린다") {
                FakeClaudeServer().use { server ->
                    server.respond(error(429, "rate_limit_error", headers = mapOf("x-should-retry" to "false")))
                    val (vision, sleeps) = extractor(server)
                    shouldThrow<MenuBoardVisionRateLimitedException> { vision.extractLines("a.jpg") }.exhausted shouldBe false
                    sleeps.size shouldBe 0
                }
            }
        }

        `when`("529 overloaded 가 예산을 넘겨 계속되면") {
            then("예산 안에서 재시도하다 일시 불가로 알린다") {
                FakeClaudeServer().use { server ->
                    server.respond(error(529, "overloaded_error"))
                    val (vision, sleeps) = extractor(server, budget = Duration.ofSeconds(3))
                    shouldThrow<MenuBoardVisionUnavailableException> { vision.extractLines("a.jpg") }
                    (server.requests.size > 1) shouldBe true
                    server.requests.size shouldBe sleeps.size + 1
                }
            }
        }

        `when`("그 밖의 400 이면") {
            then("비전 예외로 바꾸지 않고 그대로 던진다") {
                FakeClaudeServer().use { server ->
                    server.respond(error(400, "invalid_request_error", "bad image"))
                    val (vision, _) = extractor(server)
                    shouldThrow<com.anthropic.errors.BadRequestException> { vision.extractLines("a.jpg") }
                    server.requests.size shouldBe 1
                }
            }
        }
    }
})
