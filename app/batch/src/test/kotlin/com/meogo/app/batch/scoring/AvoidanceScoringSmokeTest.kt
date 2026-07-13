package com.meogo.app.batch.scoring

import com.meogo.domain.avoidance.AvoidanceSubstance
import com.meogo.domain.avoidance.AvoidanceSubstanceCode
import com.meogo.domain.food.Food
import com.meogo.domain.food.FoodContent
import com.meogo.domain.food.FoodSpiciness
import com.meogo.core.lang.LocalizedText
import com.meogo.domain.research.ensemble.ConsensusEnsembleAggregator
import com.meogo.domain.research.ensemble.FoodScoringStatus
import com.meogo.domain.research.prompt.ScoringPromptFactory
import com.meogo.domain.research.parse.ScoringResponseParser
import com.meogo.infra.llm.client.LlmFanoutClient
import com.meogo.infra.llm.config.LlmConfiguration
import io.kotest.core.annotation.EnabledCondition
import io.kotest.core.annotation.EnabledIf
import io.kotest.core.spec.Spec
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.context.annotation.UserConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import kotlin.reflect.KClass

class ScoringSmokeEnabledCondition : EnabledCondition {
    override fun enabled(kclass: KClass<out Spec>): Boolean =
        System.getProperty(SMOKE_ENABLED_PROPERTY) == "true"

    companion object {
        const val SMOKE_ENABLED_PROPERTY = "scoring.smoke.enabled"
    }
}

@EnabledIf(ScoringSmokeEnabledCondition::class)
class AvoidanceScoringSmokeTest : BehaviorSpec({

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

    given("실 API 키로 OpenAI·Upstage·Gemini 세 모델을 활성화하고 소량 음식·후보 성분으로 실 스코어링을 수동 검증하는 구성 (기본 비활성 — 수동 실행: -Dscoring.smoke.enabled=true 와 OPENAI_API_KEY/UPSTAGE_API_KEY/GEMINI_API_KEY 환경변수 필요, CI 미실행)") {
        `when`("음식 1개(비빔밥)와 후보 성분 3종(계란·우유·밀)으로 AvoidanceScoringJob 을 실호출하면") {
            then("세 모델의 실 응답이 모두 파싱돼 그 음식이 SCORED 로 확정되고 성분 스코어·음식명 번역·음식 설명이 채택된다") {
                runner.run { context ->
                    val fanoutClient = context.getBean(LlmFanoutClient::class.java)

                    val job = AvoidanceScoringJob(
                        nextChunk = SmokeFoodScoringSource(listOf(bibimbap()))::nextChunk,
                        llmFanoutClient = fanoutClient,
                        findSubstances = SmokeAvoidanceSubstanceRepository(listOf(egg(), milk(), wheat()))::findByCodes,
                        promptFactory = ScoringPromptFactory(),
                        responseParser = ScoringResponseParser(),
                        aggregator = ConsensusEnsembleAggregator(),
                        chunkSize = 10,
                    )

                    val results = job.run()

                    results shouldHaveSize 1
                    results.single().status shouldBe FoodScoringStatus.SCORED
                    results.single().scores shouldHaveSize 3
                    results.single().nameTranslations.isNotEmpty() shouldBe true
                    results.single().description.shouldNotBeNull()
                }
            }
        }
    }
})

private fun bibimbap(): Food =
    Food.reconstitute(
        id = 900L,
        content = FoodContent(
            name = LocalizedText(korean = "비빔밥"),
            description = LocalizedText(korean = "비빔밥 기본 설명"),
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

private class SmokeFoodScoringSource(private val foods: List<Food>) {
    fun nextChunk(page: Int, size: Int): List<Food> = foods.drop(page * size).take(size)
}

private class SmokeAvoidanceSubstanceRepository(
    private val substances: List<AvoidanceSubstance>,
) {
    fun findByCodes(codes: Set<AvoidanceSubstanceCode>): List<AvoidanceSubstance> = substances
}
