package com.kbap.infra.llm.config

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

    given("kbap.llm.* 프로퍼티를 하나도 설정하지 않은 환경") {
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
            "kbap.llm.openai.enabled=true",
            "kbap.llm.openai.api-key=x",
            "kbap.llm.openai.model=gpt-4o-mini",
            "kbap.llm.upstage.base-url=https://api.upstage.ai/v1",
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
            "kbap.llm.openai.max-output-tokens=2048",
            "kbap.llm.openai.reasoning-effort=minimal",
            "kbap.llm.upstage.max-output-tokens=2048",
            "kbap.llm.gemini.max-output-tokens=4096",
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

    given("기피성분 전용 오버라이드(kbap.llm.avoidance.*)를 지정한 환경") {
        val avoidanceRunner = runner.withPropertyValues(
            "kbap.llm.avoidance.min-agreement=1",
            "kbap.llm.avoidance.model=gpt-5-mini",
            "kbap.llm.avoidance.pricing.input-usd-per-million-tokens=0.25",
            "kbap.llm.avoidance.pricing.output-usd-per-million-tokens=2.00",
        )

        `when`("LlmModelProperties 로 바인딩하면") {
            then("minAgreement/model/pricing 이 지정값으로 바인딩된다") {
                avoidanceRunner.run { context ->
                    val avoidance = context.getBean(LlmModelProperties::class.java).avoidance
                    avoidance.minAgreement shouldBe 1
                    avoidance.model shouldBe "gpt-5-mini"
                    avoidance.pricing?.inputUsdPerMillionTokens shouldBe 0.25
                    avoidance.pricing?.outputUsdPerMillionTokens shouldBe 2.00
                }
            }
        }
    }

    given("기피성분 전용 오버라이드를 지정하지 않은 환경") {
        `when`("LlmModelProperties 로 바인딩하면") {
            then("minAgreement 기본값은 2, 오버라이드 필드는 전부 null 이다(openai 값 상속 신호)") {
                runner.run { context ->
                    val avoidance = context.getBean(LlmModelProperties::class.java).avoidance
                    avoidance.minAgreement shouldBe 2
                    avoidance.model.shouldBeNull()
                    avoidance.maxOutputTokens.shouldBeNull()
                    avoidance.reasoningEffort.shouldBeNull()
                    avoidance.pricing.shouldBeNull()
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
