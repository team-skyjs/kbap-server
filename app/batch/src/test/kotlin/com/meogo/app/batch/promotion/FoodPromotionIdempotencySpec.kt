package com.meogo.app.batch.promotion

import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.research.candidate.FoodCandidateRepository
import com.meogo.infra.persistence.food.FoodJpaRepository
import com.meogo.infra.persistence.research.FoodCandidateJpaEntity
import com.meogo.infra.persistence.research.FoodCandidateJpaRepository
import com.meogo.infra.persistence.research.SubstanceMappingJson
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodPromotionIdempotencySpec : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var foodCandidateRepository: FoodCandidateRepository

    @Autowired
    private lateinit var foodRepository: FoodRepository

    @Autowired
    private lateinit var foodCandidateJpaRepository: FoodCandidateJpaRepository

    @Autowired
    private lateinit var foodJpaRepository: FoodJpaRepository

    init {
        val nineTranslations: Map<String, String> =
            (LanguageCode.entries - LanguageCode.KO).associate { it.code to "${it.code} 설명" }

        val eightTranslations: Map<String, String> =
            (LanguageCode.entries - LanguageCode.KO).drop(1).associate { it.code to "${it.code} 설명" }

        val defaultSubstances = listOf(
            SubstanceMappingJson("EGG", 70),
            SubstanceMappingJson("MILK", 90),
        )

        fun seedCandidate(
            koreanName: String,
            descriptionTranslations: Map<String, String> = nineTranslations,
            substanceMapping: List<SubstanceMappingJson> = defaultSubstances,
        ) {
            foodCandidateJpaRepository.save(
                FoodCandidateJpaEntity(
                    koreanName = koreanName,
                    description = "$koreanName 기본 설명",
                    descriptionTranslations = descriptionTranslations,
                    substanceMapping = substanceMapping,
                    publishedFoodId = null,
                ),
            )
        }

        fun publishedFoodIdOf(koreanName: String): Long? =
            foodCandidateJpaRepository.findByKoreanName(koreanName)?.publishedFoodId

        fun promotionJob(): FoodPromotionJob = FoodPromotionJob(foodCandidateRepository, foodRepository)

        beforeEach {
            foodJpaRepository.deleteAll()
            foodCandidateJpaRepository.deleteAllInBatch()
        }

        given("완성·미완성 candidate 가 섞여 있고 승격 배치를 이미 한 번 실행해 완성분이 published 된 상태에서") {
            `when`("승격 배치를 다시 실행하면") {
                then("완성분 food 는 같은 id 로 단 하나만 유지되고 재적재·중복 적재가 발생하지 않는다") {
                    seedCandidate("멱등-비빔밥")
                    seedCandidate("멱등-미완성-국밥", descriptionTranslations = eightTranslations)

                    promotionJob().run()

                    val firstFoodId = foodRepository.findByKoreanName("멱등-비빔밥").shouldNotBeNull().id
                    firstFoodId.shouldNotBeNull()
                    publishedFoodIdOf("멱등-비빔밥") shouldBe firstFoodId

                    promotionJob().run()

                    val secondFood = foodRepository.findByKoreanName("멱등-비빔밥").shouldNotBeNull()
                    secondFood.id shouldBe firstFoodId
                    publishedFoodIdOf("멱등-비빔밥") shouldBe firstFoodId
                }
            }

            `when`("승격 배치를 다시 실행하면") {
                then("미완성분은 두 번의 실행 후에도 food 로 적재되지 않고 미승격으로 잔류한다") {
                    seedCandidate("멱등-비빔밥")
                    seedCandidate("멱등-미완성-국밥", descriptionTranslations = eightTranslations)

                    promotionJob().run()
                    promotionJob().run()

                    foodRepository.findByKoreanName("멱등-미완성-국밥").shouldBeNull()
                    publishedFoodIdOf("멱등-미완성-국밥").shouldBeNull()
                }
            }

            `when`("승격 배치를 다시 실행하면") {
                then("승격 대상(findPromotable)에는 더 이상 완성 미승격 candidate 가 남지 않는다") {
                    seedCandidate("멱등-비빔밥")
                    seedCandidate("멱등-미완성-국밥", descriptionTranslations = eightTranslations)

                    promotionJob().run()
                    promotionJob().run()

                    val remaining = foodCandidateRepository.findPromotable(0, 100).map { it.koreanName }
                    remaining shouldBe emptyList()
                }
            }
        }
    }
}
