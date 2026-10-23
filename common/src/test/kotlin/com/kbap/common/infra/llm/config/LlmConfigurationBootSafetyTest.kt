package com.kbap.common.infra.llm.config

import com.kbap.common.port.llm.MenuBoardVisionExtractor
import com.kbap.common.port.llm.ProcessedFoodTextTranslator
import com.kbap.common.port.llm.TextEmbeddingClient
import com.kbap.common.port.llm.TextTranslator
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class LlmConfigurationBootSafetyTest : BehaviorSpec({

    val runner = ApplicationContextRunner()
        .withConfiguration(UserConfigurations.of(LlmConfiguration::class.java))

    given("API 키·활성 플래그가 하나도 없는 기본 환경") {
        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("컨텍스트 로딩이 실패 없이 완료된다") {
                runner.run { context ->
                    context.startupFailure.shouldBeNull()
                }
            }

            then("vision 빈이 없다") {
                runner.run { context ->
                    context.getBeanNamesForType(MenuBoardVisionExtractor::class.java).size shouldBe 0
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
        }
    }

    given("vision 을 enabled=true 로 활성화하고 더미 키·모델을 지정한 환경") {
        val visionRunner = runner.withPropertyValues(
            "kbap.llm.vision.enabled=true",
            "kbap.llm.vision.api-key=dummy-key",
            "kbap.llm.vision.model=gpt-5.6-luna",
        )

        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("컨텍스트 로딩이 실패 없이 완료된다") {
                visionRunner.run { context ->
                    context.startupFailure.shouldBeNull()
                }
            }

            then("vision 빈이 등록된다") {
                visionRunner.run { context ->
                    context.getBean(MenuBoardVisionExtractor::class.java).shouldNotBeNull()
                }
            }
        }
    }

    given("vision 을 켰지만 api-key 가 비어 있는 환경") {
        val missingKeyRunner = runner.withPropertyValues(
            "kbap.llm.vision.enabled=true",
        )

        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("어떤 프로퍼티가 비었는지 알려주며 부팅에 실패한다") {
                missingKeyRunner.run { context ->
                    context.startupFailure.shouldNotBeNull()
                }
            }
        }
    }

    given("공용 번역과 가공식품 전용 번역을 함께 켠 환경") {
        val translationRunner = runner.withPropertyValues(
            "kbap.llm.translation.enabled=true",
            "kbap.llm.translation.api-key=dummy-key",
            "kbap.llm.translation.model=gpt-5.6-luna",
            "kbap.llm.processed-food-translation.enabled=true",
            "kbap.llm.processed-food-translation.api-key=dummy-key",
            "kbap.llm.processed-food-translation.model=gpt-5-nano",
        )

        `when`("LlmConfiguration 으로 컨텍스트를 로딩하면") {
            then("공용 번역기는 한 개이고 가공식품 전용 번역기는 별도 계약으로 등록된다") {
                translationRunner.run { context ->
                    context.startupFailure.shouldBeNull()
                    context.getBeanNamesForType(TextTranslator::class.java).size shouldBe 1
                    context.getBeanNamesForType(ProcessedFoodTextTranslator::class.java).size shouldBe 1
                }
            }
        }
    }
})
