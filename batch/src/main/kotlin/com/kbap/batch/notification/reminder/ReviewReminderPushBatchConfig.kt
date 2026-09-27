package com.kbap.batch.notification.reminder

import com.kbap.batch.util.JobNameMdcListener
import com.kbap.common.domain.order.OrderJpaRepository
import com.kbap.common.domain.order.model.Order
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

@Configuration
class ReviewReminderPushBatchConfig(
    @Value("\${kbap.batch.review-reminder.chunk-size:100}") private val chunkSize: Int,
    @Value("\${kbap.batch.review-reminder.ttl:6h}") private val ttl: Duration,
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val orderRepository: OrderJpaRepository,
    private val handler: PushHandler,
    private val meterRegistry: MeterRegistry,
    private val jobNameMdcListener: JobNameMdcListener,
) {
    @Bean
    fun reviewReminderPushJob(clock: Clock): Job {
        val sendStep = StepBuilder("reviewReminderSendStep", jobRepository)
            .chunk<Order, Order>(chunkSize)
            .transactionManager(transactionManager)
            .reader(ReviewReminderOrderReader(orderRepository, clock, chunkSize))
            .writer(ReviewReminderPushWriter(handler, ttl.seconds.toInt(), meterRegistry))
            .build()
        return JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobNameMdcListener)
            .start(sendStep)
            .build()
    }

    companion object {
        const val JOB_NAME = "reviewReminderPushJob"
        const val EVERY_5_MINUTES = "0 */5 * * * *"
    }
}
