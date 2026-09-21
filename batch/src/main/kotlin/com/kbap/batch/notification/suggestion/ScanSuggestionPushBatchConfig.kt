package com.kbap.batch.notification

import com.kbap.batch.util.JobNameMdcListener
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.common.port.push.PushHandler
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

@Configuration
class ScanSuggestionPushBatchConfig(
    @Value("\${kbap.batch.scan-suggestion.chunk-size:100}") private val chunkSize: Int,
    @Value("\${kbap.batch.scan-suggestion.ttl:3h}") private val ttl: Duration,
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val settingRepository: NotificationSettingJpaRepository,
    private val handler: PushHandler,
    private val meterRegistry: MeterRegistry,
    private val jobNameMdcListener: JobNameMdcListener,
) {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))

    @Bean
    fun scanSuggestionLunchPushJob(clock: Clock): Job = job(MealSlot.LUNCH, clock)

    @Bean
    fun scanSuggestionDinnerPushJob(clock: Clock): Job = job(MealSlot.DINNER, clock)

    private fun job(slot: MealSlot, clock: Clock): Job {
        val sendStep = StepBuilder("scanSuggestion${slot.jobInfix()}SendStep", jobRepository)
            .chunk<Long, Long>(chunkSize)
            .transactionManager(transactionManager)
            .reader(ScanSuggestionMemberIdReader(settingRepository, clock, chunkSize))
            .writer(ScanSuggestionPushWriter(handler, slot, ttl.seconds.toInt(), meterRegistry))
            .build()
        return JobBuilder(jobNameOf(slot), jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobNameMdcListener)
            .start(sendStep)
            .build()
    }

    companion object {
        fun jobNameOf(slot: MealSlot): String = "scanSuggestion${slot.jobInfix()}PushJob"

        private fun MealSlot.jobInfix(): String = name.lowercase().replaceFirstChar { it.uppercase() }
    }
}
