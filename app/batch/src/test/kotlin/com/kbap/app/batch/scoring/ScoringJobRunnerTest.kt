package com.kbap.app.batch.scoring

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.kbap.domain.avoidance.model.AvoidanceSubstance
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import com.kbap.domain.research.input.ScoringFood
import com.kbap.domain.research.ensemble.ConsensusEnsembleAggregator
import com.kbap.domain.research.prompt.ScoringPromptFactory
import com.kbap.domain.research.parse.ScoringResponseParser
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.client.LlmModelCaller
import com.kbap.infra.llm.model.LlmChatRequest
import com.kbap.infra.llm.model.LlmModelId
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.boot.DefaultApplicationArguments
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class ScoringJobRunnerTest : BehaviorSpec({

    val executor = Executors.newVirtualThreadPerTaskExecutor()

    given("페이크 협력자로 조립한 실제 AvoidanceScoringJob 을 감싼 ScoringJobRunner") {
        val source = RecordingFoodScoringSource(listOf(runnerFood(id = 100L, koreanName = "비빔밥")))
        val callers = listOf(
            RunnerJsonCaller(LlmModelId.OPENAI, runnerScoredJson("비빔밥")),
            RunnerJsonCaller(LlmModelId.UPSTAGE, runnerScoredJson("비빔밥")),
            RunnerJsonCaller(LlmModelId.GEMINI, runnerScoredJson("비빔밥")),
        )
        val job = AvoidanceScoringJob(
            nextChunk = source::nextChunk,
            llmFanoutClient = LlmFanoutClient(callers, executor),
            findSubstances = runnerSubstancesOf(runnerEgg(), runnerMilk(), runnerWheat()),
            promptFactory = ScoringPromptFactory(),
            responseParser = ScoringResponseParser(),
            aggregator = ConsensusEnsembleAggregator(),
            chunkSize = 10,
        )
        val runner = ScoringJobRunner(job)

        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val runnerLogger = LoggerFactory.getLogger(ScoringJobRunner::class.java) as Logger
        runnerLogger.addAppender(appender)

        `when`("애플리케이션 인자로 run 을 호출하면") {
            then("예외 없이 완료되고 감싼 잡이 실행돼 대기열이 조회된다") {
                shouldNotThrowAny { runner.run(DefaultApplicationArguments()) }
                source.invocationCount.get() shouldBeGreaterThan 0
            }

            then("잡 실행을 마친 뒤 total·scored·failed 요약이 로깅된다") {
                shouldNotThrowAny { runner.run(DefaultApplicationArguments()) }
                appender.list.map { it.formattedMessage }.any { message ->
                    message.contains("total=") && message.contains("scored=") && message.contains("failed=")
                } shouldBe true
            }
        }
    }
})

private fun runnerScoredJson(koreanName: String): String =
    """{"results":[{"food":"$koreanName","included":[{"code":"EGG","score":2,"probability":90}],"nameTranslations":{"en":"$koreanName-en"},"description":{"ko":"$koreanName 설명","translations":{"en":"$koreanName-desc-en"}}}]}"""

private fun runnerFood(id: Long, koreanName: String): ScoringFood =
    ScoringFood(foodId = id, koreanName = koreanName)

private fun runnerEgg(): AvoidanceSubstance =
    AvoidanceSubstance(code = AvoidanceSubstanceCode.EGG, koreanName = "계란")

private fun runnerMilk(): AvoidanceSubstance =
    AvoidanceSubstance(code = AvoidanceSubstanceCode.MILK, koreanName = "우유")

private fun runnerWheat(): AvoidanceSubstance =
    AvoidanceSubstance(code = AvoidanceSubstanceCode.WHEAT, koreanName = "밀")

private fun runnerSubstancesOf(vararg substances: AvoidanceSubstance): (Set<AvoidanceSubstanceCode>) -> List<AvoidanceSubstance> =
    RunnerSubstanceCatalog(substances.toList())::findByCodes

private class RecordingFoodScoringSource(private val foods: List<ScoringFood>) {
    val invocationCount = AtomicInteger()

    fun nextChunk(page: Int, size: Int): List<ScoringFood> {
        invocationCount.incrementAndGet()
        return foods.drop(page * size).take(size)
    }
}

private class RunnerSubstanceCatalog(
    private val substances: List<AvoidanceSubstance>,
) {
    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> = substances
}

private class RunnerJsonCaller(
    override val modelId: LlmModelId,
    private val json: String,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String = json
}
