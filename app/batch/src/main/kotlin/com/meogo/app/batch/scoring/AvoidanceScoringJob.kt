package com.meogo.app.batch.scoring

import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.AvoidanceSubstanceRepository
import com.meogo.core.food.Food
import com.meogo.core.food.FoodScoringSource
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.research.input.CandidateSubstance
import com.meogo.core.research.ensemble.ConsensusEnsembleAggregator
import com.meogo.core.research.ensemble.FoodScoringResult
import com.meogo.core.research.ensemble.FoodScoringStatus
import com.meogo.core.research.parse.ModelScoring
import com.meogo.core.research.input.ScoringFood
import com.meogo.core.research.prompt.ScoringPromptFactory
import com.meogo.core.research.parse.ScoringResponseParseException
import com.meogo.core.research.parse.ScoringResponseParser
import com.meogo.infra.llm.client.LlmFanoutClient
import com.meogo.infra.llm.model.LlmChatRequest
import com.meogo.infra.llm.model.LlmModelId
import org.slf4j.LoggerFactory

class AvoidanceScoringJob(
    private val foodScoringSource: FoodScoringSource,
    private val llmFanoutClient: LlmFanoutClient,
    private val avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
    private val promptFactory: ScoringPromptFactory,
    private val responseParser: ScoringResponseParser,
    private val aggregator: ConsensusEnsembleAggregator,
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(AvoidanceScoringJob::class.java)

    fun run(): List<FoodScoringResult> {
        val candidates = loadCandidates()
        val results = mutableListOf<FoodScoringResult>()
        val seen = mutableSetOf<Long>()
        var page = 0
        while (true) {
            val chunk = foodScoringSource.nextChunk(page, chunkSize)
            if (chunk.isEmpty()) {
                break
            }
            val fresh = chunk.filter { it.id != null && seen.add(it.id!!) }
            if (fresh.isEmpty()) {
                break
            }
            results += scoreChunk(fresh, candidates)
            page++
        }
        return results
    }

    private fun loadCandidates(): List<CandidateSubstance> =
        avoidanceSubstanceRepository
            .findByCodes(AvoidanceSubstanceCode.entries.toSet())
            .map { CandidateSubstance(code = it.code.name, koreanLabel = it.displayName(LanguageCode.KO)) }

    private fun scoreChunk(
        fresh: List<Food>,
        candidates: List<CandidateSubstance>,
    ): List<FoodScoringResult> {
        val scoringFoods = fresh.map { ScoringFood(foodId = it.id!!, koreanName = it.displayName(LanguageCode.KO)) }
        val prompt = promptFactory.build(scoringFoods, candidates)
        val fanoutResult = llmFanoutClient.generate(LlmChatRequest(prompt = prompt.prompt, system = prompt.system))

        fanoutResult.failures.forEach { failure ->
            logger.warn("스코어링 모델 호출 실패 modelId={} message={}", failure.modelId, failure.message)
        }

        val parsed = mutableListOf<Pair<LlmModelId, ModelScoring>>()
        fanoutResult.successes.forEach { success ->
            try {
                parsed += success.modelId to responseParser.parse(success.content, scoringFoods, candidates)
            } catch (exception: ScoringResponseParseException) {
                logger.warn("스코어링 응답 파싱 실패 modelId={} reason={}", success.modelId, exception.message)
            }
        }

        val confirmed = fanoutResult.failures.isEmpty() && parsed.size == REQUIRED_MODEL_COUNT
        if (!confirmed) {
            return scoringFoods.map { food ->
                FoodScoringResult(
                    foodId = food.foodId,
                    status = FoodScoringStatus.FAILED,
                    scores = emptyList(),
                    nameTranslations = emptyMap(),
                    description = null,
                )
            }
        }

        val ordered = parsed.sortedBy { it.first.ordinal }.map { it.second }
        return aggregator.aggregate(scoringFoods, candidates, ordered)
    }

    companion object {
        const val REQUIRED_MODEL_COUNT = 3
    }
}
