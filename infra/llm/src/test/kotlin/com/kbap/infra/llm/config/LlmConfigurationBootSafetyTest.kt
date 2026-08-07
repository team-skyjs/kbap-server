package com.kbap.infra.llm.config

import com.kbap.common.port.llm.TextEmbeddingClient
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmModelId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.Executor

class LlmConfigurationBootSafetyTest : BehaviorSpec({

    val runner = ApplicationContextRunner()
        .withConfiguration(UserConfigurations.of(LlmConfiguration::class.java))

    given("API 키·활성 플래그가 하나도 없는 기본 환경(세 모델 enabled=false)") {
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

            then("LlmFanoutClient 빈은 존재한다(빈 caller 리스트 주입)") {
                runner.run { context ->
                    context.getBean(LlmFanoutClient::class.java).shouldNotBeNull()
                }
            }

            then("fan-out 실행용 Executor 빈이 존재한다") {
                runner.run { context ->
                    context.getBean(Executor::class.java).shouldNotBeNull()
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

    given("openai 만 enabled=true 로 활성화하고 더미 키·모델을 지정한 환경") {
        val activeRunner = runner.withPropertyValues(
            "kbap.llm.openai.enabled=true",
            "kbap.llm.openai.api-key=dummy-key",
            "kbap.llm.openai.model=gpt-4o-mini",
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

            then("두 caller 모두 modelId 가 OPENAI 다") {
                activeRunner.run { context ->
                    context.getBeansOfType(LlmModelCaller::class.java).values.forEach {
                        it.modelId shouldBe LlmModelId.OPENAI
                    }
                }
            }
        }
    }

    given("OpenAI 호환 upstage 를 enabled=true 로 켜고 더미 api-key 를 지정한 환경") {
        val keyPresentRunner = runner.withPropertyValues(
            "kbap.llm.upstage.enabled=true",
            "kbap.llm.upstage.api-key=dummy-key",
        )

        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("컨텍스트 로딩이 실패 없이 완료된다") {
                keyPresentRunner.run { context ->
                    context.startupFailure.shouldBeNull()
                }
            }

            then("등록된 caller 의 modelId 가 UPSTAGE 다") {
                keyPresentRunner.run { context ->
                    context.getBean(LlmModelCaller::class.java).modelId shouldBe LlmModelId.UPSTAGE
                }
            }
        }
    }
})
