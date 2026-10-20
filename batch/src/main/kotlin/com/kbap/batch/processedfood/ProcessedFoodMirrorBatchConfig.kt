package com.kbap.batch.processedfood

import com.kbap.batch.util.JobNameMdcListener
import com.kbap.common.domain.processedfood.ProcessedFoodProductJpaRepository
import com.kbap.common.infra.publicdata.DataGoKrClient
import com.kbap.common.infra.publicdata.DataGoKrFoodMakerLookup
import com.kbap.common.infra.publicdata.DataGoKrHaccpProductSource
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration

@Configuration
@ConditionalOnProperty(prefix = "kbap.processed-food", name = ["enabled"], havingValue = "true")
class ProcessedFoodMirrorBatchConfig(
    private val jobRepository: JobRepository,
    private val jobNameMdcListener: JobNameMdcListener,
) {
    @Bean
    fun processedFoodMirror(
        repository: ProcessedFoodProductJpaRepository,
        transactionManager: PlatformTransactionManager,
        clock: Clock,
        @Value("\${kbap.processed-food.base-url}") baseUrl: String,
        @Value("\${kbap.processed-food.service-key:}") serviceKey: String,
        @Value("\${kbap.processed-food.food-types}") foodTypes: List<String>,
        @Value("\${kbap.processed-food.page-size:100}") pageSize: Int,
        @Value("\${kbap.processed-food.maker-lookup-limit:2000}") makerLookupLimit: Int,
        @Value("\${kbap.processed-food.retry.max-retries:3}") maxRetries: Long,
        @Value("\${kbap.processed-food.retry.initial-delay:2s}") initialDelay: Duration,
    ): ProcessedFoodMirror {
        val client = DataGoKrClient.create(baseUrl, serviceKey, DataGoKrClient.defaultRetryPolicy(maxRetries, initialDelay))
        return ProcessedFoodMirror(
            DataGoKrHaccpProductSource(client),
            DataGoKrFoodMakerLookup(client),
            repository,
            TransactionTemplate(transactionManager),
            clock,
            foodTypes.map { it.trim() }.filter { it.isNotEmpty() },
            pageSize,
            makerLookupLimit,
        )
    }

    @Bean
    fun processedFoodMirrorJob(mirror: ProcessedFoodMirror): Job {
        val step = StepBuilder("${JOB}Step", jobRepository)
            .tasklet({ contribution, _ ->
                if (mirror.mirror().failedTypes.isNotEmpty()) contribution.exitStatus = ExitStatus(PARTIAL)
                RepeatStatus.FINISHED
            }, ResourcelessTransactionManager())
            .build()
        return JobBuilder(JOB, jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobNameMdcListener)
            .start(step)
            .build()
    }

    companion object {
        const val JOB = "processedFoodMirrorJob"
        const val PARTIAL = "PARTIAL"
        const val DAILY_AT_04_00 = "0 0 4 * * *"
    }
}
