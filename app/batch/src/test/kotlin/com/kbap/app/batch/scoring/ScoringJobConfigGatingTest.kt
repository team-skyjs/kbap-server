package com.kbap.app.batch.scoring

import com.kbap.infra.llm.client.LlmFanoutClient
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.concurrent.Executors
import java.util.function.Supplier

class ScoringJobConfigGatingTest : BehaviorSpec({

    val contextRunner = ApplicationContextRunner()
        .withBean(AvoidanceScoringJob::class.java, Supplier { stubJob() })
        .withUserConfiguration(ScoringRunnerConfig::class.java)

    given("스텁 AvoidanceScoringJob 빈과 ScoringRunnerConfig 로 구성한 배치 컨텍스트") {
        `when`("kbap.scoring.runner.enabled 프로퍼티를 설정하지 않고 컨텍스트를 올리면") {
            then("ScoringJobRunner 빈이 등록되지 않는다(부팅 시 잡 미자동실행)") {
                contextRunner.run { context ->
                    context.getBeanNamesForType(ScoringJobRunner::class.java).size shouldBe 0
                }
            }
        }

        `when`("kbap.scoring.runner.enabled=true 로 컨텍스트를 올리면") {
            then("ScoringJobRunner 빈이 등록된다(부팅 시 잡 자동실행)") {
                contextRunner
                    .withPropertyValues("kbap.scoring.runner.enabled=true")
                    .run { context ->
                        context.getBeanNamesForType(ScoringJobRunner::class.java).size shouldBe 1
                    }
            }
        }

        `when`("kbap.scoring.runner.enabled=false 로 컨텍스트를 올리면") {
            then("ScoringJobRunner 빈이 등록되지 않는다") {
                contextRunner
                    .withPropertyValues("kbap.scoring.runner.enabled=false")
                    .run { context ->
                        context.getBeanNamesForType(ScoringJobRunner::class.java).size shouldBe 0
                    }
            }
        }
    }
})

private fun stubJob(): AvoidanceScoringJob =
    AvoidanceScoringJob(
        nextChunk = { _, _ -> emptyList() },
        llmFanoutClient = LlmFanoutClient(emptyList(), Executors.newVirtualThreadPerTaskExecutor()),
        findSubstances = { emptyList() },
        promptFactory = com.kbap.domain.research.prompt.ScoringPromptFactory(),
        responseParser = com.kbap.domain.research.parse.ScoringResponseParser(),
        aggregator = com.kbap.domain.research.ensemble.ConsensusEnsembleAggregator(),
        chunkSize = 10,
    )
