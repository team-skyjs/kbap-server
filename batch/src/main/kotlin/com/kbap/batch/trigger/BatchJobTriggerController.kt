package com.kbap.batch.trigger

import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.launch.JobOperator
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.ConcurrentHashMap

@RestController
@RequestMapping("/internal/batch/jobs")
class BatchJobTriggerController(
    jobs: List<Job>,
    private val jobOperator: JobOperator,
) {
    private val jobsByName = jobs.associateBy { it.name }
    private val runningJobNames = ConcurrentHashMap.newKeySet<String>()

    @PostMapping("/{jobName}")
    fun runJob(@PathVariable jobName: String): ResponseEntity<BatchJobRunResponse> {
        val job = jobsByName[jobName]
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BatchJobRunResponse.notFound(jobName, jobsByName.keys))

        if (!runningJobNames.add(jobName)) {
            logger.warn("이미 실행 중인 잡을 다시 트리거했습니다 job={}", jobName)
            return ResponseEntity.status(HttpStatus.CONFLICT).body(BatchJobRunResponse.alreadyRunning(jobName))
        }

        return try {
            logger.info("배치 잡 트리거 수신 job={}", jobName)
            val execution = jobOperator.start(job, JobParameters())
            val response = BatchJobRunResponse.of(execution)
            logger.info("배치 잡 트리거 완료 job={} status={}", jobName, response.status)
            if (execution.status.isUnsuccessful) {
                ResponseEntity.internalServerError().body(response)
            } else {
                ResponseEntity.ok(response)
            }
        } finally {
            runningJobNames.remove(jobName)
        }
    }

    private companion object {
        val logger = LoggerFactory.getLogger(BatchJobTriggerController::class.java)
    }
}
