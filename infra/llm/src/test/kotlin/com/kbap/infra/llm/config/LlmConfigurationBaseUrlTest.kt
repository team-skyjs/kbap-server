package com.kbap.infra.llm.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class LlmConfigurationBaseUrlTest : BehaviorSpec({

    given("OpenAI 호환 caller 의 base-url 을 해석할 때") {
        `when`("base-url 을 지정하지 않았으면(null)") {
            then("공식 OpenAI 엔드포인트인 https://api.openai.com/v1 로 해석된다") {
                LlmConfiguration.resolveOpenAiBaseUrl(null) shouldBe "https://api.openai.com/v1"
            }
        }

        `when`("base-url 을 명시적으로 지정했으면") {
            then("지정한 값을 그대로 사용한다") {
                LlmConfiguration.resolveOpenAiBaseUrl("https://proxy.internal/v1") shouldBe "https://proxy.internal/v1"
            }
        }
    }
})
