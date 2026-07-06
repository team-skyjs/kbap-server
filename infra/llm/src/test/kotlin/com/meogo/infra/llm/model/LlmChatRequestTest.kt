package com.meogo.infra.llm.model

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LlmChatRequestTest : BehaviorSpec({

    given("LlmChatRequest 생성") {
        `when`("prompt 가 빈 문자열이면") {
            then("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> { LlmChatRequest(prompt = "") }
            }
        }

        `when`("prompt 가 공백만으로 이루어지면") {
            then("IllegalArgumentException 을 던진다") {
                shouldThrow<IllegalArgumentException> { LlmChatRequest(prompt = "   ") }
            }
        }

        `when`("prompt 가 정상 문자열이면") {
            then("요청이 생성되고 prompt 가 보존된다") {
                val request = LlmChatRequest(prompt = "안녕")
                request.prompt shouldBe "안녕"
            }
        }
    }
})
