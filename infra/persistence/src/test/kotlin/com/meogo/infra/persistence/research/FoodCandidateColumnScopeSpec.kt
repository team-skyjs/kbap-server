package com.meogo.infra.persistence.research

import com.meogo.core.kernel.lang.LanguageCode
import com.meogo.core.research.candidate.FoodCandidate
import com.meogo.core.research.candidate.SubstanceSnapshot
import com.meogo.infra.persistence.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class FoodCandidateColumnScopeSpec : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: FoodCandidateRepositoryAdapter

    @Autowired
    private lateinit var foodCandidateJpaRepository: FoodCandidateJpaRepository

    init {
        val substanceA: List<SubstanceSnapshot> =
            listOf(SubstanceSnapshot("MILK", 30), SubstanceSnapshot("WHEAT", 70))

        val substanceAJson: List<SubstanceMappingJson> =
            listOf(SubstanceMappingJson("MILK", 30), SubstanceMappingJson("WHEAT", 70))

        val translations: Map<LanguageCode, String> =
            FoodCandidate.TARGET_LANGUAGES.associateWith { "${it.code}-설명" }

        val translationsJson: Map<String, String> =
            FoodCandidate.TARGET_LANGUAGES.associate { it.code to "${it.code}-설명" }

        fun seedCandidate(
            koreanName: String,
            description: String? = "된장으로 끓인 찌개",
            descriptionTranslations: Map<String, String> = emptyMap(),
            substanceMapping: List<SubstanceMappingJson> = emptyList(),
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

        given("FoodCandidate enrichment — 성분 갱신 후 번역 갱신") {
            `when`("updateSubstanceMapping 뒤에 updateDescriptionTranslations 를 호출하면") {
                then("성분 컬럼이 보존된 채 번역 컬럼이 채워진다") {
                    val id = seedCandidate("스코프-성분후번역")

                    adapter.updateSubstanceMapping(id, substanceA)
                    adapter.updateDescriptionTranslations(id, translations)

                    val reloaded = foodCandidateJpaRepository.findById(id).get()
                    reloaded.substanceMapping shouldContainExactly substanceAJson
                    reloaded.descriptionTranslations shouldBe translationsJson
                }
            }
        }

        given("FoodCandidate enrichment — 번역 갱신 후 성분 갱신") {
            `when`("updateDescriptionTranslations 뒤에 updateSubstanceMapping 을 호출하면") {
                then("번역 컬럼이 보존된 채 성분 컬럼이 채워진다") {
                    val id = seedCandidate("스코프-번역후성분")

                    adapter.updateDescriptionTranslations(id, translations)
                    adapter.updateSubstanceMapping(id, substanceA)

                    val reloaded = foodCandidateJpaRepository.findById(id).get()
                    reloaded.descriptionTranslations shouldBe translationsJson
                    reloaded.substanceMapping shouldContainExactly substanceAJson
                }
            }
        }

        given("FoodCandidate enrichment — 성분 갱신의 컬럼 스코프") {
            `when`("updateSubstanceMapping 을 호출하면") {
                then("description·published_food_id·description_translations 는 건드리지 않는다") {
                    val existingTranslations: Map<String, String> =
                        FoodCandidate.TARGET_LANGUAGES.associate { it.code to "기존-${it.code}" }
                    val id = seedCandidate(
                        koreanName = "스코프-무간섭",
                        description = "원본 설명",
                        descriptionTranslations = existingTranslations,
                        publishedFoodId = 999L,
                    )

                    adapter.updateSubstanceMapping(id, substanceA)

                    val reloaded = foodCandidateJpaRepository.findById(id).get()
                    reloaded.description shouldBe "원본 설명"
                    reloaded.publishedFoodId shouldBe 999L
                    reloaded.descriptionTranslations shouldBe existingTranslations
                    reloaded.substanceMapping shouldContainExactly substanceAJson
                }
            }
        }

        given("FoodCandidate enrichment — 재적용 멱등") {
            `when`("같은 성분·번역 값으로 각각 두 번 갱신하면") {
                then("결과 컬럼 값은 한 번 적용한 것과 같다") {
                    val id = seedCandidate("스코프-멱등")

                    adapter.updateSubstanceMapping(id, substanceA)
                    adapter.updateSubstanceMapping(id, substanceA)
                    adapter.updateDescriptionTranslations(id, translations)
                    adapter.updateDescriptionTranslations(id, translations)

                    val reloaded = foodCandidateJpaRepository.findById(id).get()
                    reloaded.substanceMapping shouldContainExactly substanceAJson
                    reloaded.descriptionTranslations shouldBe translationsJson
                }
            }
        }
    }
}
