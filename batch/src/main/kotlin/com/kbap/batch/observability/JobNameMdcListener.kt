package com.kbap.batch.observability

import org.slf4j.MDC
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.listener.JobExecutionListener
import org.springframework.stereotype.Component

@Component
class JobNameMdcListener : JobExecutionListener {

    override fun beforeJob(jobExecution: JobExecution) {
        MDC.put(JOB_KEY, jobExecution.jobInstance.jobName)
    }

    override fun afterJob(jobExecution: JobExecution) {
        MDC.remove(JOB_KEY)
    }

    companion object {
        const val JOB_KEY: String = "job"
    }
}
