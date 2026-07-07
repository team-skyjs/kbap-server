package com.meogo.infra.llm.config

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

class LlmModelPropertiesBindingTest : BehaviorSpec({

    val runner = ApplicationContextRunner()
        .withConfiguration(UserConfigurations.of(PropertiesBindingTestConfiguration::class.java))

    given("meogo.llm.* 프로퍼티를 하나도 설정하지 않은 환경") {
        `when`("LlmModelProperties 로 바인딩하면") {
            then("openai/upstage/gemini 세 모델의 enabled 기본값이 모두 false 다") {
                runner.run { context ->
                    val props = context.getBean(LlmModelProperties::class.java)
                    props.openai.enabled shouldBe false
                    props.upstage.enabled shouldBe false
                    props.gemini.enabled shouldBe false
                }
            }

            then("세 모델의 apiKey/baseUrl/model 기본값이 모두 null 이다") {
                runner.run { context ->
                    val props = context.getBean(LlmModelProperties::class.java)
                    listOf(props.openai, props.upstage, props.gemini).forEach { model ->
                        model.apiKey.shouldBeNull()
                        model.baseUrl.shouldBeNull()
                        model.model.shouldBeNull()
                    }
                }
            }
        }
    }

    given("openai 활성/키/모델과 upstage base-url 을 지정한 환경") {
        val boundRunner = runner.withPropertyValues(
            "meogo.llm.openai.enabled=true",
            "meogo.llm.openai.api-key=x",
            "meogo.llm.openai.model=gpt-4o-mini",
            "meogo.llm.upstage.base-url=https://api.upstage.ai/v1",
        )

        `when`("LlmModelProperties 로 바인딩하면") {
            then("openai 의 enabled/apiKey/model 이 지정값으로 relaxed 바인딩된다") {
                boundRunner.run { context ->
                    val props = context.getBean(LlmModelProperties::class.java)
                    props.openai.enabled shouldBe true
                    props.openai.apiKey shouldBe "x"
                    props.openai.model shouldBe "gpt-4o-mini"
                }
            }

            then("upstage 의 base-url 이 baseUrl 로 relaxed 바인딩된다") {
                boundRunner.run { context ->
                    val props = context.getBean(LlmModelProperties::class.java)
                    props.upstage.baseUrl shouldBe "https://api.upstage.ai/v1"
                }
            }

            then("지정하지 않은 필드는 기본값을 유지한다") {
                boundRunner.run { context ->
                    val props = context.getBean(LlmModelProperties::class.java)
                    props.openai.baseUrl.shouldBeNull()
                    props.upstage.enabled shouldBe false
                    props.gemini.enabled shouldBe false
                    props.gemini.apiKey.shouldBeNull()
                }
            }
        }
    }

    given("세 모델의 출력 상한과 openai 추론 노력을 지정한 환경") {
        val tunedRunner = runner.withPropertyValues(
            "meogo.llm.openai.max-output-tokens=2048",
            "meogo.llm.openai.reasoning-effort=minimal",
            "meogo.llm.upstage.max-output-tokens=2048",
            "meogo.llm.gemini.max-output-tokens=4096",
        )

        `when`("LlmModelProperties 로 바인딩하면") {
            then("각 모델 max-output-tokens 가 maxOutputTokens 로 relaxed 바인딩된다") {
                tunedRunner.run { context ->
                    val props = context.getBean(LlmModelProperties::class.java)
                    props.openai.maxOutputTokens shouldBe 2048
                    props.upstage.maxOutputTokens shouldBe 2048
                    props.gemini.maxOutputTokens shouldBe 4096
                }
            }

            then("openai 의 reasoning-effort 가 reasoningEffort 로 바인딩된다") {
                tunedRunner.run { context ->
                    context.getBean(LlmModelProperties::class.java).openai.reasoningEffort shouldBe "minimal"
                }
            }
        }
    }

    given("튜닝 프로퍼티를 하나도 지정하지 않은 환경") {
        `when`("LlmModelProperties 로 바인딩하면") {
            then("세 모델의 maxOutputTokens 기본값이 모두 null 이다") {
                runner.run { context ->
                    val props = context.getBean(LlmModelProperties::class.java)
                    listOf(props.openai, props.upstage, props.gemini).forEach { model ->
                        model.maxOutputTokens.shouldBeNull()
                    }
                }
            }

            then("openai 의 reasoningEffort 기본값이 null 이다") {
                runner.run { context ->
                    context.getBean(LlmModelProperties::class.java).openai.reasoningEffort.shouldBeNull()
                }
            }
        }
    }
})

@Configuration
@EnableConfigurationProperties(LlmModelProperties::class)
private class PropertiesBindingTestConfiguration
