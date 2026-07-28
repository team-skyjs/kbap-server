package com.kbap.infra.llm.food

import com.kbap.common.core.food.TargetLanguageTexts
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest
import com.kbap.infra.llm.model.LlmModelId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private class StubCaller(val response: (LlmChatRequest) -> String) : LlmModelCaller {
    var lastRequest: LlmChatRequest? = null
    override val modelId = LlmModelId.OPENAI
    override fun call(request: LlmChatRequest): String {
        lastRequest = request
        return response(request)
    }
}

private fun translationJson(texts: Map<String, String>): String =
    """{"translations": {${texts.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" }}}}"""

private val ALL_NINE = TargetLanguageTexts.TARGET_LANGUAGES.associate { it.code to "${it.code}-번역" }

class SpringAiFoodNameTranslationClientTest : BehaviorSpec({
    given("9개 대상 언어를 모두 채워 응답하는 caller") {
        val caller = StubCaller { translationJson(ALL_NINE) }
        val client = SpringAiFoodNameTranslationClient(caller)

        `when`("음식명 번역을 호출하면") {
            val result = client.call("불고기")
            then("9개 언어 전수의 번역 맵을 반환한다") {
                result.texts.size shouldBe 9
            }
            then("프롬프트에 음식명이 포함된다") {
                (caller.lastRequest!!.prompt.contains("불고기")) shouldBe true
            }
        }
    }

    given("한 언어(en)가 누락된 응답을 주는 caller") {
        val caller = StubCaller { translationJson(ALL_NINE.filterKeys { it != "en" }) }
        val client = SpringAiFoodNameTranslationClient(caller)

        `when`("번역을 호출하면") {
            then("9개 전수 불변식 위반으로 예외를 전파한다") {
                shouldThrow<IllegalArgumentException> { client.call("불고기") }
            }
        }
    }

    given("코드펜스로 감싼 응답을 주는 caller") {
        val caller = StubCaller { "```json\n${translationJson(ALL_NINE)}\n```" }
        val client = SpringAiFoodNameTranslationClient(caller)

        `when`("번역을 호출하면") {
            then("코드펜스를 벗기고 정상 파싱한다") {
                client.call("불고기").texts.size shouldBe 9
            }
        }
    }
})
