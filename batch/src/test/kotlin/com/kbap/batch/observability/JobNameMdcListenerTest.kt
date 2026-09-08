package com.kbap.batch.observability

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.slf4j.MDC
import org.springframework.batch.core.ExitStatus
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.job.JobInstance
import org.springframework.batch.core.job.parameters.JobParameters

class JobNameMdcListenerTest : BehaviorSpec({
    val listener = JobNameMdcListener()

    fun execution() = JobExecution(1L, JobInstance(1L, "foodVectorSyncJob"), JobParameters())

    afterTest { MDC.clear() }

    given("잡 실행 리스너") {
        `when`("잡이 시작되면") {
            listener.beforeJob(execution())
            then("MDC job 에 잡 이름이 들어간다") {
                MDC.get("job") shouldBe "foodVectorSyncJob"
            }
        }

        `when`("잡이 성공으로 끝나면") {
            listener.beforeJob(execution())
            listener.afterJob(execution())
            then("MDC job 이 지워진다") {
                MDC.get("job").shouldBeNull()
            }
        }

        `when`("잡이 실패로 끝나도") {
            val failed = execution().apply { exitStatus = ExitStatus.FAILED }
            listener.beforeJob(failed)
            listener.afterJob(failed)
            then("MDC job 이 지워진다") {
                MDC.get("job").shouldBeNull()
            }
        }
    }
})
