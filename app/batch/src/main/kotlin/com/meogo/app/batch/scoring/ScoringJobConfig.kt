package com.meogo.app.batch.scoring

import com.meogo.domain.avoidance.AvoidanceSubstanceRepository
import com.meogo.domain.food.FoodScoringSource
import com.meogo.domain.research.ensemble.ConsensusEnsembleAggregator
import com.meogo.domain.research.prompt.ScoringPromptFactory
import com.meogo.domain.research.parse.ScoringResponseParser
import com.meogo.infra.llm.client.LlmFanoutClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
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
        foodScoringSource: FoodScoringSource,
        llmFanoutClient: LlmFanoutClient,
        avoidanceSubstanceRepository: AvoidanceSubstanceRepository,
        promptFactory: ScoringPromptFactory,
        responseParser: ScoringResponseParser,
        aggregator: ConsensusEnsembleAggregator,
        @Value("\${meogo.scoring.chunk-size:10}") chunkSize: Int,
    ): AvoidanceScoringJob =
        AvoidanceScoringJob(
            foodScoringSource = foodScoringSource,
            llmFanoutClient = llmFanoutClient,
            avoidanceSubstanceRepository = avoidanceSubstanceRepository,
            promptFactory = promptFactory,
            responseParser = responseParser,
            aggregator = aggregator,
            chunkSize = chunkSize,
        )

    @Bean
    @ConditionalOnProperty(prefix = "meogo.scoring.runner", name = ["enabled"], havingValue = "true")
    fun scoringJobRunner(job: AvoidanceScoringJob): ScoringJobRunner = ScoringJobRunner(job)
}
