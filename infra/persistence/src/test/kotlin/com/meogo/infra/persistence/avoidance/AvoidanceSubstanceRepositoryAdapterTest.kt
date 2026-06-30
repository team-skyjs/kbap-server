package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceCategory
import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.string.shouldNotBeBlank
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class AvoidanceSubstanceRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: AvoidanceSubstanceRepositoryAdapter

    @Autowired
    private lateinit var substanceJpaRepository: AvoidanceSubstanceJpaRepository

    @Autowired
    private lateinit var categoryJpaRepository: AvoidanceSubstanceCategoryJpaRepository

    init {
        fun saveSubstance(
            substance: AvoidanceSubstance,
            nameZhHans: String? = null,
            nameEn: String? = null,
            nameJa: String? = null,
            nameZhHant: String? = null,
            nameVi: String? = null,
            nameId: String? = null,
            nameTh: String? = null,
            nameRu: String? = null,
            nameEs: String? = null,
        ): AvoidanceSubstanceJpaEntity =
            substanceJpaRepository.save(
                AvoidanceSubstanceJpaEntity(
                    code = substance.name,
                    koreanName = substance.koName,
                    nameZhHans = nameZhHans,
                    nameEn = nameEn,
                    nameJa = nameJa,
                    nameZhHant = nameZhHant,
                    nameVi = nameVi,
                    nameId = nameId,
                    nameTh = nameTh,
                    nameRu = nameRu,
                    nameEs = nameEs,
                ),
            )

        fun saveMembership(substanceId: Long, vararg categories: AvoidanceCategory) {
            categories.forEach { category ->
                categoryJpaRepository.save(
                    AvoidanceSubstanceCategoryJpaEntity(substanceId = substanceId, category = category),
                )
            }
        }

        given("코드 집합으로 성분 조회") {
            `when`("유효 코드와 무효 코드를 섞어 조회하면") {
                then("유효 코드만 enum 으로 반환하고 무효 코드는 제외한다") {
                    saveSubstance(AvoidanceSubstance.SHRIMP)
                    saveSubstance(AvoidanceSubstance.CRAB)

                    val found = adapter.findByCodes(setOf("SHRIMP", "CRAB", "NOT_A_REAL_CODE"))

                    found.shouldContainExactlyInAnyOrder(AvoidanceSubstance.SHRIMP, AvoidanceSubstance.CRAB)
                }
            }
        }

        given("분류별 성분 조회") {
            `when`("ALLERGEN 분류로 조회하면") {
                then("그 분류에 속한 성분(복수 분류 성분 포함)을 반환하고 비해당 성분은 제외한다") {
                    val squid = saveSubstance(AvoidanceSubstance.SQUID)
                    saveMembership(
                        squid.id,
                        AvoidanceCategory.ALLERGEN,
                        AvoidanceCategory.DIETARY_RULE,
                        AvoidanceCategory.PERSONAL_AVOIDANCE,
                    )

                    val almond = saveSubstance(AvoidanceSubstance.ALMOND)
                    saveMembership(almond.id, AvoidanceCategory.ALLERGEN)

                    val gelatin = saveSubstance(AvoidanceSubstance.GELATIN)
                    saveMembership(gelatin.id, AvoidanceCategory.DIETARY_RULE)

                    val found = adapter.byCategory(AvoidanceCategory.ALLERGEN)

                    found shouldContain AvoidanceSubstance.SQUID
                    found shouldContain AvoidanceSubstance.ALMOND
                    found shouldNotContain AvoidanceSubstance.GELATIN
                }
            }
        }

        given("다국어 이름 조회") {
            `when`("요청 언어 번역 컬럼에 값이 있으면") {
                then("그 언어 컬럼 값을 반환한다") {
                    saveSubstance(AvoidanceSubstance.PEANUT, nameEn = "Peanut")

                    adapter.translatedName(AvoidanceSubstance.PEANUT, LanguageCode.EN) shouldBe "Peanut"
                }
            }

            `when`("요청 언어가 KO 이면") {
                then("번역 컬럼이 없으므로 korean_name(=땅콩)을 그대로 반환한다") {
                    saveSubstance(AvoidanceSubstance.WALNUT, nameEn = "Walnut")

                    adapter.translatedName(AvoidanceSubstance.WALNUT, LanguageCode.KO) shouldBe "호두"
                }
            }

            `when`("요청 언어 번역 컬럼이 NULL 이면") {
                then("korean_name 으로 폴백하며 빈 문자열을 반환하지 않는다") {
                    saveSubstance(AvoidanceSubstance.CASHEW, nameEn = "Cashew nut", nameJa = null)

                    val name = adapter.translatedName(AvoidanceSubstance.CASHEW, LanguageCode.JA)

                    name shouldBe "캐슈넛"
                    name.shouldNotBeBlank()
                }
            }
        }

        given("소프트삭제된 성분") {
            `when`("성분을 소프트삭제한 뒤 코드·분류로 조회하면") {
                then("@SQLRestriction 으로 조회 결과에서 제외되고 살아있는 형제 성분은 그대로 조회된다") {
                    val pistachio = saveSubstance(AvoidanceSubstance.PISTACHIO)
                    saveMembership(pistachio.id, AvoidanceCategory.ALLERGEN)
                    val hazelnut = saveSubstance(AvoidanceSubstance.HAZELNUT)
                    saveMembership(hazelnut.id, AvoidanceCategory.ALLERGEN)

                    pistachio.delete()
                    substanceJpaRepository.save(pistachio)

                    adapter.findByCodes(setOf("PISTACHIO", "HAZELNUT")) shouldContainExactlyInAnyOrder
                        listOf(AvoidanceSubstance.HAZELNUT)

                    val allergens = adapter.byCategory(AvoidanceCategory.ALLERGEN)
                    allergens shouldContain AvoidanceSubstance.HAZELNUT
                    allergens shouldNotContain AvoidanceSubstance.PISTACHIO
                }
            }
        }
    }
}
