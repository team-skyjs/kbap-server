package com.kbap.app.batch.scoring

import com.kbap.domain.avoidance.AvoidanceCatalogService
import com.kbap.core.lang.LanguageCode
import com.kbap.domain.food.FoodScoringSource
import com.kbap.domain.research.input.ScoringFood
import com.kbap.domain.research.ensemble.ConsensusEnsembleAggregator
import com.kbap.domain.research.prompt.ScoringPromptFactory
import com.kbap.domain.research.parse.ScoringResponseParser
import com.kbap.infra.llm.client.LlmFanoutClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

@Configuration
@Import(FoodScoringSource::class, AvoidanceCatalogService::class)
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
        avoidanceCatalogService: AvoidanceCatalogService,
        promptFactory: ScoringPromptFactory,
        responseParser: ScoringResponseParser,
        aggregator: ConsensusEnsembleAggregator,
        @Value("\${kbap.scoring.chunk-size:10}") chunkSize: Int,
    ): AvoidanceScoringJob =
        AvoidanceScoringJob(
            nextChunk = { page, size ->
                foodScoringSource.nextChunk(page, size)
                    .map { ScoringFood(foodId = it.id, koreanName = it.displayName(LanguageCode.KO)) }
            },
            llmFanoutClient = llmFanoutClient,
            findSubstances = avoidanceCatalogService::getSubstancesByCodes,
            promptFactory = promptFactory,
            responseParser = responseParser,
            aggregator = aggregator,
            chunkSize = chunkSize,
        )
}
