package com.meogo.infra.llm.config

import com.meogo.infra.llm.model.LlmModelId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class LlmConfigurationOptionsTest : BehaviorSpec({

    val openAiBaseUrl = "https://api.openai.com/v1"
    val upstageBaseUrl = "https://api.upstage.ai/v1"

    given("OpenAI(gpt-5-nano) 옵션을 배선할 때") {
        val props = LlmModelProperties.ModelProps(
            apiKey = "x",
            model = "gpt-5-nano",
            maxOutputTokens = 2048,
            reasoningEffort = "minimal",
        )

        `when`("openAiChatOptions 를 만들면") {
            val options = LlmConfiguration.openAiChatOptions(LlmModelId.OPENAI, props, openAiBaseUrl)

            then("max-output-tokens 가 maxCompletionTokens 로 실린다(gpt-5 계열은 max_tokens 미지원)") {
                options.maxCompletionTokens shouldBe 2048
            }

            then("reasoning-effort=minimal 이 reasoningEffort 로 실린다") {
                options.reasoningEffort shouldBe "minimal"
            }

            then("maxTokens 는 싣지 않는다") {
                options.maxTokens.shouldBeNull()
            }
        }
    }

    given("Upstage(OpenAI 호환) 옵션을 배선할 때") {
        val props = LlmModelProperties.ModelProps(
            apiKey = "x",
            maxOutputTokens = 2048,
            reasoningEffort = "minimal",
        )

        `when`("openAiChatOptions 를 만들면") {
            val options = LlmConfiguration.openAiChatOptions(LlmModelId.UPSTAGE, props, upstageBaseUrl)

            then("max-output-tokens 가 표준 maxTokens 로 실린다") {
                options.maxTokens shouldBe 2048
            }

            then("reasoning-effort 를 지정해도 UPSTAGE 에는 싣지 않는다(reasoningEffort 는 OPENAI 전용 게이트)") {
                options.reasoningEffort.shouldBeNull()
            }

            then("maxCompletionTokens 도 싣지 않는다(OpenAI 전용 매핑)") {
                options.maxCompletionTokens.shouldBeNull()
            }
        }
    }

    given("Gemini 옵션을 배선할 때") {
        val props = LlmModelProperties.ModelProps(
            apiKey = "x",
            maxOutputTokens = 4096,
        )

        `when`("geminiChatOptions 를 만들면") {
            val options = LlmConfiguration.geminiChatOptions(props)

            then("max-output-tokens 가 maxOutputTokens 로 실린다") {
                options.maxOutputTokens shouldBe 4096
            }
        }
    }

    given("튜닝 프로퍼티가 null 인 환경(boot-safety)") {
        val untunedProps = LlmModelProperties.ModelProps(apiKey = "x")

        `when`("OpenAI 옵션을 만들면") {
            val options = LlmConfiguration.openAiChatOptions(LlmModelId.OPENAI, untunedProps, openAiBaseUrl)

            then("어떤 토큰 상한·추론 옵션도 실리지 않는다") {
                options.maxCompletionTokens.shouldBeNull()
                options.maxTokens.shouldBeNull()
                options.reasoningEffort.shouldBeNull()
            }
        }

        `when`("Gemini 옵션을 만들면") {
            val options = LlmConfiguration.geminiChatOptions(untunedProps)

            then("출력 상한이 실리지 않는다") {
                options.maxOutputTokens.shouldBeNull()
            }
        }
    }
})
