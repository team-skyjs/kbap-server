package com.kbap.batch.schedule

import com.kbap.batch.notification.PushReceiptSyncBatchConfig
import com.kbap.batch.notification.ScanSuggestionPushBatchConfig
import com.kbap.batch.notification.ScanSuggestionSendWindow
import com.kbap.common.domain.notification.model.MealSlot
import com.kbap.batch.trigger.BatchJobLaunchResult
import com.kbap.batch.trigger.BatchJobLauncher
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "kbap.batch.scheduler", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class BatchJobScheduler(
    private val launcher: BatchJobLauncher,
) {
    @Scheduled(cron = "0 0 * * * *", zone = TIME_ZONE)
    fun publishFoodContentOutbox() = launch("foodContentOutboxPublishJob")

    @Scheduled(cron = "0 30 * * * *", zone = TIME_ZONE)
    fun syncFoodVectors() = launch("foodVectorSyncJob")

    @Scheduled(cron = ScanSuggestionSendWindow.DAILY_AT_11_00, zone = TIME_ZONE)
    fun pushLunchScanSuggestions() = launch(ScanSuggestionPushBatchConfig.jobNameOf(MealSlot.LUNCH))

    @Scheduled(cron = ScanSuggestionSendWindow.DAILY_AT_17_00, zone = TIME_ZONE)
    fun pushDinnerScanSuggestions() = launch(ScanSuggestionPushBatchConfig.jobNameOf(MealSlot.DINNER))

    @Scheduled(cron = PushReceiptSyncBatchConfig.EVERY_10_MINUTES_FROM_11_TO_13_AND_17_TO_19, zone = TIME_ZONE)
    @Scheduled(cron = PushReceiptSyncBatchConfig.AT_13_00_AND_19_00, zone = TIME_ZONE)
    fun syncMarketingPushReceipts() = launch(PushReceiptSyncBatchConfig.MARKETING_JOB)

    @Scheduled(cron = PushReceiptSyncBatchConfig.EVERY_15_MINUTES, zone = TIME_ZONE)
    fun syncActivityPushReceipts() = launch(PushReceiptSyncBatchConfig.ACTIVITY_JOB)

    private fun launch(jobName: String) {
        when (val result = launcher.launch(jobName)) {
            is BatchJobLaunchResult.UnknownJob ->
                logger.info("스케줄 대상 잡이 이 환경에 구성되지 않아 건너뜁니다 job={}", jobName)

            is BatchJobLaunchResult.AlreadyRunning ->
                logger.warn(
                    "앞선 실행이 아직 끝나지 않아 이번 스케줄을 건너뜁니다 job={} executionId={}",
                    jobName,
                    result.executionId,
                )

            is BatchJobLaunchResult.Started ->
                logger.info("스케줄 트리거 job={} executionId={}", jobName, result.execution.id)
        }
    }

    private companion object {
        const val TIME_ZONE = "Asia/Seoul"

        val logger = LoggerFactory.getLogger(BatchJobScheduler::class.java)
    }
}
