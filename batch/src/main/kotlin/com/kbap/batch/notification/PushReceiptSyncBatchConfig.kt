package com.kbap.batch.notification

import com.kbap.batch.observability.JobNameMdcListener
import com.kbap.common.domain.notification.NotificationDispatchJpaRepository
import com.kbap.common.domain.notification.PushDispatchService
import com.kbap.common.domain.notification.PushReceiptService
import com.kbap.common.domain.notification.ResendPolicy
import com.kbap.common.domain.notification.model.NotificationDispatch
import com.kbap.common.domain.notification.model.NotificationType
import com.kbap.common.port.push.PushReceiptClient
import com.kbap.common.port.push.PushSender
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

@Configuration
class PushReceiptSyncBatchConfig(
    @Value("\${kbap.batch.push-receipt.chunk-size:100}") private val chunkSize: Int,
    @Value("\${kbap.batch.push-receipt.min-age:15m}") private val minAge: Duration,
    @Value("\${kbap.batch.push-receipt.max-age:24h}") private val maxAge: Duration,
    @Value("\${kbap.batch.push-receipt.resend-window:2h}") private val resendWindow: Duration,
    @Value("\${kbap.batch.push-receipt.max-resends:2}") private val maxResends: Int,
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val dispatchRepository: NotificationDispatchJpaRepository,
    private val receiptClient: PushReceiptClient,
    private val receiptService: PushReceiptService,
    private val dispatchService: PushDispatchService,
    private val sender: PushSender,
    private val meterRegistry: MeterRegistry,
    private val jobNameMdcListener: JobNameMdcListener,
) {
    @Bean
    fun marketingPushReceiptSyncJob(clock: Clock): Job = job(MARKETING_JOB, NotificationType.entries.filter { it.marketing }, clock)

    @Bean
    fun activityPushReceiptSyncJob(clock: Clock): Job = job(ACTIVITY_JOB, NotificationType.entries.filterNot { it.marketing }, clock)

    private fun job(name: String, types: List<NotificationType>, clock: Clock): Job {
        val step = StepBuilder("${name}Step", jobRepository)
            .chunk<NotificationDispatch, NotificationDispatch>(chunkSize)
            .transactionManager(transactionManager)
            .reader(PushReceiptTargetReader(dispatchRepository, types, clock, minAge, maxAge, chunkSize))
            .writer(PushReceiptSyncWriter(receiptClient, receiptService, dispatchService, sender, ResendPolicy(maxResends, resendWindow), clock, meterRegistry))
            .build()
        return JobBuilder(name, jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobNameMdcListener)
            .start(step)
            .build()
    }

    companion object {
        const val MARKETING_JOB = "marketingPushReceiptSyncJob"
        const val ACTIVITY_JOB = "activityPushReceiptSyncJob"
        const val EVERY_10_MINUTES_FROM_11_TO_13_AND_17_TO_19 = "0 0/10 11-12,17-18 * * *"
        const val AT_13_00_AND_19_00 = "0 0 13,19 * * *"
        const val EVERY_15_MINUTES = "0 0/15 * * * *"
    }
}
