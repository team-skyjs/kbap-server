// KB-301: 음식 콘텐츠 채움이 kbap-langchain 으로 이관돼 이 잡은 더 이상 실행하지 않는다.
// 복구 가능성을 위해 원본을 주석으로 보존한다 — 최종 삭제는 KB-302.
// package com.kbap.batch.content
//
// import com.kbap.batch.BatchTestClientConfig
// import com.kbap.common.port.llm.FoodAvoidanceAssessmentClient
// import com.kbap.common.port.llm.FoodAvoidanceAssessmentResult
// import com.kbap.common.port.llm.FoodDescriptionClient
// import com.kbap.common.port.llm.FoodDescriptionContent
// import com.kbap.common.port.llm.FoodNameTranslationClient
// import com.kbap.common.domain.food.model.TargetLanguageTexts
// import com.kbap.common.domain.LanguageCode
// import com.kbap.common.core.testsupport.MySqlContainerConfig
// import com.kbap.common.domain.food.FoodJpaRepository
// import com.kbap.common.domain.food.model.Food
// import com.kbap.common.domain.food.model.FoodAvoidanceItem
// import io.kotest.assertions.throwables.shouldThrow
// import io.kotest.core.spec.style.BehaviorSpec
// import io.kotest.extensions.spring.SpringExtension
// import io.kotest.matchers.shouldBe
// import org.springframework.beans.factory.annotation.Autowired
// import org.springframework.boot.test.context.SpringBootTest
// import org.springframework.context.annotation.Import
// import org.springframework.transaction.PlatformTransactionManager
//
// @SpringBootTest
// @Import(MySqlContainerConfig::class, BatchTestClientConfig::class)
// class FoodNameTranslationProcessorTest : BehaviorSpec() {
//     override fun extensions() = listOf(SpringExtension)
//
//     @Autowired
//     private lateinit var foodJpaRepository: FoodJpaRepository
//
//     @Autowired
//     private lateinit var transactionManager: PlatformTransactionManager
//
//     init {
//         val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.associate { it.code to "t-${it.code}" }
//
//         fun fullTexts(value: String) =
//             TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "$value-${it.code}" })
//
//         fun processor(
//             nameTranslationClient: FoodNameTranslationClient?,
//             avoidanceClient: FoodAvoidanceAssessmentClient = FoodAvoidanceAssessmentClient { _, _ ->
//                 FoodAvoidanceAssessmentResult(emptyList(), 0)
//             },
//             descriptionClient: FoodDescriptionClient? = FoodDescriptionClient { korean ->
//                 FoodDescriptionContent("$korean 설명", fullTexts("d"))
//             },
//         ) = FoodContentItemProcessor(
//             foodRepository = foodJpaRepository,
//             transactionManager = transactionManager,
//             avoidanceClient = avoidanceClient,
//             descriptionClient = descriptionClient,
//             nameTranslationClient = nameTranslationClient,
//             candidateCodes = { emptySet() },
//         )
//
//         fun saveNeedingOnlyNameTranslations(name: String): Food {
//             val food = foodJpaRepository.save(Food.incomplete(name))
//             food.imageRef = "s3://img/$name.jpg"
//             food.description = "맛있는 $name"
//             food.descriptionTranslations = targets
//             food.avoidanceSubstances = listOf(FoodAvoidanceItem("EGG", 90))
//             food.spiciness = 2
//             foodJpaRepository.save(food)
//             return foodJpaRepository.findById(food.id).get()
//         }
//
//         given("translateName — 이름 번역 미완") {
//             `when`("client 가 9개 전수 번역을 반환하면") {
//                 then("이름 번역이 커밋되고 미완 판정이 해소된다") {
//                     val food = saveNeedingOnlyNameTranslations("이름번역-잡채")
//                     val client = FoodNameTranslationClient { korean -> fullTexts(korean) }
//
//                     processor(client).process(food)
//
//                     val loaded = foodJpaRepository.findById(food.id).get()
//                     loaded.nameTranslations.keys shouldBe
//                         TargetLanguageTexts.TARGET_LANGUAGES.map { it.code }.toSet()
//                     loaded.nameTranslations["en"] shouldBe "이름번역-잡채-en"
//                     loaded.needsNameTranslations() shouldBe false
//                 }
//             }
//         }
//
//         given("translateName — 이름 번역만 미완인 음식") {
//             `when`("나머지 콘텐츠가 모두 완비된 음식을 처리하면") {
//                 then("이름 번역 client 만 호출되고 설명·기피성분 client 는 호출되지 않는다") {
//                     val food = saveNeedingOnlyNameTranslations("단독-비빔국수")
//                     var nameCalls = 0
//                     var descriptionCalls = 0
//                     var avoidanceCalls = 0
//                     val processor = processor(
//                         nameTranslationClient = { korean -> nameCalls++; fullTexts(korean) },
//                         avoidanceClient = { _, _ ->
//                             avoidanceCalls++
//                             FoodAvoidanceAssessmentResult(emptyList(), 0)
//                         },
//                         descriptionClient = { korean ->
//                             descriptionCalls++
//                             FoodDescriptionContent("$korean 설명", fullTexts("d"))
//                         },
//                     )
//
//                     processor.process(food)
//
//                     nameCalls shouldBe 1
//                     descriptionCalls shouldBe 0
//                     avoidanceCalls shouldBe 0
//                 }
//             }
//         }
//
//         given("translateName — 이미 완비(skip-if-done)") {
//             `when`("이름 번역이 전수로 채워져 있으면") {
//                 then("client 를 호출하지 않는다") {
//                     val food = saveNeedingOnlyNameTranslations("완비-김치전")
//                     food.nameTranslations = targets
//                     foodJpaRepository.save(food)
//                     var calls = 0
//                     val client = FoodNameTranslationClient { korean -> calls++; fullTexts(korean) }
//
//                     processor(client).process(foodJpaRepository.findById(food.id).get())
//
//                     calls shouldBe 0
//                 }
//             }
//         }
//
//         given("translateName — client 예외") {
//             `when`("client 가 예외를 던지면") {
//                 then("예외가 전파되고 기존 값은 훼손되지 않는다") {
//                     val food = saveNeedingOnlyNameTranslations("예외-떡국")
//                     val client = FoodNameTranslationClient { _ -> throw IllegalArgumentException("계약 위반 응답") }
//
//                     shouldThrow<IllegalArgumentException> { processor(client).process(food) }
//
//                     foodJpaRepository.findById(food.id).get().nameTranslations shouldBe emptyMap()
//                 }
//             }
//         }
//
//         given("translateName — client 미구성") {
//             `when`("이름 번역이 필요한데 nameTranslationClient 가 없으면") {
//                 then("명시적 예외로 실패한다(조용한 영구 INCOMPLETE 방지)") {
//                     val food = saveNeedingOnlyNameTranslations("미구성-된장국")
//
//                     shouldThrow<IllegalStateException> { processor(null).process(food) }
//                 }
//             }
//         }
//     }
// }
