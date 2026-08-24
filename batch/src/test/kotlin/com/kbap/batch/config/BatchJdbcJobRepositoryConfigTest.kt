package com.kbap.batch.config

import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldNotBe
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParameters
import org.springframework.batch.core.launch.JobOperator
import org.springframework.batch.core.repository.JobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class BatchJdbcJobRepositoryConfigTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var jobRepository: JobRepository

    @Autowired
    private lateinit var jobOperator: JobOperator

    @Autowired
    @Qualifier("foodContentOutboxPublishJob")
    private lateinit var job: Job

    init {
        given("배치 애플리케이션 컨텍스트") {
            `when`("같은 잡을 연달아 두 번 실행하면") {
                then("실행마다 새 JobExecution 이 메타데이터에 영속된다") {
                    val first = jobOperator.start(job, JobParameters())
                    val second = jobOperator.start(job, JobParameters())

                    second.id shouldNotBe first.id
                    jobRepository.getJobExecution(first.id) shouldNotBe null
                    jobRepository.getJobExecution(second.id) shouldNotBe null
                }

                then("JobInstance 와 StepExecution 도 조회된다") {
                    val execution = jobOperator.start(job, JobParameters())
                    awaitFinished(execution.id)

                    jobRepository.getJobInstances(job.name, 0, 10).shouldNotBeEmpty()
                    jobRepository.getJobExecution(execution.id)?.stepExecutions.orEmpty().shouldNotBeEmpty()
                }
            }
        }
    }

    private fun awaitFinished(executionId: Long) {
        repeat(100) {
            if (jobRepository.getJobExecution(executionId)?.status?.isRunning() == false) return
            Thread.sleep(100)
        }
    }
}
