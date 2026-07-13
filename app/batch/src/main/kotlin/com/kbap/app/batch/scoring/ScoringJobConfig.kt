package com.kbap.app.batch.scoring

import com.kbap.domain.avoidance.AvoidanceSubstanceJpaRepository
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.research.input.ScoringFood
import org.springframework.data.domain.PageRequest
import com.kbap.domain.research.ensemble.ConsensusEnsembleAggregator
import com.kbap.domain.research.prompt.ScoringPromptFactory
import com.kbap.domain.research.parse.ScoringResponseParser
import com.kbap.infra.llm.client.LlmFanoutClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ScoringJobConfig {

    @Bean
    fun scoringPromptFactory(): ScoringPromptFactory = ScoringPromptFactory()

    @Bean
    fun scoringResponseParser(): ScoringResponseParser = ScoringResponseParser()

    @Bean
    fun consensusEnsembleAggregator(): ConsensusEnsembleAggregator = ConsensusEnsembleAggregator()

    @Bean
    fun avoidanceScoringJob(
        foodRepository: FoodJpaRepository,
        llmFanoutClient: LlmFanoutClient,
        avoidanceSubstanceRepository: AvoidanceSubstanceJpaRepository,
        promptFactory: ScoringPromptFactory,
        responseParser: ScoringResponseParser,
        aggregator: ConsensusEnsembleAggregator,
        @Value("\${kbap.scoring.chunk-size:10}") chunkSize: Int,
    ): AvoidanceScoringJob =
        AvoidanceScoringJob(
            nextChunk = { page, size ->
                foodRepository.findFoodIds(PageRequest.of(page, size))
                    .takeIf { it.isNotEmpty() }
                    ?.let { ids -> foodRepository.findByIdIn(ids).sortedBy { it.id } }
                    .orEmpty()
                    .map { ScoringFood(foodId = it.id, koreanName = it.displayName(LanguageCode.KO)) }
            },
            llmFanoutClient = llmFanoutClient,
            findSubstances = { codes -> if (codes.isEmpty()) emptyList() else avoidanceSubstanceRepository.findByCodeIn(codes) },
            promptFactory = promptFactory,
            responseParser = responseParser,
            aggregator = aggregator,
            chunkSize = chunkSize,
        )
}
