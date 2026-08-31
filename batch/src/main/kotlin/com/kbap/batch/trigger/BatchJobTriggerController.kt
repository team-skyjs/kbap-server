package com.kbap.batch.trigger

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
    private val launcher: BatchJobLauncher,
) {
    @PostMapping("/jobs")
    fun runJob(@RequestParam jobName: String): ResponseEntity<BatchJobRunResponse> =
        when (val result = launcher.launch(jobName)) {
            is BatchJobLaunchResult.UnknownJob ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(BatchJobRunResponse.jobNotFound(jobName, launcher.jobNames))

            is BatchJobLaunchResult.AlreadyRunning ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(BatchJobRunResponse.alreadyRunning(jobName, result.executionId))

            is BatchJobLaunchResult.Started ->
                ResponseEntity.accepted().body(BatchJobRunResponse.of(result.execution))
        }

    @GetMapping("/executions/{executionId}")
    fun getExecution(@PathVariable executionId: Long): ResponseEntity<BatchJobRunResponse> {
        val execution = launcher.getExecution(executionId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(BatchJobRunResponse.executionNotFound(executionId))
        return ResponseEntity.ok(BatchJobRunResponse.of(execution))
    }
}
