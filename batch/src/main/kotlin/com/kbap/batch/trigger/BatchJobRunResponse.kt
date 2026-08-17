package com.kbap.batch.trigger

import org.springframework.batch.core.job.JobExecution

data class BatchJobRunResponse(
    val jobName: String,
    val executionId: Long?,
    val status: String,
    val exitCode: String?,
    val message: String?,
) {
    companion object {
        fun of(execution: JobExecution): BatchJobRunResponse =
            BatchJobRunResponse(
                jobName = execution.jobInstance.jobName,
                executionId = execution.id,
                status = execution.status.name,
                exitCode = execution.exitStatus.exitCode,
                message = execution.allFailureExceptions.firstOrNull()?.message,
            )

        fun notFound(jobName: String, knownJobNames: Collection<String>): BatchJobRunResponse =
            BatchJobRunResponse(
                jobName = jobName,
                executionId = null,
                status = "NOT_FOUND",
                exitCode = null,
                message = "실행 가능한 잡: ${knownJobNames.sorted().joinToString(", ")}",
            )

        fun alreadyRunning(jobName: String): BatchJobRunResponse =
            BatchJobRunResponse(
                jobName = jobName,
                executionId = null,
                status = "ALREADY_RUNNING",
                exitCode = null,
                message = "이미 실행 중입니다. 끝난 뒤 다시 트리거하세요.",
            )
    }
}
