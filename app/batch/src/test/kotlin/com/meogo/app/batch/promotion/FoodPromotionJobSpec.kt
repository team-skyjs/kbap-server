package com.meogo.app.batch.promotion

import com.meogo.core.food.AvoidanceSubstanceCodeRef
import com.meogo.core.food.Food
import com.meogo.core.food.FoodAvoidanceSubstance
import com.meogo.core.food.FoodContent
import com.meogo.core.food.FoodRepository
import com.meogo.core.food.FoodSpiciness
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.kernel.lang.LocalizedText
import com.meogo.core.research.candidate.FoodCandidateRepository
import com.meogo.infra.persistence.food.FoodJpaRepository
import com.meogo.infra.persistence.research.FoodCandidateJpaEntity
import com.meogo.infra.persistence.research.FoodCandidateJpaRepository
import com.meogo.infra.persistence.research.SubstanceMappingJson
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodPromotionJobSpec : BehaviorSpec() {
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
            description: String? = "$koreanName 기본 설명",
            descriptionTranslations: Map<String, String> = nineTranslations,
            substanceMapping: List<SubstanceMappingJson> = defaultSubstances,
        ) {
            foodCandidateJpaRepository.save(
                FoodCandidateJpaEntity(
                    koreanName = koreanName,
                    description = description,
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

        given("성분·ko설명·9언어 번역을 모두 갖춘 완성 candidate 가 대기 중일 때") {
            `when`("승격 배치를 실행하면") {
                then("해당 메뉴명이 회피 성분과 함께 food 로 적재되고 candidate 는 published_food_id 로 링크된다") {
                    seedCandidate(
                        "승격-비빔밥",
                        substanceMapping = listOf(
                            SubstanceMappingJson("EGG", 70),
                            SubstanceMappingJson("MILK", 90),
                        ),
                    )

                    promotionJob().run()

                    val food = foodRepository.findByKoreanName("승격-비빔밥").shouldNotBeNull()
                    food.id.shouldNotBeNull()
                    food.avoidanceSubstances.map { it.substanceCode.value }
                        .shouldContainExactlyInAnyOrder("EGG", "MILK")
                    publishedFoodIdOf("승격-비빔밥") shouldBe food.id
                }
            }
        }

        given("성분은 있으나 설명 번역이 8개뿐인 미완성 candidate 가 대기 중일 때") {
            `when`("승격 배치를 실행하면") {
                then("food 에 적재되지 않고 candidate 는 미승격(published_food_id null)으로 잔류한다") {
                    seedCandidate("잔류-된장국", descriptionTranslations = eightTranslations)

                    promotionJob().run()

                    foodRepository.findByKoreanName("잔류-된장국").shouldBeNull()
                    publishedFoodIdOf("잔류-된장국").shouldBeNull()
                }
            }
        }

        given("같은 korean_name 의 food 가 이미 존재하고, 다른 성분·설명의 완성 candidate 가 대기 중일 때") {
            `when`("승격 배치를 실행하면") {
                then("candidate 는 기존 food.id 로 링크되고 기존 food 는 candidate 값으로 덮이지 않는다") {
                    val existing = foodRepository.save(
                        Food.create(
                            content = FoodContent(
                                name = LocalizedText(korean = "중복-소불고기", translations = emptyMap()),
                                description = LocalizedText(korean = "기존 소불고기 설명", translations = emptyMap()),
                            ),
                            imageRef = "existing.png",
                            spiciness = FoodSpiciness(3),
                            avoidanceSubstances = listOf(
                                FoodAvoidanceSubstance(
                                    substanceCode = AvoidanceSubstanceCodeRef("EGG"),
                                    inclusionProbability = 70,
                                ),
                                FoodAvoidanceSubstance(
                                    substanceCode = AvoidanceSubstanceCodeRef("MILK"),
                                    inclusionProbability = 90,
                                ),
                            ),
                        ),
                    )

                    seedCandidate(
                        "중복-소불고기",
                        description = "땅콩 범벅 설명",
                        substanceMapping = listOf(SubstanceMappingJson("PEANUT", 55)),
                    )

                    promotionJob().run()

                    publishedFoodIdOf("중복-소불고기") shouldBe existing.id

                    val reloaded = foodRepository.findByKoreanName("중복-소불고기").shouldNotBeNull()
                    reloaded.id shouldBe existing.id
                    reloaded.content.description.korean shouldBe "기존 소불고기 설명"
                    reloaded.spiciness.value shouldBe 3
                    reloaded.avoidanceSubstances.map { it.substanceCode.value }
                        .shouldContainExactlyInAnyOrder("EGG", "MILK")
                }
            }
        }

        given("완성 candidate 와 미완성(성분 0개) candidate 가 섞여 대기 중일 때") {
            `when`("승격 배치를 실행하면") {
                then("완성분만 food 로 적재·승격되고 미완성은 잔류한다") {
                    seedCandidate("혼합-완성-김밥")
                    seedCandidate("혼합-미완성-순대", substanceMapping = emptyList())

                    promotionJob().run()

                    foodRepository.findByKoreanName("혼합-완성-김밥").shouldNotBeNull()
                    foodRepository.findByKoreanName("혼합-미완성-순대").shouldBeNull()
                    publishedFoodIdOf("혼합-완성-김밥").shouldNotBeNull()
                    publishedFoodIdOf("혼합-미완성-순대").shouldBeNull()
                }
            }
        }
    }
}
