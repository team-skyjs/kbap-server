package com.kbap.infra.llm.config

import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.beans.factory.getBean
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
            "kbap.llm.openai.enabled=true",
            "kbap.llm.openai.api-key=${System.getenv("OPENAI_API_KEY") ?: ""}",
            "kbap.llm.openai.model=gpt-5.6-luna",
        )

    given("실 API 키로 luna 를 활성화한 구성(수동 실행: quickstart §3, -Dllm.smoke.enabled=true)") {
        `when`("단일 프롬프트로 caller 를 실호출하면") {
            then("비어 있지 않은 응답이 돌아온다") {
                runner.run { context ->
                    val caller = context.getBean<LlmModelCaller>("openAiModelCaller")

                    val content = caller.call(LlmChatRequest(prompt = "ping"))

                    content.shouldNotBeBlank()
                }
            }
        }
    }
})
