package com.kbap.batch.notification

import com.kbap.batch.observability.JobNameMdcListener
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.port.push.PushNotifier
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
class ScanSuggestionPushBatchConfig {
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
    fun scanSuggestionPushWriter(
        notifier: PushNotifier,
        meterRegistry: MeterRegistry,
        @Value("\${kbap.batch.scan-suggestion.ttl:3h}") ttl: Duration,
    ): ScanSuggestionPushWriter = ScanSuggestionPushWriter(notifier, ttl.seconds.toInt(), meterRegistry)

    @Bean
    fun scanSuggestionSendStep(
        jobRepository: JobRepository,
        scanSuggestionCandidateReader: ItemReader<Long>,
        scanSuggestionPushWriter: ScanSuggestionPushWriter,
        @Value("\${kbap.batch.scan-suggestion.member-chunk-size:500}") memberChunkSize: Int,
    ): Step =
        StepBuilder("scanSuggestionSendStep", jobRepository)
            .chunk<Long, Long>(memberChunkSize)
            .transactionManager(ResourcelessTransactionManager())
            .reader(scanSuggestionCandidateReader)
            .writer(scanSuggestionPushWriter)
            .build()

    @Bean
    fun scanSuggestionPushJob(
        jobRepository: JobRepository,
        scanSuggestionTargetStep: Step,
        scanSuggestionSendStep: Step,
        jobNameMdcListener: JobNameMdcListener,
    ): Job =
        JobBuilder("scanSuggestionPushJob", jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobNameMdcListener)
            .start(scanSuggestionTargetStep)
            .next(scanSuggestionSendStep)
            .build()
}
