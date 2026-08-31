package com.kbap.batch.trigger

import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
class SlowJobTestConfig {
    @Bean
    fun slowTestStep(jobRepository: JobRepository): Step =
        StepBuilder("slowTestStep", jobRepository)
            .tasklet(
                { _, _ ->
                    Thread.sleep(1500)
                    RepeatStatus.FINISHED
                },
                ResourcelessTransactionManager(),
            )
            .build()

    @Bean
    fun slowTestJob(jobRepository: JobRepository, slowTestStep: Step): Job =
        JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer())
            .start(slowTestStep)
            .build()

    companion object {
        const val JOB_NAME = "slowTestJob"
    }
}
