package com.kbap.app.batch.scoring

import com.kbap.domain.avoidance.AvoidanceSubstance
import com.kbap.domain.avoidance.AvoidanceSubstanceCode
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.research.input.CandidateSubstance
import com.kbap.domain.research.ensemble.ConsensusEnsembleAggregator
import com.kbap.domain.research.ensemble.FoodScoringResult
import com.kbap.domain.research.ensemble.FoodScoringStatus
import com.kbap.domain.research.parse.ModelScoring
import com.kbap.domain.research.input.ScoringFood
import com.kbap.domain.research.prompt.ScoringPromptFactory
import com.kbap.domain.research.parse.ScoringResponseParseException
import com.kbap.domain.research.parse.ScoringResponseParser
import com.kbap.infra.llm.client.LlmFanoutClient
import com.kbap.infra.llm.model.LlmChatRequest
import com.kbap.infra.llm.model.LlmModelId
import org.slf4j.LoggerFactory

class AvoidanceScoringJob(
    private val nextChunk: (page: Int, size: Int) -> List<ScoringFood>,
    private val llmFanoutClient: LlmFanoutClient,
    private val findSubstances: (Set<AvoidanceSubstanceCode>) -> List<AvoidanceSubstance>,
    private val promptFactory: ScoringPromptFactory,
    private val responseParser: ScoringResponseParser,
    private val aggregator: ConsensusEnsembleAggregator,
    private val chunkSize: Int,
) {
    private val logger = LoggerFactory.getLogger(AvoidanceScoringJob::class.java)

    fun run(): List<FoodScoringResult> {
        val candidates = loadCandidates()
        check(candidates.isNotEmpty()) { "scoring.candidates 는 비어있을 수 없습니다 — 회피성분 카탈로그(시드/활성 상태)를 확인하세요" }
        val results = mutableListOf<FoodScoringResult>()
        val seen = mutableSetOf<Long>()
        var page = 0
        while (true) {
            val chunk = nextChunk(page, chunkSize)
            if (chunk.isEmpty()) {
                break
            }
            val fresh = chunk.filter { seen.add(it.foodId) }
            if (fresh.isEmpty()) {
                break
            }
            results += scoreChunk(fresh, candidates)
            page++
        }
        return results
    }

    private fun loadCandidates(): List<CandidateSubstance> =
        findSubstances(AvoidanceSubstanceCode.entries.toSet())
            .map { CandidateSubstance(code = it.code.name, koreanLabel = it.displayName(LanguageCode.KO)) }

    private fun scoreChunk(
        scoringFoods: List<ScoringFood>,
        candidates: List<CandidateSubstance>,
    ): List<FoodScoringResult> {
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

        val foodIds = scoringFoods.map { it.foodId }.toSet()
        parsed.forEach { (modelId, scoring) ->
            val missingFoodIds = foodIds - scoring.coveredFoodIds
            if (missingFoodIds.isNotEmpty()) {
                logger.warn("스코어링 응답 음식 커버리지 미달 modelId={} missingFoodIds={}", modelId, missingFoodIds)
            }
        }

        val confirmed = fanoutResult.failures.isEmpty() &&
            parsed.size == REQUIRED_MODEL_COUNT &&
            parsed.all { it.second.coveredFoodIds.containsAll(foodIds) }
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
