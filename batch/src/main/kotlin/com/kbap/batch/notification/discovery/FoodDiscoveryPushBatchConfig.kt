package com.kbap.batch.notification.discovery

import com.kbap.batch.notification.suggestion.ScanSuggestionMemberIdReader
import com.kbap.batch.util.JobNameMdcListener
import com.kbap.common.domain.bookmark.BookmarkJpaRepository
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.FoodMarketingPopularJpaRepository
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.PushTargetResolver
import com.kbap.common.domain.notification.model.NotificationType
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
class FoodDiscoveryPushBatchConfig(
    @Value("\${kbap.batch.food-discovery.chunk-size:100}") private val chunkSize: Int,
    @Value("\${kbap.batch.food-discovery.ttl:3h}") private val ttl: Duration,
    @Value("\${kbap.storage.public-base-url:}") private val imagePublicBaseUrl: String,
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val settingRepository: NotificationSettingJpaRepository,
    private val targetResolver: PushTargetResolver,
    private val memberRepository: MemberJpaRepository,
    private val notificationRepository: NotificationJpaRepository,
    private val bookmarkRepository: BookmarkJpaRepository,
    private val popularRepository: FoodMarketingPopularJpaRepository,
    private val foodRepository: FoodJpaRepository,
    private val handler: PushHandler,
    private val meterRegistry: MeterRegistry,
    private val jobNameMdcListener: JobNameMdcListener,
) {
    @Bean
    fun foodDiscoveryPushJob(clock: Clock): Job {
        val writer = FoodDiscoveryPushWriter(
            handler,
            targetResolver,
            memberRepository,
            notificationRepository,
            bookmarkRepository,
            popularRepository,
            foodRepository,
            clock,
            ttl.seconds.toInt(),
            imagePublicBaseUrl,
            meterRegistry,
        )
        val step = StepBuilder("foodDiscoverySendStep", jobRepository)
            .chunk<Long, Long>(chunkSize)
            .transactionManager(transactionManager)
            .reader(ScanSuggestionMemberIdReader(settingRepository, clock, chunkSize, NotificationType.FOOD_DISCOVERY, FoodDiscoverySendWindow::startOfCurrentSlot))
            .writer(writer)
            .build()
        return JobBuilder(JOB_NAME, jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobNameMdcListener)
            .start(step)
            .build()
    }

    companion object {
        const val JOB_NAME = "foodDiscoveryPushJob"
    }
}
