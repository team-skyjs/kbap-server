package com.kbap.common.infra.llm.claude

import com.kbap.common.infra.llm.ingredient.ClaudeIngredientDecider
import com.kbap.common.infra.llm.ingredient.OpenAiIngredientDecider
import com.kbap.common.infra.llm.menu.ClaudeMenuNameEstimator
import com.kbap.common.infra.llm.translation.ClaudeTextTranslator
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.port.llm.IngredientDecisionFood
import com.kbap.common.port.llm.MenuNameKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.math.BigDecimal

class ClaudeChatTest : BehaviorSpec({
    given("Claude 단일 호출") {
        `when`("이미지·스키마·effort 를 주고 부르면") {
            then("Messages API 형식으로 보내고, 사고 블록은 버리고 text 만 돌려주며, 단가대로 비용 이벤트를 1건 낸다") {
                FakeClaudeServer().use { server ->
                    server.respond(FakeClaudeServer.message("""{"ok":true}""", input = 1_000_000, output = 100_000, model = "claude-sonnet-5-5"))

                    val reply = server.chat(effort = "minimal").ask(
                        system = "sys",
                        userText = "hi",
                        imageUrl = "https://cdn.test/a.jpg",
                        jsonSchema = """{"type":"object","properties":{"ok":{"type":"boolean"}},"required":["ok"],"additionalProperties":false}""",
                        maxTokens = 123,
                    )

                    reply.text shouldBe """{"ok":true}"""
                    reply.finished shouldBe true
                    val request = server.requests.single()
                    request["model"].asText() shouldBe "claude-test"
                    request["max_tokens"].asLong() shouldBe 123
                    request["system"].asText() shouldBe "sys"
                    request.has("temperature") shouldBe false
                    request.has("thinking") shouldBe false
                    val content = request["messages"][0]["content"]
                    content[0]["type"].asText() shouldBe "image"
                    content[0]["source"]["type"].asText() shouldBe "url"
                    content[0]["source"]["url"].asText() shouldBe "https://cdn.test/a.jpg"
                    content[1]["text"].asText() shouldBe "hi"
                    request["output_config"]["effort"].asText() shouldBe "low"
                    request["output_config"]["format"]["type"].asText() shouldBe "json_schema"
                    request["output_config"]["format"]["schema"]["additionalProperties"].asBoolean() shouldBe false

                    val cost = server.events.single()
                    cost.modelName shouldBe "claude-sonnet-5-5"
                    cost.inputTokens shouldBe 1_000_000
                    cost.outputTokens shouldBe 100_000
                    cost.costUsd shouldBe BigDecimal("3.000000")
                    cost.costKrw shouldBe BigDecimal("4500.00")
                }
            }
        }

        `when`("effort·스키마 없이 부르면") {
            then("output_config 를 보내지 않는다 — 모델 기본값을 쓴다") {
                FakeClaudeServer().use { server ->
                    server.respond(FakeClaudeServer.message("x"))
                    server.chat().ask("sys", "hi")
                    server.requests.single().has("output_config") shouldBe false
                }
            }
        }
    }

    given("Claude 번역기") {
        `when`("응답이 max_tokens 로 잘려 오면") {
            then("잘린 번역을 쓰지 않고 실패한다") {
                FakeClaudeServer().use { server ->
                    server.respond(FakeClaudeServer.message("안녕", stopReason = "max_tokens"))
                    shouldThrow<IllegalStateException> { ClaudeTextTranslator(server.chat()).translate("hello", LanguageCode.KO) }
                        .message shouldContain "max_tokens"
                }
            }
        }

        `when`("끝까지 생성되면") {
            then("원문 길이 기준 출력 상한을 걸어 보내고 번역문을 돌려준다") {
                FakeClaudeServer().use { server ->
                    server.respond(FakeClaudeServer.message("안녕하세요"))
                    ClaudeTextTranslator(server.chat(), newMarker = { "LANG-x" }).translate("hello", LanguageCode.KO).text shouldBe "안녕하세요"
                    server.requests.single()["max_tokens"].asLong() shouldBe 2048L + 5 * 6
                }
            }
        }
    }

    given("Claude 메뉴 이름 추정·재료 판정") {
        `when`("구조화 출력을 받으면") {
            then("기존 OpenAI 구현과 같은 파서로 해석한다") {
                FakeClaudeServer().use { server ->
                    server.respond(
                        FakeClaudeServer.message(
                            """{"items":[{"name":"김치찌개","kind":"DISH","englishName":"Kimchi stew","description":"Stew.","avoidanceCodes":["PORK"],"spiciness":6,"confidence":0.9}]}""",
                        ),
                    )
                    val estimate = ClaudeMenuNameEstimator(server.chat()).estimate(listOf("김치찌개")).single()
                    estimate.kind shouldBe MenuNameKind.DISH
                    estimate.englishName shouldBe "Kimchi stew"
                    server.requests.single()["output_config"]["format"]["schema"]["required"][0].asText() shouldBe "items"
                }
                FakeClaudeServer().use { server ->
                    server.respond(FakeClaudeServer.message("""{"decisions":[{"code":"PORK","contains":true,"probability":0.8,"reason":"r"}]}"""))
                    val food = IngredientDecisionFood(koreanName = "김치찌개", description = "d", longDescription = null, currentCodes = emptyList())
                    val decisions = ClaudeIngredientDecider(server.chat()).decide(food, listOf(IngredientCode.PORK))
                    decisions.byCode.getValue(IngredientCode.PORK).probability shouldBe 0.8
                    server.requests.single()["system"].asText() shouldBe OpenAiIngredientDecider.SYSTEM_PROMPT
                }
            }
        }
    }
})
