package com.kbap.infra.llm.food

import com.kbap.core.food.TargetLanguageTexts
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest
import com.kbap.infra.llm.model.LlmModelId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

private class DescStubCaller(val response: String) : LlmModelCaller {
    override val modelId = LlmModelId.OPENAI
    override fun call(request: LlmChatRequest): String = response
}

private val NINE_TRANSLATIONS = TargetLanguageTexts.TARGET_LANGUAGES.associate { it.code to "${it.code}-설명" }

private fun descriptionJson(description: String, spiciness: Int, translations: Map<String, String> = NINE_TRANSLATIONS): String {
    val t = translations.entries.joinToString(", ") { "\"${it.key}\": \"${it.value}\"" }
    return """{"description": "$description", "spiciness": $spiciness, "translations": {$t}}"""
}

class SpringAiFoodDescriptionClientTest : BehaviorSpec({
    given("정상 설명 응답을 주는 caller") {
        val client = SpringAiFoodDescriptionClient(DescStubCaller(descriptionJson("불고기는 달콤 짭짤한 한국식 소고기 요리다.", 2)))

        `when`("설명 생성을 호출하면") {
            val result = client.call("불고기")
            then("설명·맵기·9개 번역을 담은 콘텐츠를 반환한다") {
                result.spiciness shouldBe 2
                result.translations.texts.size shouldBe 9
            }
        }
    }

    given("256자 설명(255자 초과)을 주는 caller") {
        val client = SpringAiFoodDescriptionClient(DescStubCaller(descriptionJson("가".repeat(256), 3)))

        `when`("설명 생성을 호출하면") {
            then("255자 제한 위반으로 예외를 전파한다") {
                shouldThrow<IllegalArgumentException> { client.call("불고기") }
            }
        }
    }

    given("맵기가 범위 밖(11)인 응답을 주는 caller") {
        val client = SpringAiFoodDescriptionClient(DescStubCaller(descriptionJson("설명", 11)))

        `when`("설명 생성을 호출하면") {
            then("맵기 0..10 위반으로 예외를 전파한다") {
                shouldThrow<IllegalArgumentException> { client.call("불고기") }
            }
        }
    }

    given("플레이스홀더 설명을 주는 caller") {
        val client = SpringAiFoodDescriptionClient(DescStubCaller(descriptionJson("설명 준비 중", 1)))

        `when`("설명 생성을 호출하면") {
            then("플레이스홀더 금지 위반으로 예외를 전파한다") {
                shouldThrow<IllegalArgumentException> { client.call("불고기") }
            }
        }
    }
})
