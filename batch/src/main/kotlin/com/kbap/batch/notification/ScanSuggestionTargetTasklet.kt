package com.kbap.batch.notification

import com.kbap.common.domain.notification.NotificationSettingJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.batch.core.scope.context.ChunkContext
import org.springframework.batch.core.step.StepContribution
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.infrastructure.repeat.RepeatStatus

class ScanSuggestionTargetTasklet(
    private val settingRepository: NotificationSettingJpaRepository,
    private val buffer: ScanSuggestionCandidateBuffer,
) : Tasklet {
    override fun execute(contribution: StepContribution, chunkContext: ChunkContext): RepeatStatus {
        val candidates = settingRepository.findMemberIdsByNewsTrue()
        buffer.load(candidates)
        logger.info("스캔 제안 대상 확정 candidates={} excludedToday={} targets={}", candidates.size, 0, candidates.size)
        return RepeatStatus.FINISHED
    }

    private companion object {
        val logger = LoggerFactory.getLogger(ScanSuggestionTargetTasklet::class.java)
    }
}
