package com.kbap.app.batch.content

import com.kbap.core.food.FoodAvoidanceAssessment
import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.domain.avoidance.AvoidanceSubstanceJpaRepository
import com.kbap.domain.avoidance.model.AvoidanceSubstance
import com.kbap.domain.avoidance.model.AvoidanceSubstanceCode
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.model.Food
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobLauncher
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class, FoodContentJobTest.ThrowingClientConfig::class)
class FoodContentJobTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @TestConfiguration
    class ThrowingClientConfig {
        @Bean
        fun avoidanceClient(): FoodAvoidanceAssessmentClient =
            FoodAvoidanceAssessmentClient { koreanName, _ ->
                if (koreanName == FAILING_FOOD) throw RuntimeException("조사 실패: $koreanName")
                listOf(FoodAvoidanceAssessment("EGG", 90))
            }
    }

    @Autowired
    private lateinit var jobLauncher: JobLauncher

    @Autowired
    private lateinit var foodContentJob: Job

    @Autowired
    private lateinit var foodRepository: FoodJpaRepository

    @Autowired
    private lateinit var avoidanceRepository: AvoidanceSubstanceJpaRepository

    init {
        given("청크 트랜잭션 없이(ResourcelessTransactionManager) 잡을 실행하면") {
            `when`("여러 음식 중 한 건의 기피성분 조사가 예외를 던지면") {
                then("잡은 COMPLETED 로 끝나고, 실패 음식만 미조사(null)로 남으며 나머지는 성분이 커밋된다") {
                    foodRepository.deleteAll()
                    avoidanceRepository.deleteAll()
                    avoidanceRepository.save(AvoidanceSubstance(code = AvoidanceSubstanceCode.EGG, koreanName = "달걀"))
                    val ok1 = foodRepository.save(Food.incomplete("성공-김밥")).id
                    val failed = foodRepository.save(Food.incomplete(FAILING_FOOD)).id
                    val ok2 = foodRepository.save(Food.incomplete("성공-비빔밥")).id

                    val params = JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters()
                    val execution = jobLauncher.run(foodContentJob, params)

                    execution.status shouldBe BatchStatus.COMPLETED
                    foodRepository.findById(ok1).get().avoidanceSubstances.shouldNotBeNull()
                    foodRepository.findById(ok2).get().avoidanceSubstances.shouldNotBeNull()
                    foodRepository.findById(failed).get().avoidanceSubstances shouldBe null
                }
            }
        }
    }

    companion object {
        private const val FAILING_FOOD = "실패-마라탕"
    }
}
