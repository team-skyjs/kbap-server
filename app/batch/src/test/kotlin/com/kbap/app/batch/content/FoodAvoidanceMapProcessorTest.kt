package com.kbap.app.batch.content

import com.kbap.app.batch.BatchTestClientConfig
import com.kbap.core.food.FoodAvoidanceAssessment
import com.kbap.core.food.FoodAvoidanceAssessmentClient
import com.kbap.core.food.FoodAvoidanceAssessmentResult
import com.kbap.core.lang.LanguageCode
import com.kbap.core.testsupport.MySqlContainerConfig
import com.kbap.domain.food.FoodJpaRepository
import com.kbap.domain.food.model.Food
import com.kbap.domain.food.model.FoodAvoidanceItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.PlatformTransactionManager

@SpringBootTest
@Import(MySqlContainerConfig::class, BatchTestClientConfig::class)
class FoodAvoidanceMapProcessorTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    init {
        val targets = LanguageCode.entries.filter { it != LanguageCode.KO }.associate { it.code to "t-${it.code}" }

        fun processor(client: FoodAvoidanceAssessmentClient, candidateCodes: Set<String>) =
            FoodContentItemProcessor(foodJpaRepository, transactionManager, client) { candidateCodes }

        fun saveNeedingOnlyAvoidance(name: String): Food {
            val food = foodJpaRepository.save(Food.incomplete(name))
            food.imageRef = "s3://img/$name.jpg"
            food.description = "맛있는 $name"
            food.nameTranslations = targets
            food.descriptionTranslations = targets
            foodJpaRepository.save(food)
            return foodJpaRepository.findById(food.id).get()
        }

        given("mapAvoidance — 조사 성공") {
            `when`("client 가 성분 목록을 반환하면") {
                then("성분이 반영·즉시 커밋되고(null→non-null) 음식명·후보 코드가 client 에 전달된다") {
                    val food = saveNeedingOnlyAvoidance("성공-김치찌개")
                    var capturedName: String? = null
                    var capturedCodes: Set<String>? = null
                    val client = FoodAvoidanceAssessmentClient { name, codes ->
                        capturedName = name
                        capturedCodes = codes
                        FoodAvoidanceAssessmentResult(listOf(FoodAvoidanceAssessment("EGG", 90), FoodAvoidanceAssessment("WHEAT", 100)), 3)
                    }

                    processor(client, setOf("EGG", "WHEAT")).process(food)

                    capturedName shouldBe "성공-김치찌개"
                    capturedCodes shouldBe setOf("EGG", "WHEAT")
                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.avoidanceSubstances.orEmpty().map { it.code } shouldContainExactlyInAnyOrder listOf("EGG", "WHEAT")
                    loaded.spiciness shouldBe 3
                    loaded.needsAvoidanceAssessment() shouldBe false
                }
            }
        }

        given("mapAvoidance — 성분 조사완료·맵기 미판정 재판정") {
            `when`("avoidanceSubstances 는 채워졌지만 spiciness 가 미판정(-1)이면") {
                then("client 를 호출해 성분·맵기를 함께 다시 반영한다") {
                    val food = saveNeedingOnlyAvoidance("맵기만-떡볶이")
                    food.avoidanceSubstances = listOf(FoodAvoidanceItem("WHEAT", 80))
                    foodJpaRepository.save(food)
                    val client = FoodAvoidanceAssessmentClient { _, _ ->
                        FoodAvoidanceAssessmentResult(listOf(FoodAvoidanceAssessment("WHEAT", 90)), 5)
                    }

                    processor(client, setOf("WHEAT")).process(foodJpaRepository.findById(food.id).get())

                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.spiciness shouldBe 5
                    loaded.avoidanceSubstances.orEmpty().map { it.code } shouldContainExactlyInAnyOrder listOf("WHEAT")
                }
            }
        }

        given("mapAvoidance — 포함률 0 폐기") {
            `when`("client 응답에 포함률 0(미포함 판단) 항목이 섞이면") {
                then("0 은 버리고 1..100 만 저장한다(RiskLevel 예외 방지)") {
                    val food = saveNeedingOnlyAvoidance("0필터-부대찌개")
                    val client = FoodAvoidanceAssessmentClient { _, _ ->
                        FoodAvoidanceAssessmentResult(listOf(FoodAvoidanceAssessment("EGG", 0), FoodAvoidanceAssessment("WHEAT", 80)), 3)
                    }

                    processor(client, setOf("EGG", "WHEAT")).process(food)

                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.avoidanceSubstances.orEmpty().map { it.code } shouldContainExactlyInAnyOrder listOf("WHEAT")
                    loaded.needsAvoidanceMapping() shouldBe false
                }
            }
        }

        given("mapAvoidance — 무성분 조사완료") {
            `when`("client 가 빈 목록을 반환하면") {
                then("빈 목록으로 조사완료 반영되어 재조사 대상이 아니다") {
                    val food = saveNeedingOnlyAvoidance("무성분-흰밥")
                    val client = FoodAvoidanceAssessmentClient { _, _ -> FoodAvoidanceAssessmentResult(emptyList(), 0) }

                    processor(client, setOf("EGG")).process(food)

                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.avoidanceSubstances shouldBe emptyList<FoodAvoidanceItem>()
                    loaded.needsAvoidanceMapping() shouldBe false
                }
            }
        }

        given("mapAvoidance — client 예외") {
            `when`("client 가 예외를 던지면") {
                then("예외가 process 밖으로 전파되고 avoidanceSubstances 는 null(미완) 로 남는다") {
                    val food = saveNeedingOnlyAvoidance("예외-마라탕")
                    val client = FoodAvoidanceAssessmentClient { _, _ -> throw RuntimeException("LLM 조사 실패") }

                    shouldThrow<RuntimeException> {
                        processor(client, setOf("EGG")).process(food)
                    }

                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.avoidanceSubstances shouldBe null
                    loaded.spiciness shouldBe Food.SPICINESS_UNASSESSED
                }
            }
        }

        given("mapAvoidance — 빈 카탈로그") {
            `when`("candidateCodes 가 비어 있으면") {
                then("client 를 호출하지 않고 avoidanceSubstances 는 null 로 남는다") {
                    val food = saveNeedingOnlyAvoidance("빈카탈로그-국밥")
                    var calls = 0
                    val client = FoodAvoidanceAssessmentClient { _, _ -> calls++; FoodAvoidanceAssessmentResult(emptyList(), 0) }

                    processor(client, emptySet()).process(food)

                    calls shouldBe 0
                    val loaded = foodJpaRepository.findById(food.id).get()
                    loaded.avoidanceSubstances shouldBe null
                    loaded.spiciness shouldBe Food.SPICINESS_UNASSESSED
                }
            }
        }

        given("mapAvoidance — 이미 조사완료(skip-if-done)") {
            `when`("avoidanceSubstances 와 spiciness 가 모두 채워져 있으면") {
                then("client 를 호출하지 않는다") {
                    val food = foodJpaRepository.save(Food.incomplete("완료-비빔밥"))
                    food.imageRef = "s3://img/done.jpg"
                    food.description = "맛있는 비빔밥"
                    food.nameTranslations = targets
                    food.descriptionTranslations = targets
                    food.avoidanceSubstances = listOf(FoodAvoidanceItem("SOYBEAN", 100))
                    food.spiciness = 2
                    foodJpaRepository.save(food)
                    var calls = 0
                    val client = FoodAvoidanceAssessmentClient { _, _ -> calls++; FoodAvoidanceAssessmentResult(emptyList(), 0) }

                    processor(client, setOf("SOYBEAN")).process(foodJpaRepository.findById(food.id).get())

                    calls shouldBe 0
                }
            }
        }
    }
}
