package com.kbap.common.domain.avoidance

import com.kbap.common.domain.avoidance.model.AvoidanceSubstance
import com.kbap.common.domain.avoidance.model.AvoidanceSubstanceCode
import com.kbap.common.core.lang.LanguageCode
import com.kbap.common.core.testsupport.MySqlContainerConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(MySqlContainerConfig::class)
class AvoidanceSubstanceRepositoryTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var substanceJpaRepository: AvoidanceSubstanceJpaRepository

    init {
        fun saveSubstance(
            code: AvoidanceSubstanceCode,
            koreanName: String,
            translations: Map<String, String> = emptyMap(),
        ): AvoidanceSubstance =
            substanceJpaRepository.save(
                AvoidanceSubstance(
                    code = code,
                    koreanName = koreanName,
                    translations = translations,
                ),
            )

        given("코드 조회 — 성분 복원") {
            `when`("성분을 코드로 조회하면") {
                then("그 성분이 정상 복원된다") {
                    saveSubstance(AvoidanceSubstanceCode.PEANUT, koreanName = "땅콩")

                    val found = substanceJpaRepository.findByCodeIn(setOf(AvoidanceSubstanceCode.PEANUT))

                    found.map { it.code } shouldContainExactlyInAnyOrder listOf(AvoidanceSubstanceCode.PEANUT)
                    found.single().displayName(LanguageCode.KO) shouldBe "땅콩"
                }
            }
        }

        given("translations JSON 컬럼 왕복") {
            `when`("여러 언어 번역을 translations 맵으로 저장하고 코드로 조회하면") {
                then("각 언어 displayName 이 저장한 번역과 같다") {
                    saveSubstance(
                        AvoidanceSubstanceCode.EGG,
                        koreanName = "달걀",
                        translations = mapOf(
                            "en" to "Egg",
                            "ja" to "卵",
                            "zh-Hans" to "鸡蛋",
                        ),
                    )

                    val found = substanceJpaRepository.findByCodeIn(setOf(AvoidanceSubstanceCode.EGG)).single()

                    found.displayName(LanguageCode.EN) shouldBe "Egg"
                    found.displayName(LanguageCode.JA) shouldBe "卵"
                    found.displayName(LanguageCode.ZH_HANS) shouldBe "鸡蛋"
                }
            }

            `when`("특정 언어 번역이 없는 성분을 그 언어로 조회하면") {
                then("displayName 이 korean_name 으로 폴백하며 빈 문자열을 반환하지 않는다") {
                    saveSubstance(
                        AvoidanceSubstanceCode.CASHEW,
                        koreanName = "캐슈넛",
                        translations = mapOf("en" to "Cashew nut"),
                    )

                    val name = substanceJpaRepository.findByCodeIn(setOf(AvoidanceSubstanceCode.CASHEW))
                        .single().displayName(LanguageCode.JA)

                    name shouldBe "캐슈넛"
                    name.shouldNotBeBlank()
                }
            }

            `when`("translations 가 빈 맵인 성분을 임의 비-ko 언어로 조회하면") {
                then("displayName 이 korean_name 으로 폴백한다") {
                    saveSubstance(
                        AvoidanceSubstanceCode.ALMOND,
                        koreanName = "아몬드",
                        translations = emptyMap(),
                    )

                    substanceJpaRepository.findByCodeIn(setOf(AvoidanceSubstanceCode.ALMOND))
                        .single().displayName(LanguageCode.EN) shouldBe "아몬드"
                }
            }

            `when`("유효 언어 키와 미인식 언어 키가 섞인 translations 를 저장하고 조회하면") {
                then("미인식 키는 언어 해석에서 무시되고 유효 언어만 반영된다") {
                    saveSubstance(
                        AvoidanceSubstanceCode.WHEAT,
                        koreanName = "밀",
                        translations = mapOf("en" to "Wheat", "xx" to "무시대상"),
                    )

                    val found = substanceJpaRepository.findByCodeIn(setOf(AvoidanceSubstanceCode.WHEAT)).single()

                    found.displayName(LanguageCode.EN) shouldBe "Wheat"
                    found.displayName(LanguageCode.JA) shouldBe "밀"
                }
            }
        }

        given("한국어명 데이터 출처 회귀 — Finding ①") {
            `when`("DB korean_name 을 코드 하드코딩 값과 다르게 저장하고 코드로 조회하면") {
                then("displayName(KO) 가 저장된 korean_name 을 반환한다") {
                    saveSubstance(AvoidanceSubstanceCode.WALNUT, koreanName = "호두-운영자수정")

                    val found = substanceJpaRepository.findByCodeIn(setOf(AvoidanceSubstanceCode.WALNUT))

                    found.single().displayName(LanguageCode.KO) shouldBe "호두-운영자수정"
                }
            }
        }

        given("코드 집합으로 성분 조회") {
            `when`("저장된 코드와 미저장 코드를 섞어 조회하면") {
                then("저장된 코드의 성분만 반환하고 미저장 코드는 제외한다") {
                    saveSubstance(AvoidanceSubstanceCode.SHRIMP, koreanName = "새우")
                    saveSubstance(AvoidanceSubstanceCode.CRAB, koreanName = "게")

                    val found = substanceJpaRepository.findByCodeIn(
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
        }

        given("소프트삭제된 성분") {
            `when`("성분을 소프트삭제한 뒤 코드로 조회하면") {
                then("@SQLRestriction 으로 제외되고 살아있는 형제 성분은 그대로 조회된다") {
                    saveSubstance(AvoidanceSubstanceCode.HAZELNUT, koreanName = "헤이즐넛")
                    val pecan = saveSubstance(AvoidanceSubstanceCode.PECAN, koreanName = "피칸")

                    pecan.delete()
                    substanceJpaRepository.save(pecan)

                    substanceJpaRepository.findByCodeIn(setOf(AvoidanceSubstanceCode.HAZELNUT, AvoidanceSubstanceCode.PECAN))
                        .map { it.code } shouldContainExactlyInAnyOrder listOf(AvoidanceSubstanceCode.HAZELNUT)
                }
            }
        }
    }
}
