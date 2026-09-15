package com.kbap.batch.notification

import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import java.time.Clock
import java.time.ZonedDateTime

class ScanSuggestionTargetTasklet(
    private val settingRepository: NotificationSettingJpaRepository,
    private val notificationRepository: NotificationJpaRepository,
    private val buffer: ScanSuggestionCandidateBuffer,
    private val clock: Clock,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        if (!ScanSuggestionSendWindow.isOpen(clock)) {
            logger.info("스캔 제안 발송 시간대 밖이라 건너뜁니다 now={}", ZonedDateTime.now(clock))
            contribution.exitStatus = ExitStatus.NOOP
            return RepeatStatus.FINISHED
        }
        val candidates = settingRepository.findMemberIdsByNewsTrue()
        val receivedToday = notificationRepository
            .findMemberIdsByTypeAndCreatedAtAfter(NotificationType.SCAN_SUGGESTION, ScanSuggestionSendWindow.startOfToday(clock))
            .toSet()
        val targets = candidates.filterNot { it in receivedToday }
        buffer.load(targets)
        logger.info(
            "스캔 제안 대상 확정 candidates={} excludedToday={} targets={}",
            candidates.size,
            candidates.size - targets.size,
            targets.size,
        )
        return RepeatStatus.FINISHED
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ScanSuggestionTargetTasklet::class.java)
    }
}
