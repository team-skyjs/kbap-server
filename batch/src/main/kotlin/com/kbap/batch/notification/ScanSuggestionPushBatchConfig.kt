package com.kbap.batch.notification

import com.kbap.batch.observability.JobNameMdcListener
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.common.port.push.PushHandler
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemReader
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Duration
import java.time.ZoneId

@Configuration
class ScanSuggestionPushBatchConfig(
    @Value("\${kbap.batch.scan-suggestion.member-chunk-size:500}") private val memberChunkSize: Int,
    @Value("\${kbap.batch.scan-suggestion.ttl:3h}") private val ttl: Duration,
) {
    @Bean
    fun clock(): Clock = Clock.system(ZoneId.of("Asia/Seoul"))

    @Bean
    fun scanSuggestionTargetTasklet(
        settingRepository: NotificationSettingJpaRepository,
        notificationRepository: NotificationJpaRepository,
        buffer: ScanSuggestionCandidateBuffer,
        clock: Clock,
    ): ScanSuggestionTargetTasklet = ScanSuggestionTargetTasklet(settingRepository, notificationRepository, buffer, clock)

    @Bean
    fun scanSuggestionTargetStep(jobRepository: JobRepository, tasklet: ScanSuggestionTargetTasklet): Step =
        StepBuilder("scanSuggestionTargetStep", jobRepository)
            .tasklet(tasklet, ResourcelessTransactionManager())
            .build()

    @Bean
    fun scanSuggestionCandidateReader(buffer: ScanSuggestionCandidateBuffer): ItemReader<Long> = ItemReader { buffer.poll() }

    @Bean
    fun scanSuggestionLunchPushJob(
        jobRepository: JobRepository,
        scanSuggestionTargetStep: Step,
        scanSuggestionCandidateReader: ItemReader<Long>,
        handler: PushHandler,
        meterRegistry: MeterRegistry,
        jobNameMdcListener: JobNameMdcListener,
    ): Job = job(MealSlot.LUNCH, jobRepository, scanSuggestionTargetStep, scanSuggestionCandidateReader, handler, meterRegistry, jobNameMdcListener)

    @Bean
    fun scanSuggestionDinnerPushJob(
        jobRepository: JobRepository,
        scanSuggestionTargetStep: Step,
        scanSuggestionCandidateReader: ItemReader<Long>,
        handler: PushHandler,
        meterRegistry: MeterRegistry,
        jobNameMdcListener: JobNameMdcListener,
    ): Job = job(MealSlot.DINNER, jobRepository, scanSuggestionTargetStep, scanSuggestionCandidateReader, handler, meterRegistry, jobNameMdcListener)

    private fun job(
        slot: MealSlot,
        jobRepository: JobRepository,
        targetStep: Step,
        reader: ItemReader<Long>,
        handler: PushHandler,
        meterRegistry: MeterRegistry,
        jobNameMdcListener: JobNameMdcListener,
    ): Job {
        val sendStep = StepBuilder("scanSuggestion${slot.jobInfix()}SendStep", jobRepository)
            .chunk<Long, Long>(memberChunkSize)
            .transactionManager(ResourcelessTransactionManager())
            .reader(reader)
            .writer(ScanSuggestionPushWriter(handler, slot, ttl.seconds.toInt(), meterRegistry))
            .build()
        return JobBuilder(jobNameOf(slot), jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobNameMdcListener)
            .start(targetStep)
            .next(sendStep)
            .build()
    }

    companion object {
        fun jobNameOf(slot: MealSlot): String = "scanSuggestion${slot.jobInfix()}PushJob"

        private fun MealSlot.jobInfix(): String = name.lowercase().replaceFirstChar { it.uppercase() }
    }
}
