package com.kbap.app.batch.scoring

import com.kbap.domain.avoidance.AvoidanceSubstanceService
import com.kbap.domain.food.FoodService
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
        foodService: FoodService,
        llmFanoutClient: LlmFanoutClient,
        avoidanceSubstanceService: AvoidanceSubstanceService,
        promptFactory: ScoringPromptFactory,
        responseParser: ScoringResponseParser,
        aggregator: ConsensusEnsembleAggregator,
        @Value("\${kbap.scoring.chunk-size:10}") chunkSize: Int,
    ): AvoidanceScoringJob =
        AvoidanceScoringJob(
            nextChunk = foodService::nextChunk,
            llmFanoutClient = llmFanoutClient,
            findSubstances = avoidanceSubstanceService::findByCodes,
            promptFactory = promptFactory,
            responseParser = responseParser,
            aggregator = aggregator,
            chunkSize = chunkSize,
        )
}
