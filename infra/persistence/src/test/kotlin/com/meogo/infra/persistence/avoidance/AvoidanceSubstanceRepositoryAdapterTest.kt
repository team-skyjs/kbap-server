package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceCategory
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
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

    @Autowired
    private lateinit var entityManagerFactory: EntityManagerFactory

    init {
        fun saveSubstance(
            code: AvoidanceSubstanceCode,
            koreanName: String,
            translations: Map<String, String> = emptyMap(),
        ): AvoidanceSubstanceJpaEntity =
            substanceJpaRepository.save(
                AvoidanceSubstanceJpaEntity(
                    code = code.name,
                    koreanName = koreanName,
                    translations = translations,
                ),
            )

        fun saveMembership(substanceId: Long, vararg categories: AvoidanceCategory) {
            categories.forEach { category ->
                categoryJpaRepository.save(
                    AvoidanceSubstanceCategoryJpaEntity(substanceId = substanceId, category = category.name),
                )
            }
        }

        given("translations JSON 컬럼 왕복") {
            `when`("여러 언어 번역을 translations 맵으로 저장하고 코드로 조회하면") {
                then("각 언어 displayName 이 저장한 번역과 같다") {
                    val egg = saveSubstance(
                        AvoidanceSubstanceCode.EGG,
                        koreanName = "달걀",
                        translations = mapOf(
                            "en" to "Egg",
                            "ja" to "卵",
                            "zh-Hans" to "鸡蛋",
                        ),
                    )
                    saveMembership(egg.id, AvoidanceCategory.ALLERGEN)

                    val found = adapter.findByCodes(setOf(AvoidanceSubstanceCode.EGG)).single()

                    found.displayName(LanguageCode.EN) shouldBe "Egg"
                    found.displayName(LanguageCode.JA) shouldBe "卵"
                    found.displayName(LanguageCode.ZH_HANS) shouldBe "鸡蛋"
                }
            }

            `when`("특정 언어 번역이 없는 성분을 그 언어로 조회하면") {
                then("displayName 이 korean_name 으로 폴백하며 빈 문자열을 반환하지 않는다") {
                    val cashew = saveSubstance(
                        AvoidanceSubstanceCode.CASHEW,
                        koreanName = "캐슈넛",
                        translations = mapOf("en" to "Cashew nut"),
                    )
                    saveMembership(cashew.id, AvoidanceCategory.ALLERGEN)

                    val name = adapter.findByCodes(setOf(AvoidanceSubstanceCode.CASHEW))
                        .single().displayName(LanguageCode.JA)

                    name shouldBe "캐슈넛"
                    name.shouldNotBeBlank()
                }
            }

            `when`("translations 가 빈 맵인 성분을 임의 비-ko 언어로 조회하면") {
                then("displayName 이 korean_name 으로 폴백한다") {
                    val almond = saveSubstance(
                        AvoidanceSubstanceCode.ALMOND,
                        koreanName = "아몬드",
                        translations = emptyMap(),
                    )
                    saveMembership(almond.id, AvoidanceCategory.ALLERGEN)

                    adapter.findByCodes(setOf(AvoidanceSubstanceCode.ALMOND))
                        .single().displayName(LanguageCode.EN) shouldBe "아몬드"
                }
            }
        }

        given("한국어명 데이터 출처 회귀 — Finding ①") {
            `when`("DB korean_name 을 코드 하드코딩 값과 다르게 저장하고 코드로 조회하면") {
                then("displayName(KO) 가 저장된 korean_name 을 반환한다") {
                    val walnut = saveSubstance(AvoidanceSubstanceCode.WALNUT, koreanName = "호두-운영자수정")
                    saveMembership(walnut.id, AvoidanceCategory.ALLERGEN)

                    val found = adapter.findByCodes(setOf(AvoidanceSubstanceCode.WALNUT))

                    found.single().displayName(LanguageCode.KO) shouldBe "호두-운영자수정"
                }
            }

            `when`("DB korean_name 을 다르게 저장하고 분류로 조회하면") {
                then("displayName(KO) 가 저장된 korean_name 을 반환한다") {
                    val pistachio = saveSubstance(AvoidanceSubstanceCode.PISTACHIO, koreanName = "피스타치오-운영자수정")
                    saveMembership(pistachio.id, AvoidanceCategory.ALLERGEN)

                    val found = adapter.byCategory(AvoidanceCategory.ALLERGEN)
                        .first { it.code == AvoidanceSubstanceCode.PISTACHIO }

                    found.displayName(LanguageCode.KO) shouldBe "피스타치오-운영자수정"
                }
            }
        }

        given("코드 집합으로 성분 조회") {
            `when`("저장된 코드와 미저장 코드를 섞어 조회하면") {
                then("저장된 코드의 어그리게이트만 반환하고 미저장 코드는 제외한다") {
                    val shrimp = saveSubstance(AvoidanceSubstanceCode.SHRIMP, koreanName = "새우")
                    saveMembership(shrimp.id, AvoidanceCategory.ALLERGEN)
                    val crab = saveSubstance(AvoidanceSubstanceCode.CRAB, koreanName = "게")
                    saveMembership(crab.id, AvoidanceCategory.ALLERGEN)

                    val found = adapter.findByCodes(
                        setOf(
                            AvoidanceSubstanceCode.SHRIMP,
                            AvoidanceSubstanceCode.CRAB,
                            AvoidanceSubstanceCode.LOBSTER,
                        ),
                    )

                    found.map { it.code } shouldContainExactlyInAnyOrder listOf(
                        AvoidanceSubstanceCode.SHRIMP,
                        AvoidanceSubstanceCode.CRAB,
                    )
                }
            }

            `when`("복수 분류 성분을 조회하면") {
                then("어그리게이트가 분류 집합을 담고 belongsTo 로 답한다") {
                    val squid = saveSubstance(AvoidanceSubstanceCode.SQUID, koreanName = "오징어")
                    saveMembership(
                        squid.id,
                        AvoidanceCategory.ALLERGEN,
                        AvoidanceCategory.DIETARY_RULE,
                        AvoidanceCategory.PERSONAL_AVOIDANCE,
                    )

                    val found = adapter.findByCodes(setOf(AvoidanceSubstanceCode.SQUID)).single()

                    found.categories shouldContainExactlyInAnyOrder listOf(
                        AvoidanceCategory.ALLERGEN,
                        AvoidanceCategory.DIETARY_RULE,
                        AvoidanceCategory.PERSONAL_AVOIDANCE,
                    )
                    found.belongsTo(AvoidanceCategory.PERSONAL_AVOIDANCE) shouldBe true
                }
            }
        }

        given("분류별 성분 조회") {
            `when`("ALLERGEN 분류로 조회하면") {
                then("그 분류에 속한 성분 어그리게이트를 반환하고 비해당 성분은 제외한다") {
                    val mackerel = saveSubstance(AvoidanceSubstanceCode.MACKEREL, koreanName = "고등어")
                    saveMembership(
                        mackerel.id,
                        AvoidanceCategory.ALLERGEN,
                        AvoidanceCategory.DIETARY_RULE,
                    )
                    val gelatin = saveSubstance(AvoidanceSubstanceCode.GELATIN, koreanName = "젤라틴")
                    saveMembership(gelatin.id, AvoidanceCategory.DIETARY_RULE)

                    val codes = adapter.byCategory(AvoidanceCategory.ALLERGEN).map { it.code }

                    codes shouldContain AvoidanceSubstanceCode.MACKEREL
                    codes shouldNotContain AvoidanceSubstanceCode.GELATIN
                }
            }
        }

        given("소프트삭제된 성분") {
            `when`("성분을 소프트삭제한 뒤 코드·분류로 조회하면") {
                then("@SQLRestriction 으로 제외되고 살아있는 형제 성분은 그대로 조회된다") {
                    val hazelnut = saveSubstance(AvoidanceSubstanceCode.HAZELNUT, koreanName = "헤이즐넛")
                    saveMembership(hazelnut.id, AvoidanceCategory.ALLERGEN)
                    val pecan = saveSubstance(AvoidanceSubstanceCode.PECAN, koreanName = "피칸")
                    saveMembership(pecan.id, AvoidanceCategory.ALLERGEN)

                    pecan.delete()
                    substanceJpaRepository.save(pecan)

                    adapter.findByCodes(setOf(AvoidanceSubstanceCode.HAZELNUT, AvoidanceSubstanceCode.PECAN))
                        .map { it.code } shouldContainExactlyInAnyOrder listOf(AvoidanceSubstanceCode.HAZELNUT)
                }
            }
        }

        given("N+1 없음 — 조회 쿼리 수가 성분 수와 무관") {
            `when`("성분 수가 다른 두 분류 조회의 쿼리 수를 비교하면") {
                then("쿼리 수가 성분 개수에 비례하지 않고 동일하다") {
                    val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
                    statistics.isStatisticsEnabled = true

                    val small = saveSubstance(AvoidanceSubstanceCode.BEEF, koreanName = "소고기")
                    saveMembership(small.id, AvoidanceCategory.PERSONAL_AVOIDANCE)

                    statistics.clear()
                    adapter.byCategory(AvoidanceCategory.PERSONAL_AVOIDANCE)
                    val queriesWithFewer = statistics.prepareStatementCount

                    listOf(
                        AvoidanceSubstanceCode.PORK to "돼지고기",
                        AvoidanceSubstanceCode.CHICKEN to "닭고기",
                        AvoidanceSubstanceCode.SEAFOOD to "해산물",
                    ).forEach { (code, korean) ->
                        val entity = saveSubstance(code, koreanName = korean)
                        saveMembership(entity.id, AvoidanceCategory.PERSONAL_AVOIDANCE)
                    }

                    statistics.clear()
                    adapter.byCategory(AvoidanceCategory.PERSONAL_AVOIDANCE)
                    val queriesWithMore = statistics.prepareStatementCount

                    queriesWithMore shouldBe queriesWithFewer
                }
            }
        }
    }
}
