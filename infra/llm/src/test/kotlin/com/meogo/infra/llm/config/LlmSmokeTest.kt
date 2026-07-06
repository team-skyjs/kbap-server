package com.meogo.infra.llm.config

import com.meogo.infra.llm.client.LlmFanoutClient
import com.meogo.infra.llm.model.LlmChatRequest
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.reflect.KClass

class RealKeySmokeEnabledCondition : EnabledCondition {
    override fun enabled(kclass: KClass<out Spec>): Boolean =
        System.getProperty(SMOKE_ENABLED_PROPERTY) == "true"

    companion object {
        const val SMOKE_ENABLED_PROPERTY = "llm.smoke.enabled"
    }
}

@EnabledIf(RealKeySmokeEnabledCondition::class)
class LlmSmokeTest : BehaviorSpec({

    val runner = ApplicationContextRunner()
        .withConfiguration(UserConfigurations.of(LlmConfiguration::class.java))
        .withPropertyValues(
            "meogo.llm.openai.enabled=true",
            "meogo.llm.openai.api-key=${System.getenv("OPENAI_API_KEY") ?: ""}",
            "meogo.llm.openai.model=gpt-4o-mini",
            "meogo.llm.upstage.enabled=true",
            "meogo.llm.upstage.api-key=${System.getenv("UPSTAGE_API_KEY") ?: ""}",
            "meogo.llm.upstage.model=solar-pro",
            "meogo.llm.gemini.enabled=true",
            "meogo.llm.gemini.api-key=${System.getenv("GEMINI_API_KEY") ?: ""}",
            "meogo.llm.gemini.model=gemini-2.0-flash",
        )

    given("실 API 키로 OpenAI·Upstage·Gemini 세 모델을 모두 활성화한 구성(수동 실행: quickstart §3, -Dllm.smoke.enabled=true)") {
        `when`("단일 프롬프트로 LlmFanoutClient.generate 를 실호출하면") {
            then("세 모델이 모두 성공해 successes 가 3개이고 failures 는 비어 있다") {
                runner.run { context ->
                    val client = context.getBean(LlmFanoutClient::class.java)

                    val result = client.generate(LlmChatRequest(prompt = "ping"))

                    result.successes shouldHaveSize 3
                    result.failures.shouldBeEmpty()
                }
            }
        }
    }
})
