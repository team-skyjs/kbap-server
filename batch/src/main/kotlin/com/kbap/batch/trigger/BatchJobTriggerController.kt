package com.kbap.batch.trigger

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.launch.JobExecutionAlreadyRunningException
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.repository.JobRepository
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/internal/batch")
class BatchJobTriggerController(
    jobs: List<Job>,
    private val jobOperator: JobOperator,
    private val jobRepository: JobRepository,
) {
    private val jobsByName = jobs.associateBy { it.name }

    @PostMapping("/jobs")
    fun runJob(@RequestParam jobName: String): ResponseEntity<BatchJobRunResponse> {
        val job = jobsByName[jobName]
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BatchJobRunResponse.jobNotFound(jobName, jobsByName.keys))

        runningExecutionId(jobName)?.let { runningId ->
            logger.warn("이미 실행 중인 잡을 다시 트리거했습니다 job={} executionId={}", jobName, runningId)
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(BatchJobRunResponse.alreadyRunning(jobName, runningId))
        }

        val execution = try {
            jobOperator.start(job, JobParameters())
        } catch (e: JobExecutionAlreadyRunningException) {
            logger.warn("이미 실행 중인 잡을 다시 트리거했습니다 job={}", jobName, e)
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(BatchJobRunResponse.alreadyRunning(jobName, runningExecutionId(jobName)))
        }

        logger.info("배치 잡 트리거 수신 job={} executionId={}", jobName, execution.id)
        return ResponseEntity.accepted().body(BatchJobRunResponse.of(execution))
    }

    @GetMapping("/executions/{executionId}")
    fun getExecution(@PathVariable executionId: Long): ResponseEntity<BatchJobRunResponse> {
        val execution = try {
            jobRepository.getJobExecution(executionId)
        } catch (e: DataAccessException) {
            null
        } ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(BatchJobRunResponse.executionNotFound(executionId))
        return ResponseEntity.ok(BatchJobRunResponse.of(execution))
    }

    private fun runningExecutionId(jobName: String): Long? =
        jobRepository.findRunningJobExecutions(jobName).firstOrNull()?.id

    private companion object {
        val logger = LoggerFactory.getLogger(BatchJobTriggerController::class.java)
    }
}
