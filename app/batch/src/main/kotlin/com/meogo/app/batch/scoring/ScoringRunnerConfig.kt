package com.meogo.app.batch.scoring

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ScoringRunnerConfig {

    @Bean
    @ConditionalOnProperty(prefix = "meogo.scoring.runner", name = ["enabled"], havingValue = "true")
    fun scoringJobRunner(job: AvoidanceScoringJob): ScoringJobRunner = ScoringJobRunner(job)
}
