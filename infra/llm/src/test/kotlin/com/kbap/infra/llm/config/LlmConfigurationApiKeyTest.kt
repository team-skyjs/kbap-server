package com.kbap.infra.llm.config

import com.kbap.infra.llm.model.LlmModelId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.core.spec.style.BehaviorSpec

class LlmConfigurationApiKeyTest : BehaviorSpec({

    given("OpenAI 호환 caller 가 활성 상태(enabled)로 자격증명을 검증할 때") {
        `when`("api-key 가 null 이면") {
            then("환경변수 폴백을 허용하지 않고 예외로 즉시 실패한다") {
                shouldThrow<IllegalStateException> {
                    LlmConfiguration.requireOpenAiApiKey(LlmModelId.UPSTAGE, null)
                }
            }

            then("실패 메시지에 어떤 모델(upstage)의 문제인지가 드러난다") {
                val thrown = shouldThrow<IllegalStateException> {
                    LlmConfiguration.requireOpenAiApiKey(LlmModelId.UPSTAGE, null)
                }
                thrown.message.orEmpty() shouldContainIgnoringCase "upstage"
            }
        }

        `when`("api-key 가 공백 문자열이면") {
            then("환경변수 폴백을 허용하지 않고 예외로 즉시 실패한다") {
                shouldThrow<IllegalStateException> {
                    LlmConfiguration.requireOpenAiApiKey(LlmModelId.UPSTAGE, "   ")
                }
            }
        }

        `when`("api-key 가 유효한 값이면") {
            then("해당 키를 그대로 반환한다") {
                LlmConfiguration.requireOpenAiApiKey(LlmModelId.OPENAI, "sk-real-key") shouldBe "sk-real-key"
            }
        }
    }
})
