package com.meogo.app.batch.promotion

import com.meogo.core.food.Food
import com.meogo.core.food.FoodRepository
import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.research.candidate.FoodCandidateRepository
import com.meogo.infra.persistence.food.FoodJpaRepository
import com.meogo.infra.persistence.food.FoodRepositoryAdapter
import com.meogo.infra.persistence.research.FoodCandidateJpaEntity
import com.meogo.infra.persistence.research.FoodCandidateJpaRepository
import com.meogo.infra.persistence.research.SubstanceMappingJson
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary

const val 키셋_저장이_실패하는_메뉴명 = "키셋정체-하위id-저장불가-제육"

@TestConfiguration
class KeysetFailingFoodRepositoryConfig {

    @Bean
    @Primary
    fun keysetFailingFoodRepository(delegate: FoodRepositoryAdapter): FoodRepository =
        object : FoodRepository {
            override fun findByKoreanName(name: String): Food? = delegate.findByKoreanName(name)

            override fun save(food: Food): Food {
                if (food.content.name.korean == 키셋_저장이_실패하는_메뉴명) {
                    throw IllegalStateException("food 저장 중 일시적 오류가 발생했습니다: ${food.content.name.korean}")
                }
                return delegate.save(food)
            }
        }
}

@SpringBootTest
@Import(MySqlContainerConfig::class, KeysetFailingFoodRepositoryConfig::class)
class FoodPromotionKeysetProgressSpec : BehaviorSpec() {
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

        val eightSupportedPlusUnsupported: Map<String, String> =
            (LanguageCode.entries - LanguageCode.KO).drop(1).associate { it.code to "${it.code} 설명" } +
                ("xx" to "미지원 코드 설명")

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

        fun keysetChunkOneJob(): FoodPromotionJob =
            FoodPromotionJob(foodCandidateRepository, foodRepository, chunkSize = 1)

        fun defaultJob(): FoodPromotionJob =
            FoodPromotionJob(foodCandidateRepository, foodRepository)

        beforeEach {
            foodJpaRepository.deleteAll()
            foodCandidateJpaRepository.deleteAllInBatch()
        }

        given("하위 id 완성 후보는 food 저장이 실패하고 상위 id 완성 후보는 정상인 상황에서 chunkSize=1 로") {
            `when`("승격 배치를 실행하면") {
                then("상위 id 정상 후보가 커서 전진으로 조회·승격되고 published 링크된다") {
                    seedCandidate(키셋_저장이_실패하는_메뉴명)
                    seedCandidate("키셋정체-상위id-정상-비빔밥")

                    keysetChunkOneJob().run()

                    foodRepository.findByKoreanName("키셋정체-상위id-정상-비빔밥").shouldNotBeNull()
                    publishedFoodIdOf("키셋정체-상위id-정상-비빔밥").shouldNotBeNull()
                }
            }

            `when`("승격 배치를 실행하면") {
                then("저장에 실패한 하위 id 후보는 food 로 적재되지 않고 미승격으로 잔류한다") {
                    seedCandidate(키셋_저장이_실패하는_메뉴명)
                    seedCandidate("키셋정체-상위id-정상-비빔밥")

                    keysetChunkOneJob().run()

                    foodRepository.findByKoreanName(키셋_저장이_실패하는_메뉴명).shouldBeNull()
                    publishedFoodIdOf(키셋_저장이_실패하는_메뉴명).shouldBeNull()
                }
            }
        }

        given("SQL 선필터(json_length=9)는 통과하지만 미지원 언어 코드가 섞여 도메인 번역이 8개로 줄어드는 후보가 대기 중일 때") {
            `when`("승격 배치를 실행하면") {
                then("도메인 isComplete 재검증에서 걸러져 food 로 승격되지 않고 미승격으로 잔류한다") {
                    seedCandidate(
                        "재검증-미지원코드-국밥",
                        descriptionTranslations = eightSupportedPlusUnsupported,
                    )

                    defaultJob().run()

                    foodRepository.findByKoreanName("재검증-미지원코드-국밥").shouldBeNull()
                    publishedFoodIdOf("재검증-미지원코드-국밥").shouldBeNull()
                }
            }
        }
    }
}
