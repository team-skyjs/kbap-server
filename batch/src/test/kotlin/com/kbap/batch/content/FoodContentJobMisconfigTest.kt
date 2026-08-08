// KB-301: 음식 콘텐츠 채움이 kbap-langchain 으로 이관돼 이 잡은 더 이상 실행하지 않는다.
// 복구 가능성을 위해 원본을 주석으로 보존한다 — 최종 삭제는 KB-302.
// package com.kbap.batch.content
//
// import com.kbap.common.port.llm.FoodAvoidanceAssessmentClient
// import com.kbap.common.port.llm.FoodAvoidanceAssessmentResult
// import com.kbap.common.core.testsupport.MySqlContainerConfig
// import com.kbap.common.domain.food.FoodJpaRepository
// import com.kbap.common.domain.food.model.Food
// import io.kotest.core.spec.style.BehaviorSpec
// import io.kotest.extensions.spring.SpringExtension
// import io.kotest.matchers.shouldBe
// import org.springframework.batch.core.BatchStatus
// import org.springframework.batch.core.job.Job
// import org.springframework.batch.core.job.parameters.JobParametersBuilder
// import org.springframework.batch.core.launch.JobLauncher
// import org.springframework.beans.factory.annotation.Autowired
// import org.springframework.boot.test.context.SpringBootTest
// import org.springframework.boot.test.context.TestConfiguration
// import org.springframework.context.annotation.Bean
// import org.springframework.context.annotation.Import
//
// @SpringBootTest
// @Import(MySqlContainerConfig::class, FoodContentJobMisconfigTest.AvoidanceOnlyConfig::class)
// class FoodContentJobMisconfigTest : BehaviorSpec() {
//     override fun extensions() = listOf(SpringExtension)
//
//     @TestConfiguration
//     class AvoidanceOnlyConfig {
//         @Bean
//         fun avoidanceClient(): FoodAvoidanceAssessmentClient =
//             FoodAvoidanceAssessmentClient { _, _ -> FoodAvoidanceAssessmentResult(emptyList(), 0) }
//     }
//
//     @Autowired
//     private lateinit var jobLauncher: JobLauncher
//
//     @Autowired
//     private lateinit var foodContentJob: Job
//
//     @Autowired
//     private lateinit var foodRepository: FoodJpaRepository
//
//     init {
//         given("이름 번역·설명 클라이언트가 구성되지 않은 컨텍스트") {
//             `when`("처리할 INCOMPLETE 음식이 있는 채로 잡을 실행하면") {
//                 then("음식별 skip 으로 위장하지 않고 잡이 FAILED 로 끝난다") {
//                     foodRepository.deleteAll()
//                     foodRepository.save(Food.incomplete("미구성-잡곡밥"))
//
//                     val params = JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters()
//                     val execution = jobLauncher.run(foodContentJob, params)
//
//                     execution.status shouldBe BatchStatus.FAILED
//                 }
//             }
//         }
//     }
// }
