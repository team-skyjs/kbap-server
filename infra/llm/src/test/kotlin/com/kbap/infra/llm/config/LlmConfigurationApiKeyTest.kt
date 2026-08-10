package com.kbap.infra.llm.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.core.spec.style.BehaviorSpec

class LlmConfigurationApiKeyTest : BehaviorSpec({

    given("OpenAI caller 가 활성 상태(enabled)로 자격증명을 검증할 때") {
        `when`("api-key 가 null 이면") {
            then("예외로 즉시 실패하고 메시지에 어떤 프로퍼티를 채워야 하는지가 드러난다") {
                val thrown = shouldThrow<IllegalStateException> {
                    LlmConfiguration.requireOpenAiApiKey(LlmConfiguration.OPENAI_API_KEY_PROPERTY, null)
                }
                thrown.message.orEmpty() shouldContain "kbap.llm.openai.api-key"
            }
        }

        `when`("api-key 가 공백 문자열이면") {
            then("환경변수 폴백을 허용하지 않고 예외로 즉시 실패한다") {
                shouldThrow<IllegalStateException> {
                    LlmConfiguration.requireOpenAiApiKey(LlmConfiguration.OPENAI_API_KEY_PROPERTY, "   ")
                }
            }
        }

        `when`("api-key 가 유효한 값이면") {
            then("해당 키를 그대로 반환한다") {
                LlmConfiguration.requireOpenAiApiKey(
                    LlmConfiguration.OPENAI_API_KEY_PROPERTY,
                    "sk-real-key",
                ) shouldBe "sk-real-key"
            }
        }
    }
})
