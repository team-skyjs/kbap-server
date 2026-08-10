package com.kbap.infra.llm.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration

private val CALL_TIMEOUT: Duration = Duration.ofSeconds(5)

class LlmConfigurationOptionsTest : BehaviorSpec({

    val openAiBaseUrl = "https://api.openai.com/v1"

    given("OpenAI(gpt-5.6-luna) 옵션을 배선할 때") {
        val props = LlmModelProperties.ModelProps(
            apiKey = "x",
            model = "gpt-5.6-luna",
            maxOutputTokens = 2048,
            reasoningEffort = "minimal",
        )

        `when`("openAiChatOptions 를 만들면") {
            val options = LlmConfiguration.openAiChatOptions(props, openAiBaseUrl, CALL_TIMEOUT)

            then("max-output-tokens 가 maxCompletionTokens 로 실린다(추론 모델은 max_tokens 미지원)") {
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

    given("기피성분 전용 OpenAI 프로퍼티 병합") {
        val openai = LlmModelProperties.ModelProps(
            enabled = true,
            apiKey = "x",
            model = "gpt-5.6-luna",
            maxOutputTokens = 2048,
            reasoningEffort = "minimal",
            pricing = LlmModelProperties.PricingProps(0.2, 1.2),
        )

        `when`("model 과 pricing 만 오버라이드하면") {
            val merged = LlmConfiguration.avoidanceOpenAiProps(
                openai,
                LlmModelProperties.AvoidanceProps(
                    model = "gpt-5.6-luna",
                    pricing = LlmModelProperties.PricingProps(0.25, 2.00),
                ),
            )

            then("model·pricing 은 오버라이드 값으로 바뀐다") {
                merged.model shouldBe "gpt-5.6-luna"
                merged.pricing.inputUsdPerMillionTokens shouldBe 0.25
                merged.pricing.outputUsdPerMillionTokens shouldBe 2.00
            }

            then("api-key·reasoning-effort·max-output-tokens 는 openai 값을 상속한다") {
                merged.apiKey shouldBe "x"
                merged.reasoningEffort shouldBe "minimal"
                merged.maxOutputTokens shouldBe 2048
            }
        }

        `when`("오버라이드를 하나도 지정하지 않으면") {
            val merged = LlmConfiguration.avoidanceOpenAiProps(openai, LlmModelProperties.AvoidanceProps())

            then("openai 프로퍼티가 그대로 유지된다") {
                merged shouldBe openai
            }
        }
    }

    given("호출 타임아웃·재시도 배선") {
        val props = LlmModelProperties.ModelProps(apiKey = "x")

        `when`("call-timeout 을 주면") {
            val options = LlmConfiguration.openAiChatOptions(props, openAiBaseUrl, CALL_TIMEOUT)

            then("옵션의 timeout 으로 실린다") {
                options.timeout shouldBe CALL_TIMEOUT
            }
        }

        `when`("max-retries 를 0 으로 주면") {
            val noRetry = LlmModelProperties.ModelProps(apiKey = "x", maxRetries = 0)
            val options = LlmConfiguration.openAiChatOptions(noRetry, openAiBaseUrl, CALL_TIMEOUT)

            then("재시도하지 않아 총 대기시간이 timeout 을 넘지 않는다") {
                options.maxRetries shouldBe 0
            }
        }

        `when`("max-retries 를 주지 않으면") {
            val options = LlmConfiguration.openAiChatOptions(props, openAiBaseUrl, CALL_TIMEOUT)

            then("Spring AI 기본 재시도 횟수가 그대로 남아 총 대기시간이 timeout 의 배수가 된다") {
                options.maxRetries shouldBe 3
            }
        }
    }

    given("튜닝 프로퍼티가 null 인 환경(boot-safety)") {
        val untunedProps = LlmModelProperties.ModelProps(apiKey = "x")

        `when`("OpenAI 옵션을 만들면") {
            val options = LlmConfiguration.openAiChatOptions(untunedProps, openAiBaseUrl, CALL_TIMEOUT)

            then("어떤 토큰 상한·추론 옵션도 실리지 않는다") {
                options.maxCompletionTokens.shouldBeNull()
                options.maxTokens.shouldBeNull()
                options.reasoningEffort.shouldBeNull()
            }
        }
    }
})
