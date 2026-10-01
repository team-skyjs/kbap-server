package com.kbap.batch.stats

import com.kbap.batch.util.JobNameMdcListener
import com.kbap.common.domain.member.MemberJpaRepository
import com.kbap.common.port.teamchannel.TeamChannelSender
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.job.parameters.RunIdIncrementer
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.repeat.RepeatStatus
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class DailyUserStatsBatchConfig(
    @Value("\${kbap.batch.user-stats.excluded-member-ids:}") private val excludedMemberIds: Set<Long>,
    private val jobRepository: JobRepository,
    private val memberRepository: MemberJpaRepository,
    private val sender: ObjectProvider<TeamChannelSender>,
    private val jobNameMdcListener: JobNameMdcListener,
) {
    @Bean
    fun dailyUserStatsJob(clock: Clock): Job {
        val step = StepBuilder("${JOB}Step", jobRepository)
            .tasklet({ contribution, _ ->
                val reporter = DailyUserStatsReporter(memberRepository, sender.ifAvailable, clock, excludedMemberIds)
                if (reporter.report() == DailyUserStatsReporter.Outcome.SKIPPED) {
                    contribution.exitStatus = ExitStatus(SKIPPED)
                }
                RepeatStatus.FINISHED
            }, ResourcelessTransactionManager())
            .build()
        return JobBuilder(JOB, jobRepository)
            .incrementer(RunIdIncrementer())
            .listener(jobNameMdcListener)
            .start(step)
            .build()
    }

    companion object {
        const val JOB = "dailyUserStatsJob"
        const val SKIPPED = "SKIPPED"
        const val DAILY_AT_09_00 = "0 0 9 * * *"
    }
}
