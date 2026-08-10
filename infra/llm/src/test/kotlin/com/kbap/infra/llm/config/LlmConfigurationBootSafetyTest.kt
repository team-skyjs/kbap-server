package com.kbap.infra.llm.config

import com.kbap.common.port.llm.FoodAvoidanceAssessmentClient
import com.kbap.common.port.llm.TextEmbeddingClient
import com.kbap.infra.llm.client.LlmModelCaller
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LlmConfigurationBootSafetyTest : BehaviorSpec({

    val runner = ApplicationContextRunner()
        .withConfiguration(
            UserConfigurations.of(LlmConfiguration::class.java, FoodContentClientConfiguration::class.java),
        )

    given("API 키·활성 플래그가 하나도 없는 기본 환경") {
        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("컨텍스트 로딩이 실패 없이 완료된다") {
                runner.run { context ->
                    context.startupFailure.shouldBeNull()
                }
            }

            then("LlmModelCaller 타입 빈이 0개다") {
                runner.run { context ->
                    context.getBeanNamesForType(LlmModelCaller::class.java).size shouldBe 0
                }
            }

            then("기피성분 조사 클라이언트 빈도 없다(caller 없이 조립하지 않는다)") {
                runner.run { context ->
                    context.getBeanNamesForType(FoodAvoidanceAssessmentClient::class.java).size shouldBe 0
                }
            }

            then("TextEmbeddingClient 빈이 없다(임베딩 미설정 — 부팅 무영향)") {
                runner.run { context ->
                    context.getBeanNamesForType(TextEmbeddingClient::class.java).size shouldBe 0
                }
            }
        }
    }

    given("embedding 만 enabled=true 로 활성화한 환경") {
        val embeddingRunner = runner.withPropertyValues(
            "kbap.llm.embedding.enabled=true",
        )

        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("컨텍스트 로딩이 실패 없이 완료된다") {
                embeddingRunner.run { context ->
                    context.startupFailure.shouldBeNull()
                }
            }

            then("TextEmbeddingClient 빈이 존재한다") {
                embeddingRunner.run { context ->
                    context.getBean(TextEmbeddingClient::class.java).shouldNotBeNull()
                }
            }

            then("LlmModelCaller 타입 빈은 여전히 0개다(채팅 모델 미활성)") {
                embeddingRunner.run { context ->
                    context.getBeanNamesForType(LlmModelCaller::class.java).size shouldBe 0
                }
            }
        }
    }

    given("openai 를 enabled=true 로 활성화하고 더미 키·모델을 지정한 환경") {
        val activeRunner = runner.withPropertyValues(
            "kbap.llm.openai.enabled=true",
            "kbap.llm.openai.api-key=dummy-key",
            "kbap.llm.openai.model=gpt-5.6-luna",
        )

        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("컨텍스트 로딩이 실패 없이 완료된다") {
                activeRunner.run { context ->
                    context.startupFailure.shouldBeNull()
                }
            }

            then("공용 caller 와 기피성분 전용 caller 가 각각 등록된다") {
                activeRunner.run { context ->
                    context.getBeanNamesForType(LlmModelCaller::class.java).toSet() shouldBe
                        setOf("openAiModelCaller", "avoidanceOpenAiModelCaller")
                }
            }

            then("기피성분 조사 클라이언트가 기피성분 전용 caller 로 조립된다") {
                activeRunner.run { context ->
                    context.getBean(FoodAvoidanceAssessmentClient::class.java).shouldNotBeNull()
                }
            }
        }
    }

    given("openai 를 켰지만 api-key 가 비어 있는 환경") {
        val missingKeyRunner = runner.withPropertyValues(
            "kbap.llm.openai.enabled=true",
        )

        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("어떤 프로퍼티가 비었는지 알려주며 부팅에 실패한다") {
                missingKeyRunner.run { context ->
                    context.startupFailure.shouldNotBeNull()
                }
            }
        }
    }
})
