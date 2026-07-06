package com.meogo.app.batch.scoring

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.Food
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodScoringSource
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.research.ensemble.ConsensusEnsembleAggregator
import com.meogo.core.research.ensemble.FoodScoringStatus
import com.meogo.core.research.prompt.ScoringPromptFactory
import com.meogo.core.research.parse.ScoringResponseParser
import com.meogo.infra.llm.client.LlmFanoutClient
import com.meogo.infra.llm.client.LlmModelCaller
import com.meogo.infra.llm.model.LlmChatRequest
import com.meogo.infra.llm.model.LlmModelId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AvoidanceScoringJobTest : BehaviorSpec({

    val executor = Executors.newVirtualThreadPerTaskExecutor()

    given("음식 23개가 대기 중이고 chunkSize 가 10 인 스코어링 잡") {
        val foods = (1..23).map { food(id = it.toLong(), koreanName = "음식$it") }
        val source = FakeFoodScoringSource(foods)
        val counter = AtomicInteger()
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, EMPTY_RESULTS_JSON, counter),
            CountingJsonCaller(LlmModelId.UPSTAGE, EMPTY_RESULTS_JSON, AtomicInteger()),
            CountingJsonCaller(LlmModelId.GEMINI, EMPTY_RESULTS_JSON, AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), milk(), wheat()), chunkSize = 10)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("대기열을 10/10/3 세 청크로 소진하며 LLM generate 를 청크 수(3)만큼 호출한다") {
                counter.get() shouldBe 3
            }

            then("23개 음식 전부가 SCORED 로 산출된다(첫 청크만 처리하면 실패)") {
                results shouldHaveSize 23
                results.map { it.foodId }.toSet() shouldBe (1L..23L).toSet()
                results.all { it.status == FoodScoringStatus.SCORED } shouldBe true
            }
        }
    }

    given("대기열이 비어 있고 chunkSize 가 10 인 스코어링 잡") {
        val source = FakeFoodScoringSource(emptyList())
        val counter = AtomicInteger()
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, EMPTY_RESULTS_JSON, counter),
            CountingJsonCaller(LlmModelId.UPSTAGE, EMPTY_RESULTS_JSON, AtomicInteger()),
            CountingJsonCaller(LlmModelId.GEMINI, EMPTY_RESULTS_JSON, AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), milk(), wheat()), chunkSize = 10)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("LLM generate 를 한 번도 호출하지 않는다") {
                counter.get() shouldBe 0
            }

            then("산출 결과가 비어 있다") {
                results.shouldBeEmpty()
            }
        }
    }

    given("음식 1개(비빔밥)에 대해 3개 모델이 모두 성공 응답하는 스코어링 잡") {
        val source = FakeFoodScoringSource(listOf(food(id = 100L, koreanName = "비빔밥")))
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, scoredJson("비빔밥"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.UPSTAGE, scoredJson("비빔밥"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.GEMINI, scoredJson("비빔밥"), AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), milk(), wheat()), chunkSize = 10)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("그 음식은 SCORED 로 확정된다") {
                results shouldHaveSize 1
                results.single().status shouldBe FoodScoringStatus.SCORED
            }

            then("scores 가 카탈로그 후보 전부(3종)를 커버한다") {
                results.single().scores shouldHaveSize 3
            }

            then("음식명 번역과 음식 설명이 채택된다") {
                results.single().nameTranslations.isNotEmpty() shouldBe true
                results.single().description.shouldNotBeNull()
            }
        }
    }

    given("음식 2개에 대해 2개 모델은 성공하고 UPSTAGE 가 실패하는 스코어링 잡") {
        val chunk = listOf(food(id = 201L, koreanName = "김밥"), food(id = 202L, koreanName = "된장국"))
        val source = FakeFoodScoringSource(chunk)
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, scoredJson("김밥", "된장국"), AtomicInteger()),
            FailingCaller(LlmModelId.UPSTAGE, "upstage down"),
            CountingJsonCaller(LlmModelId.GEMINI, scoredJson("김밥", "된장국"), AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), milk(), wheat()), chunkSize = 10)

        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val jobLogger = LoggerFactory.getLogger(AvoidanceScoringJob::class.java) as Logger
        jobLogger.addAppender(appender)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("청크의 모든 음식이 FAILED(미확정)로 처리된다") {
                results shouldHaveSize 2
                results.map { it.status }.toSet() shouldBe setOf(FoodScoringStatus.FAILED)
            }

            then("부분 집계가 유입되지 않아 scores 가 모두 비어 있다") {
                results.forEach { it.scores.shouldBeEmpty() }
            }

            then("SCORED 결과가 하나도 산출되지 않는다") {
                results.none { it.status == FoodScoringStatus.SCORED } shouldBe true
            }

            then("실패한 모델(UPSTAGE) 정보가 로그에 남는다") {
                appender.list.map { it.formattedMessage }.any { it.contains("UPSTAGE") } shouldBe true
            }
        }
    }

    given("음식 1개에 대해 3개 모델이 전부 실패하는 스코어링 잡") {
        val source = FakeFoodScoringSource(listOf(food(id = 301L, koreanName = "불고기")))
        val callers = listOf(
            FailingCaller(LlmModelId.OPENAI, "openai down"),
            FailingCaller(LlmModelId.UPSTAGE, "upstage down"),
            FailingCaller(LlmModelId.GEMINI, "gemini down"),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), milk(), wheat()), chunkSize = 10)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("그 음식은 FAILED(미확정)로 처리되고 SCORED 는 0건이다") {
                results shouldHaveSize 1
                results.single().status shouldBe FoodScoringStatus.FAILED
                results.none { it.status == FoodScoringStatus.SCORED } shouldBe true
            }
        }
    }

    given("커서를 전진하지 않아 매 호출 같은 음식 3개를 반환하는 비전진 소스의 스코어링 잡") {
        val chunk = listOf(
            food(id = 501L, koreanName = "떡볶이"),
            food(id = 502L, koreanName = "순대"),
            food(id = 503L, koreanName = "튀김"),
        )
        val source = NonAdvancingFoodScoringSource(chunk)
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, scoredJson("떡볶이", "순대", "튀김"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.UPSTAGE, scoredJson("떡볶이", "순대", "튀김"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.GEMINI, scoredJson("떡볶이", "순대", "튀김"), AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), milk(), wheat()), chunkSize = 10)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("seen 가드로 무한루프 없이 종료하고 각 distinct foodId 당 1건만 산출한다") {
                results.map { it.foodId }.toSet() shouldBe setOf(501L, 502L, 503L)
                results shouldHaveSize 3
            }
        }
    }

    given("카탈로그에 후보 성분이 2종만 있는 상태에서 3개 모델이 성공하는 스코어링 잡") {
        val source = FakeFoodScoringSource(listOf(food(id = 400L, koreanName = "잡채")))
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, scoredJson("잡채"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.UPSTAGE, scoredJson("잡채"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.GEMINI, scoredJson("잡채"), AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), wheat()), chunkSize = 10)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("scores 개수가 카탈로그 후보 수(2)를 그대로 추종한다(하드코딩 아님)") {
                results.single().scores shouldHaveSize 2
                results.single().scores.size shouldBeGreaterThan 0
            }
        }
    }

    given("음식 1개에 대해 3개 모델 모두 generate 는 성공하지만 GEMINI 만 파싱 불가한 응답을 반환하는 스코어링 잡") {
        val source = FakeFoodScoringSource(listOf(food(id = 600L, koreanName = "비빔국수")))
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, scoredJson("비빔국수"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.UPSTAGE, scoredJson("비빔국수"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.GEMINI, "이건 JSON 이 아니다", AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), milk(), wheat()), chunkSize = 10)

        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val jobLogger = LoggerFactory.getLogger(AvoidanceScoringJob::class.java) as Logger
        jobLogger.addAppender(appender)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("파싱 성공 모델이 3개 미만이라 그 음식은 FAILED(미확정)로 처리된다") {
                results shouldHaveSize 1
                results.single().status shouldBe FoodScoringStatus.FAILED
            }

            then("부분 집계가 유입되지 않아 scores 가 비어 있고 SCORED 는 0건이다") {
                results.single().scores.shouldBeEmpty()
                results.none { it.status == FoodScoringStatus.SCORED } shouldBe true
            }

            then("파싱 불가 응답을 준 모델(GEMINI) 정보가 로그에 남는다") {
                appender.list.map { it.formattedMessage }.any { it.contains("GEMINI") } shouldBe true
            }
        }
    }

    given("회피성분 카탈로그가 비어 있는 스코어링 잡") {
        val source = FakeFoodScoringSource(listOf(food(id = 800L, koreanName = "라면")))
        val counter = AtomicInteger()
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, EMPTY_RESULTS_JSON, counter),
            CountingJsonCaller(LlmModelId.UPSTAGE, EMPTY_RESULTS_JSON, AtomicInteger()),
            CountingJsonCaller(LlmModelId.GEMINI, EMPTY_RESULTS_JSON, AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(), chunkSize = 10)

        `when`("잡을 실행하면") {
            then("IllegalStateException 을 던져 즉시 중단한다") {
                shouldThrow<IllegalStateException> {
                    job.run()
                }
            }

            then("LLM 을 한 번도 호출하지 않는다") {
                counter.get() shouldBe 0
            }
        }
    }

    given("음식 1개에 대해 3개 모델이 서로 다른 음식명 번역·설명을 반환하는 스코어링 잡") {
        val source = FakeFoodScoringSource(listOf(food(id = 700L, koreanName = "칼국수")))
        val callers = listOf(
            CountingJsonCaller(LlmModelId.OPENAI, scoredJsonWithText("칼국수", "A-en", "A설명"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.UPSTAGE, scoredJsonWithText("칼국수", "B-en", "B설명"), AtomicInteger()),
            CountingJsonCaller(LlmModelId.GEMINI, scoredJsonWithText("칼국수", "C-en", "C설명"), AtomicInteger()),
        )
        val job = job(source, LlmFanoutClient(callers, executor), repositoryOf(egg(), milk(), wheat()), chunkSize = 10)

        `when`("잡을 실행하면") {
            val results = job.run()

            then("우선순위 첫 모델(OPENAI)의 음식명 번역이 채택된다") {
                results.single().nameTranslations[LanguageCode.EN] shouldBe "A-en"
            }

            then("우선순위 첫 모델(OPENAI)의 음식 설명이 채택된다") {
                results.single().description.shouldNotBeNull().korean shouldBe "A설명"
            }
        }
    }
})

private const val EMPTY_RESULTS_JSON = """{"results":[]}"""

private fun scoredJson(vararg koreanNames: String): String {
    val results = koreanNames.joinToString(",") { name ->
        """{"food":"$name","included":[{"code":"EGG","score":2,"probability":90}],"nameTranslations":{"en":"$name-en"},"description":{"ko":"$name 설명","translations":{"en":"$name-desc-en"}}}"""
    }
    return """{"results":[$results]}"""
}

private fun scoredJsonWithText(koreanName: String, nameEn: String, descriptionKo: String): String =
    """{"results":[{"food":"$koreanName","included":[{"code":"EGG","score":2,"probability":90}],"nameTranslations":{"en":"$nameEn"},"description":{"ko":"$descriptionKo","translations":{"en":"$nameEn-desc"}}}]}"""

private fun food(id: Long, koreanName: String): Food =
    Food.reconstitute(
        id = id,
        content = FoodContent(
            name = LocalizedText(korean = koreanName),
            description = LocalizedText(korean = "$koreanName 기본 설명"),
        ),
        imageRef = null,
        spiciness = FoodSpiciness(0),
        avoidanceSubstances = emptyList(),
    )

private fun egg(): AvoidanceSubstance =
    AvoidanceSubstance.reconstitute(id = 1L, code = AvoidanceSubstanceCode.EGG, name = LocalizedText(korean = "계란"))

private fun milk(): AvoidanceSubstance =
    AvoidanceSubstance.reconstitute(id = 2L, code = AvoidanceSubstanceCode.MILK, name = LocalizedText(korean = "우유"))

private fun wheat(): AvoidanceSubstance =
    AvoidanceSubstance.reconstitute(id = 3L, code = AvoidanceSubstanceCode.WHEAT, name = LocalizedText(korean = "밀"))

private fun repositoryOf(vararg substances: AvoidanceSubstance): AvoidanceSubstanceRepository =
    FakeAvoidanceSubstanceRepository(substances.toList())

private fun job(
    source: FoodScoringSource,
    client: LlmFanoutClient,
    repository: AvoidanceSubstanceRepository,
    chunkSize: Int,
): AvoidanceScoringJob =
    AvoidanceScoringJob(
        foodScoringSource = source,
        llmFanoutClient = client,
        avoidanceSubstanceRepository = repository,
        promptFactory = ScoringPromptFactory(),
        responseParser = ScoringResponseParser(),
        aggregator = ConsensusEnsembleAggregator(),
        chunkSize = chunkSize,
    )

private class FakeFoodScoringSource(private val foods: List<Food>) : FoodScoringSource {
    override fun nextChunk(page: Int, size: Int): List<Food> = foods.drop(page * size).take(size)
}

private class NonAdvancingFoodScoringSource(private val foods: List<Food>) : FoodScoringSource {
    override fun nextChunk(page: Int, size: Int): List<Food> = foods.take(size)
}

private class FakeAvoidanceSubstanceRepository(
    private val substances: List<AvoidanceSubstance>,
) : AvoidanceSubstanceRepository {
    override fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> = substances
}

private class CountingJsonCaller(
    override val modelId: LlmModelId,
    private val json: String,
    private val counter: AtomicInteger,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String {
        counter.incrementAndGet()
        return json
    }
}

private class FailingCaller(
    override val modelId: LlmModelId,
    private val failureMessage: String,
) : LlmModelCaller {
    override fun call(request: LlmChatRequest): String = throw RuntimeException(failureMessage)
}
