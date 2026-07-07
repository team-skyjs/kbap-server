package com.meogo.infra.persistence.research

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodCandidateRepositoryAdapterSpec : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: FoodCandidateRepositoryAdapter

    @Autowired
    private lateinit var foodCandidateJpaRepository: FoodCandidateJpaRepository

    init {
        val allNineLanguages: Map<String, String> =
            (LanguageCode.entries - LanguageCode.KO).associate { it.code to "${it.code} 번역" }

        val eightLanguages: Map<String, String> =
            (LanguageCode.entries - LanguageCode.KO).drop(1).associate { it.code to "${it.code} 번역" }

        fun seedCandidate(
            koreanName: String,
            description: String? = "된장으로 끓인 찌개",
            descriptionTranslations: Map<String, String> = allNineLanguages,
            substanceMapping: List<SubstanceMappingJson> = listOf(SubstanceMappingJson("SOYBEAN", 100)),
            publishedFoodId: Long? = null,
        ): Long =
            foodCandidateJpaRepository.save(
                FoodCandidateJpaEntity(
                    koreanName = koreanName,
                    description = description,
                    descriptionTranslations = descriptionTranslations,
                    substanceMapping = substanceMapping,
                    publishedFoodId = publishedFoodId,
                ),
            ).id

        beforeEach {
            foodCandidateJpaRepository.deleteAllInBatch()
        }

        given("FoodCandidate 저장소 어댑터 — 중복 생성 무시(korean_name UNIQUE)") {
            `when`("같은 koreanName 으로 두 번 create 하면") {
                then("row 는 하나만 유지되고 두 번째 호출은 기존 candidate 를 반환한다") {
                    val first = adapter.create("중복무시-된장찌개", "된장으로 끓인 찌개")
                    val second = adapter.create("중복무시-된장찌개", "다른 설명")

                    second.id shouldBe first.id
                    foodCandidateJpaRepository.findAll()
                        .count { it.koreanName == "중복무시-된장찌개" } shouldBe 1
                }
            }
        }

        given("FoodCandidate 저장소 어댑터 — 승격 대상 필터(완성·미승격만)") {
            `when`("완성/미완성/이미 승격 후보가 섞여 있으면") {
                then("완성·미승격 후보만 id 오름차순으로 반환한다") {
                    val completeA = seedCandidate("필터-완성A")
                    val completeB = seedCandidate("필터-완성B")
                    seedCandidate("필터-번역8개", descriptionTranslations = eightLanguages)
                    seedCandidate("필터-성분0개", substanceMapping = emptyList())
                    seedCandidate("필터-설명없음", description = null)
                    seedCandidate("필터-이미승격", publishedFoodId = 4242L)

                    val promotable = adapter.findPromotable(0, 10)

                    promotable.map { it.id } shouldContainExactly listOf(completeA, completeB)
                    promotable.map { it.koreanName } shouldContainExactly listOf("필터-완성A", "필터-완성B")
                }
            }

            `when`("완성·미승격 후보가 페이지 크기보다 많으면") {
                then("id 오름차순 페이지 경계로 잘라 반환한다") {
                    val first = seedCandidate("페이지-완성1")
                    val second = seedCandidate("페이지-완성2")
                    seedCandidate("페이지-완성3")

                    val firstPage = adapter.findPromotable(0, 2)

                    firstPage.map { it.id } shouldContainExactly listOf(first, second)
                }
            }
        }

        given("FoodCandidate 저장소 어댑터 — 승격 마킹(published_food_id 링크)") {
            `when`("완성 후보를 markPublished 하면") {
                then("published_food_id 가 링크되고 이후 findPromotable 에서 제외된다") {
                    val candidateId = seedCandidate("마킹-완성후보")

                    adapter.markPublished(candidateId, 777L)

                    val reloaded = foodCandidateJpaRepository.findById(candidateId).get()
                    reloaded.publishedFoodId shouldBe 777L
                    adapter.findPromotable(0, 10).map { it.id } shouldNotContain candidateId
                }
            }
        }
    }
}
