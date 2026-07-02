package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceCategory
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest

@SpringBootTest
class IngredientAvoidanceSubstanceRepositoryAdapterTest : BehaviorSpec() {
    override fun extensions() = listOf(SpringExtension)

    @Autowired
    private lateinit var adapter: IngredientAvoidanceSubstanceRepositoryAdapter

    @Autowired
    private lateinit var substanceJpaRepository: AvoidanceSubstanceJpaRepository

    @Autowired
    private lateinit var categoryJpaRepository: AvoidanceSubstanceCategoryJpaRepository

    @Autowired
    private lateinit var mappingJpaRepository: IngredientAvoidanceSubstanceJpaRepository

    init {
        fun saveSubstance(
            code: AvoidanceSubstanceCode,
            koreanName: String,
            nameEn: String? = null,
        ): Long {
            val id = substanceJpaRepository.save(
                AvoidanceSubstanceJpaEntity(code = code.name, koreanName = koreanName, nameEn = nameEn),
            ).id
            categoryJpaRepository.save(
                AvoidanceSubstanceCategoryJpaEntity(substanceId = id, category = AvoidanceCategory.ALLERGEN.name),
            )
            return id
        }

        fun saveRawSubstance(code: String, koreanName: String): Long =
            substanceJpaRepository.save(
                AvoidanceSubstanceJpaEntity(code = code, koreanName = koreanName),
            ).id

        fun saveMapping(ingredientId: Long, substanceId: Long): IngredientAvoidanceSubstanceJpaEntity =
            mappingJpaRepository.save(
                IngredientAvoidanceSubstanceJpaEntity(ingredientId = ingredientId, substanceId = substanceId),
            )

        fun codesOf(result: Map<Long, Set<*>>, ingredientId: Long): Set<AvoidanceSubstanceCode> =
            (result[ingredientId] ?: emptySet<Any?>())
                .filterIsInstance<com.meogo.core.avoidance.AvoidanceSubstance>()
                .map { it.code }
                .toSet()

        given("재료 id 집합으로 매핑 성분 조회") {
            `when`("매핑된 재료들을 조회하면") {
                then("재료별 연결 성분을 코드→어그리게이트로 반환한다") {
                    val peanutId = saveSubstance(AvoidanceSubstanceCode.PEANUT, koreanName = "땅콩", nameEn = "Peanut")
                    val milkId = saveSubstance(AvoidanceSubstanceCode.MILK, koreanName = "우유")
                    saveMapping(ingredientId = 1001L, substanceId = peanutId)
                    saveMapping(ingredientId = 1002L, substanceId = milkId)

                    val result = adapter.findByIngredientIds(setOf(1001L, 1002L))

                    codesOf(result, 1001L) shouldBe setOf(AvoidanceSubstanceCode.PEANUT)
                    codesOf(result, 1002L) shouldBe setOf(AvoidanceSubstanceCode.MILK)
                }
            }

            `when`("반환된 어그리게이트의 표시명·분류를 확인하면") {
                then("어그리게이트가 displayName·belongsTo 를 스스로 답한다") {
                    val peanutId = saveSubstance(AvoidanceSubstanceCode.WHEAT, koreanName = "밀", nameEn = "Wheat")
                    saveMapping(ingredientId = 1101L, substanceId = peanutId)

                    val substance = adapter.findByIngredientIds(setOf(1101L)).getValue(1101L).single()

                    substance.displayName(LanguageCode.EN) shouldBe "Wheat"
                    substance.displayName(LanguageCode.KO) shouldBe "밀"
                    substance.belongsTo(AvoidanceCategory.ALLERGEN) shouldBe true
                }
            }

            `when`("매핑된 재료와 미매핑 재료를 함께 조회하면") {
                then("매핑된 재료만 반환하고 미매핑 재료 키는 생략한다") {
                    val eggId = saveSubstance(AvoidanceSubstanceCode.EGG, koreanName = "계란")
                    saveMapping(ingredientId = 2001L, substanceId = eggId)

                    val result = adapter.findByIngredientIds(setOf(2001L, 2002L))

                    codesOf(result, 2001L) shouldBe setOf(AvoidanceSubstanceCode.EGG)
                    result shouldNotContainKey 2002L
                }
            }

            `when`("한 재료가 여러 성분에·한 성분이 여러 재료에 연결돼 있으면") {
                then("다대다 관계를 재료별 집합으로 모두 반영한다") {
                    val soyId = saveSubstance(AvoidanceSubstanceCode.SOY, koreanName = "대두")
                    val cornId = saveSubstance(AvoidanceSubstanceCode.CORN, koreanName = "옥수수")
                    saveMapping(ingredientId = 3001L, substanceId = soyId)
                    saveMapping(ingredientId = 3001L, substanceId = cornId)
                    saveMapping(ingredientId = 3002L, substanceId = soyId)

                    val result = adapter.findByIngredientIds(setOf(3001L, 3002L))

                    codesOf(result, 3001L) shouldBe setOf(AvoidanceSubstanceCode.SOY, AvoidanceSubstanceCode.CORN)
                    codesOf(result, 3002L) shouldBe setOf(AvoidanceSubstanceCode.SOY)
                }
            }

            `when`("빈 재료 id 집합을 조회하면") {
                then("빈 맵을 반환한다") {
                    adapter.findByIngredientIds(emptySet()) shouldBe emptyMap()
                }
            }
        }

        given("소프트삭제된 매핑") {
            `when`("매핑을 소프트삭제한 뒤 재료로 조회하면") {
                then("@SQLRestriction 으로 제외되고 살아있는 형제 매핑은 그대로 반환된다") {
                    val abaloneId = saveSubstance(AvoidanceSubstanceCode.ABALONE, koreanName = "전복")
                    val deletedMapping = saveMapping(ingredientId = 5001L, substanceId = abaloneId)
                    saveMapping(ingredientId = 5002L, substanceId = abaloneId)

                    deletedMapping.delete()
                    mappingJpaRepository.save(deletedMapping)

                    val result = adapter.findByIngredientIds(setOf(5001L, 5002L))

                    codesOf(result, 5002L) shouldBe setOf(AvoidanceSubstanceCode.ABALONE)
                    result shouldNotContainKey 5001L
                }
            }
        }

        given("성분이 소프트삭제된 매핑") {
            `when`("재료가 소프트삭제된 성분 하나에만 매핑돼 있으면") {
                then("@SQLRestriction 으로 성분이 제외돼 그 재료 키가 생략되고 살아있는 형제 재료는 반환된다") {
                    val squidId = saveSubstance(AvoidanceSubstanceCode.SQUID, koreanName = "오징어")
                    val octopusId = saveSubstance(AvoidanceSubstanceCode.OCTOPUS, koreanName = "문어")
                    saveMapping(ingredientId = 6001L, substanceId = squidId)
                    saveMapping(ingredientId = 6002L, substanceId = octopusId)

                    val squid = substanceJpaRepository.findById(squidId).get()
                    squid.delete()
                    substanceJpaRepository.save(squid)

                    val result = adapter.findByIngredientIds(setOf(6001L, 6002L))

                    result shouldNotContainKey 6001L
                    codesOf(result, 6002L) shouldBe setOf(AvoidanceSubstanceCode.OCTOPUS)
                }
            }
        }

        given("매핑된 성분 코드가 식별자 enum 과 비매칭") {
            `when`("재료가 enum 에 없는 코드의 성분에 매핑돼 있으면") {
                then("그 성분은 제외되고 같은 재료의 유효 성분만 반환하며 비매칭만 가진 재료 키는 생략된다") {
                    val unknownSubstanceId = saveRawSubstance(code = "NOT_A_REAL_CODE", koreanName = "가짜")
                    val tunaId = saveSubstance(AvoidanceSubstanceCode.TUNA, koreanName = "참치")
                    saveMapping(ingredientId = 7001L, substanceId = unknownSubstanceId)
                    saveMapping(ingredientId = 7001L, substanceId = tunaId)
                    saveMapping(ingredientId = 7002L, substanceId = unknownSubstanceId)

                    val result = adapter.findByIngredientIds(setOf(7001L, 7002L))

                    codesOf(result, 7001L) shouldBe setOf(AvoidanceSubstanceCode.TUNA)
                    result shouldNotContainKey 7002L
                }
            }
        }
    }
}
