package com.meogo.app.batch.scoring

import com.meogo.domain.avoidance.AvoidanceSubstanceService
import com.meogo.domain.food.FoodService
import com.meogo.domain.research.ensemble.ConsensusEnsembleAggregator
import com.meogo.domain.research.prompt.ScoringPromptFactory
import com.meogo.domain.research.parse.ScoringResponseParser
import com.meogo.infra.llm.client.LlmFanoutClient
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
        @Value("\${meogo.scoring.chunk-size:10}") chunkSize: Int,
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
