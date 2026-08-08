// KB-301: 음식 콘텐츠 채움이 kbap-langchain 으로 이관돼 이 잡은 더 이상 실행하지 않는다.
// 복구 가능성을 위해 원본을 주석으로 보존한다 — 최종 삭제는 KB-302.
// package com.kbap.batch.content
//
// import com.kbap.common.port.llm.FoodAvoidanceAssessment
// import com.kbap.common.port.llm.FoodAvoidanceAssessmentClient
// import com.kbap.common.port.llm.FoodAvoidanceAssessmentResult
// import com.kbap.common.port.llm.FoodDescriptionClient
// import com.kbap.common.port.llm.FoodDescriptionContent
// import com.kbap.common.port.llm.FoodNameTranslationClient
// import com.kbap.common.domain.food.model.TargetLanguageTexts
// import com.kbap.common.core.testsupport.MySqlContainerConfig
// import com.kbap.common.domain.ingredient.IngredientJpaRepository
// import com.kbap.common.domain.ingredient.model.Ingredient
// import com.kbap.common.domain.ingredient.model.IngredientCode
// import com.kbap.common.domain.food.FoodJpaRepository
// import com.kbap.common.domain.food.model.Food
// import com.kbap.common.domain.food.model.FoodContentStatus
// import io.kotest.core.spec.style.BehaviorSpec
// import io.kotest.extensions.spring.SpringExtension
// import io.kotest.matchers.nulls.shouldNotBeNull
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
// @Import(MySqlContainerConfig::class, FoodContentJobTest.ThrowingClientConfig::class)
// class FoodContentJobTest : BehaviorSpec() {
//     override fun extensions() = listOf(SpringExtension)
//
//     @TestConfiguration
//     class ThrowingClientConfig {
//         @Bean
//         fun avoidanceClient(): FoodAvoidanceAssessmentClient =
//             FoodAvoidanceAssessmentClient { koreanName, _ ->
//                 if (koreanName == FAILING_FOOD) throw RuntimeException("조사 실패: $koreanName")
//                 FoodAvoidanceAssessmentResult(listOf(FoodAvoidanceAssessment("EGG", 90)), 2)
//             }
//
//         @Bean
//         fun nameTranslationClient(): FoodNameTranslationClient =
//             FoodNameTranslationClient { korean ->
//                 TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "$korean-${it.code}" })
//             }
//
//         @Bean
//         fun descriptionClient(): FoodDescriptionClient =
//             FoodDescriptionClient { korean ->
//                 FoodDescriptionContent(
//                     "$korean 설명",
//                     TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "$korean-${it.code}" }),
//                 )
//             }
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
//     @Autowired
//     private lateinit var avoidanceRepository: IngredientJpaRepository
//
//     init {
//         given("청크 트랜잭션 없이(ResourcelessTransactionManager) 잡을 실행하면") {
//             `when`("여러 음식 중 한 건의 기피성분 조사가 예외를 던지면") {
//                 then("잡은 COMPLETED 로 끝나고, 실패 음식만 미조사로 남으며 나머지는 성분·맵기가 함께 커밋된다") {
//                     foodRepository.deleteAll()
//                     avoidanceRepository.deleteAll()
//                     avoidanceRepository.save(Ingredient(code = IngredientCode.EGG, koreanName = "달걀"))
//                     val ok1 = foodRepository.save(Food.incomplete("성공-김밥")).id
//                     val failed = foodRepository.save(Food.incomplete(FAILING_FOOD)).id
//                     val ok2 = foodRepository.save(Food.incomplete("성공-비빔밥")).id
//
//                     val params = JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters()
//                     val execution = jobLauncher.run(foodContentJob, params)
//
//                     execution.status shouldBe BatchStatus.COMPLETED
//                     val loadedOk1 = foodRepository.findById(ok1).get()
//                     val loadedOk2 = foodRepository.findById(ok2).get()
//                     val loadedFailed = foodRepository.findById(failed).get()
//                     loadedOk1.avoidanceSubstances.shouldNotBeNull()
//                     loadedOk1.spiciness shouldBe 2
//                     loadedOk2.avoidanceSubstances.shouldNotBeNull()
//                     loadedOk2.spiciness shouldBe 2
//                     loadedFailed.avoidanceSubstances shouldBe null
//                     loadedFailed.spiciness shouldBe Food.SPICINESS_UNASSESSED
//                     loadedFailed.needsNameTranslations() shouldBe false
//                     loadedFailed.needsDescription() shouldBe false
//                     loadedFailed.needsDescriptionTranslations() shouldBe false
//                 }
//             }
//         }
//
//         given("텍스트 3작업 전체 파이프라인 — 배치는 텍스트 전담(KB-226)") {
//             `when`("INCOMPLETE 음식 1건으로 잡을 1회 실행하면") {
//                 then("텍스트 3작업이 채워지고 이미지 미보유라 PENDING_IMAGE(이미지 대기)로 전이한다") {
//                     foodRepository.deleteAll()
//                     avoidanceRepository.deleteAll()
//                     avoidanceRepository.save(Ingredient(code = IngredientCode.EGG, koreanName = "달걀"))
//                     val id = foodRepository.save(Food.incomplete("전체-잡곡밥")).id
//
//                     val params = JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters()
//                     val execution = jobLauncher.run(foodContentJob, params)
//
//                     execution.status shouldBe BatchStatus.COMPLETED
//                     val loaded = foodRepository.findById(id).get()
//                     loaded.needsNameTranslations() shouldBe false
//                     loaded.needsDescription() shouldBe false
//                     loaded.needsDescriptionTranslations() shouldBe false
//                     loaded.needsAvoidanceAssessment() shouldBe false
//                     loaded.spiciness shouldBe 2
//                     loaded.needsImage() shouldBe true
//                     loaded.contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
//                 }
//             }
//
//             `when`("이미지만 남은(PENDING_IMAGE) 음식이 있는 상태에서 잡을 다시 실행하면") {
//                 then("INCOMPLETE 선정에서 빠져 재처리되지 않는다 — 무한 재선정 차단") {
//                     foodRepository.deleteAll()
//                     avoidanceRepository.deleteAll()
//                     avoidanceRepository.save(Ingredient(code = IngredientCode.EGG, koreanName = "달걀"))
//                     val id = foodRepository.save(Food.incomplete("재선정-잡채밥")).id
//
//                     jobLauncher.run(foodContentJob, JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters())
//                     foodRepository.findById(id).get().contentStatus shouldBe FoodContentStatus.PENDING_IMAGE
//
//                     val second = jobLauncher.run(
//                         foodContentJob,
//                         JobParametersBuilder().addLong("run.id", System.nanoTime()).toJobParameters(),
//                     )
//
//                     second.status shouldBe BatchStatus.COMPLETED
//                     second.stepExecutions.single().readCount shouldBe 0
//                 }
//             }
//         }
//     }
//
//     companion object {
//         private const val FAILING_FOOD = "실패-마라탕"
//     }
// }
