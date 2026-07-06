package com.meogo.infra.llm.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LlmConfigurationBaseUrlTest : BehaviorSpec({

    given("OpenAI 호환 caller 의 base-url 을 해석할 때") {
        `when`("base-url 을 지정하지 않았으면(null)") {
            then("공식 OpenAI 엔드포인트인 https://api.openai.com/v1 로 해석된다") {
                LlmConfiguration.resolveOpenAiBaseUrl(null) shouldBe "https://api.openai.com/v1"
            }

            then("해석 결과가 /v1 로 끝난다") {
                LlmConfiguration.resolveOpenAiBaseUrl(null).endsWith("/v1") shouldBe true
            }
        }

        `when`("base-url 을 명시적으로 지정했으면") {
            then("지정한 값을 그대로 사용한다") {
                LlmConfiguration.resolveOpenAiBaseUrl("https://api.upstage.ai/v1") shouldBe "https://api.upstage.ai/v1"
            }
        }
    }

    given("OpenAI 기본 base-url 상수") {
        `when`("상수 값을 확인하면") {
            then("경로 접두 /v1 을 포함한 https://api.openai.com/v1 이다") {
                LlmConfiguration.DEFAULT_OPENAI_BASE_URL shouldBe "https://api.openai.com/v1"
            }
        }
    }
})
