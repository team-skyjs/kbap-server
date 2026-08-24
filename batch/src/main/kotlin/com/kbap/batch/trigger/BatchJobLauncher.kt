package com.kbap.batch.trigger

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.repository.JobRepository
import org.springframework.dao.DataAccessException
import org.springframework.stereotype.Component

sealed interface BatchJobLaunchResult {
    data class Started(val execution: JobExecution) : BatchJobLaunchResult

    data class AlreadyRunning(val executionId: Long?) : BatchJobLaunchResult

    data object UnknownJob : BatchJobLaunchResult
}

@Component
class BatchJobLauncher(
    jobs: List<Job>,
    private val jobOperator: JobOperator,
    private val jobRepository: JobRepository,
) {
    private val jobsByName = jobs.associateBy { it.name }

    val jobNames: Set<String>
        get() = jobsByName.keys

    fun launch(jobName: String): BatchJobLaunchResult {
        val job = jobsByName[jobName] ?: return BatchJobLaunchResult.UnknownJob

        runningExecutionId(jobName)?.let { return BatchJobLaunchResult.AlreadyRunning(it) }

        return try {
            val execution = jobOperator.start(job, JobParameters())
            logger.info("배치 잡 기동 job={} executionId={}", jobName, execution.id)
            BatchJobLaunchResult.Started(execution)
        } catch (e: JobExecutionAlreadyRunningException) {
            logger.warn("이미 실행 중인 잡을 다시 기동하려 했습니다 job={}", jobName, e)
            BatchJobLaunchResult.AlreadyRunning(runningExecutionId(jobName))
        }
    }

    fun getExecution(executionId: Long): JobExecution? =
        try {
            jobRepository.getJobExecution(executionId)
        } catch (e: DataAccessException) {
            null
        }

    private fun runningExecutionId(jobName: String): Long? =
        jobRepository.findRunningJobExecutions(jobName).firstOrNull()?.id

    private companion object {
        val logger = LoggerFactory.getLogger(BatchJobLauncher::class.java)
    }
}
