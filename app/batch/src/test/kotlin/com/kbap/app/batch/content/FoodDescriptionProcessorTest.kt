package com.kbap.app.batch.content

import com.kbap.app.batch.BatchTestClientConfig
import com.kbap.common.core.food.FoodAvoidanceAssessmentClient
import com.kbap.common.core.food.FoodAvoidanceAssessmentResult
import com.kbap.common.core.food.FoodDescriptionClient
import com.kbap.common.core.food.FoodDescriptionContent
import com.kbap.common.core.food.TargetLanguageTexts
import com.kbap.common.core.lang.LanguageCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import com.kbap.common.domain.food.FoodJpaRepository
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodAvoidanceItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager

@SpringBootTest
@Import(MySqlContainerConfig::class, BatchTestClientConfig::class)
class FoodDescriptionProcessorTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.associate { it.code to "t-${it.code}" }
        val avoidanceDone = FoodAvoidanceAssessmentClient { _, _ -> FoodAvoidanceAssessmentResult(emptyList(), 0) }

        fun fullTranslations(value: String) =
            TargetLanguageTexts(TargetLanguageTexts.TARGET_LANGUAGES.associateWith { "$value-${it.code}" })

        fun processor(descriptionClient: FoodDescriptionClient?) =
            FoodContentItemProcessor(
                foodRepository = foodJpaRepository,
                transactionManager = transactionManager,
                avoidanceClient = avoidanceDone,
                candidateCodes = { emptySet() },
                descriptionClient = descriptionClient,
            )

        fun saveNeedingOnlyDescription(name: String): Food {
            val food = foodJpaRepository.save(Food.incomplete(name))
            food.imageRef = "s3://img/$name.jpg"
            food.nameTranslations = targets
            food.avoidanceSubstances = listOf(FoodAvoidanceItem("EGG", 90))
            food.spiciness = 2
            foodJpaRepository.save(food)
            return foodJpaRepository.findById(food.id).get()
        }

        given("generateDescription — 플레이스홀더 설명 생성") {
            `when`("client 가 설명·번역 세트를 반환하면") {
                then("설명과 9개 번역이 함께 커밋되고 맵기는 변하지 않는다") {
                    val food = saveNeedingOnlyDescription("설명생성-잡채")
                    val client = FoodDescriptionClient { korean ->
                        FoodDescriptionContent("$korean 은 당면 볶음 요리다", fullTranslations("desc"))
                    }

                    processor(client).process(food)

                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.description shouldBe "설명생성-잡채 은 당면 볶음 요리다"
                    loaded.descriptionTranslations.keys shouldBe
                        TargetLanguageTexts.TARGET_LANGUAGES.map { it.code }.toSet()
                    loaded.spiciness shouldBe 2
                    loaded.needsDescription() shouldBe false
                    loaded.needsDescriptionTranslations() shouldBe false
                }
            }
        }

        given("generateDescription — 번역만 미완인 음식") {
            `when`("설명 원문은 있으나 번역이 전수가 아니면") {
                then("원문·번역 세트를 함께 재생성해 교체한다") {
                    val food = saveNeedingOnlyDescription("번역미완-갈비탕")
                    food.description = "옛 설명"
                    food.descriptionTranslations = mapOf("en" to "old")
                    foodJpaRepository.save(food)
                    val client = FoodDescriptionClient { _ ->
                        FoodDescriptionContent("새 설명", fullTranslations("new"))
                    }

                    processor(client).process(foodJpaRepository.findById(food.id).get())

                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.description shouldBe "새 설명"
                    loaded.descriptionTranslations["en"] shouldBe "new-en"
                }
            }
        }

        given("generateDescription — 이미 완비(skip-if-done)") {
            `when`("설명·번역이 모두 채워져 있으면") {
                then("client 를 호출하지 않는다") {
                    val food = saveNeedingOnlyDescription("완비-국수")
                    food.description = "완성된 설명"
                    food.descriptionTranslations = targets
                    foodJpaRepository.save(food)
                    var calls = 0
                    val client = FoodDescriptionClient { _ ->
                        calls++
                        FoodDescriptionContent("불필요", fullTranslations("x"))
                    }

                    processor(client).process(foodJpaRepository.findById(food.id).get())

                    calls shouldBe 0
                }
            }
        }

        given("generateDescription — client 계약 위반 예외") {
            `when`("client 가 예외를 던지면") {
                then("예외가 전파되고 기존 값은 훼손되지 않는다") {
                    val food = saveNeedingOnlyDescription("예외-수제비")
                    val client = FoodDescriptionClient { _ -> throw IllegalArgumentException("계약 위반 응답") }

                    shouldThrow<IllegalArgumentException> { processor(client).process(food) }

                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.description shouldBe Food.PLACEHOLDER_DESCRIPTION
                    loaded.descriptionTranslations shouldBe emptyMap()
                }
            }
        }

        given("generateDescription — client 미구성") {
            `when`("설명이 필요한데 descriptionClient 가 없으면") {
                then("명시적 예외로 실패한다(조용한 영구 INCOMPLETE 방지)") {
                    val food = saveNeedingOnlyDescription("미구성-김밥")

                    shouldThrow<IllegalStateException> { processor(null).process(food) }
                }
            }
        }
    }
}
