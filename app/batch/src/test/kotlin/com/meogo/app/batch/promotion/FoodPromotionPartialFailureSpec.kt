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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.boot.test.context.SpringBootTest

const val 저장이_실패하는_메뉴명 = "부분실패-저장불가-제육"

@TestConfiguration
class FailingFoodRepositoryConfig {

    @Bean
    @Primary
    fun failingFoodRepository(delegate: FoodRepositoryAdapter): FoodRepository =
        object : FoodRepository {
            override fun findByKoreanName(name: String): Food? = delegate.findByKoreanName(name)

            override fun save(food: Food): Food {
                if (food.content.name.korean == 저장이_실패하는_메뉴명) {
                    throw IllegalStateException("food 저장 중 일시적 오류가 발생했습니다: ${food.content.name.korean}")
                }
                return delegate.save(food)
            }
        }
}

@SpringBootTest
@Import(MySqlContainerConfig::class, FailingFoodRepositoryConfig::class)
class FoodPromotionPartialFailureSpec : BehaviorSpec() {
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

        val defaultSubstances = listOf(
            SubstanceMappingJson("EGG", 70),
            SubstanceMappingJson("MILK", 90),
        )

        fun seedCandidate(koreanName: String) {
            foodCandidateJpaRepository.save(
                FoodCandidateJpaEntity(
                    koreanName = koreanName,
                    description = "$koreanName 기본 설명",
                    descriptionTranslations = nineTranslations,
                    substanceMapping = defaultSubstances,
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

        given("완성 candidate 여러 개 중 하나가 food 저장 단계에서 실패하는 상황에서") {
            `when`("승격 배치를 실행하면") {
                then("저장에 성공하는 완성분은 정상 적재·승격된다") {
                    seedCandidate("부분실패-성공-비빔밥")
                    seedCandidate(저장이_실패하는_메뉴명)
                    seedCandidate("부분실패-성공-김밥")

                    promotionJob().run()

                    foodRepository.findByKoreanName("부분실패-성공-비빔밥").shouldNotBeNull()
                    foodRepository.findByKoreanName("부분실패-성공-김밥").shouldNotBeNull()
                    publishedFoodIdOf("부분실패-성공-비빔밥").shouldNotBeNull()
                    publishedFoodIdOf("부분실패-성공-김밥").shouldNotBeNull()
                }
            }

            `when`("승격 배치를 실행하면") {
                then("저장에 실패한 candidate 는 food 로 적재되지 않고 미승격으로 잔류한다") {
                    seedCandidate("부분실패-성공-비빔밥")
                    seedCandidate(저장이_실패하는_메뉴명)
                    seedCandidate("부분실패-성공-김밥")

                    promotionJob().run()

                    foodRepository.findByKoreanName(저장이_실패하는_메뉴명).shouldBeNull()
                    publishedFoodIdOf(저장이_실패하는_메뉴명).shouldBeNull()
                }
            }

            `when`("승격 배치를 실행하면") {
                then("저장에 실패한 candidate 는 다음 실행 대상(findPromotable)으로 남고 성공분은 대상에서 빠진다") {
                    seedCandidate("부분실패-성공-비빔밥")
                    seedCandidate(저장이_실패하는_메뉴명)
                    seedCandidate("부분실패-성공-김밥")

                    promotionJob().run()

                    val remaining = foodCandidateRepository.findPromotable(0, 100).map { it.koreanName }
                    remaining shouldContain 저장이_실패하는_메뉴명
                    remaining shouldNotContain "부분실패-성공-비빔밥"
                    remaining shouldNotContain "부분실패-성공-김밥"
                }
            }
        }
    }
}
