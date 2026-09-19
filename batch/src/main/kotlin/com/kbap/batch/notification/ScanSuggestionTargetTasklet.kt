package com.kbap.batch.notification

import com.kbap.common.domain.notification.NotificationJpaRepository
import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import com.kbap.common.domain.notification.model.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import java.time.Clock

class ScanSuggestionTargetTasklet(
    private val settingRepository: NotificationSettingJpaRepository,
    private val notificationRepository: NotificationJpaRepository,
    private val candidateDto: ScanSuggestionCandidateDto,
    private val clock: Clock,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val candidates = settingRepository.findMemberIdsByNewsTrue()
        val receivedThisSlot = notificationRepository
            .findMemberIdsByTypeAndCreatedAtAfter(NotificationType.SCAN_SUGGESTION, ScanSuggestionSendWindow.startOfCurrentSlot(clock))
            .toSet()
        val targets = candidates.filterNot { it in receivedThisSlot }
        candidateDto.load(targets)
        logger.info(
            "스캔 제안 대상 확정 candidates={} excludedThisSlot={} targets={}",
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
