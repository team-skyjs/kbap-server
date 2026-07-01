package com.meogo.infra.persistence.avoidance

import com.meogo.core.avoidance.AvoidanceSubstance
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
    private lateinit var mappingJpaRepository: IngredientAvoidanceSubstanceJpaRepository

    init {
        fun saveSubstance(substance: AvoidanceSubstance): Long =
            substanceJpaRepository.save(
                AvoidanceSubstanceJpaEntity(code = substance.name, koreanName = substance.koName),
            ).id

        fun saveRawSubstance(code: String, koreanName: String): Long =
            substanceJpaRepository.save(
                AvoidanceSubstanceJpaEntity(code = code, koreanName = koreanName),
            ).id

        fun saveMapping(ingredientId: Long, substanceId: Long): IngredientAvoidanceSubstanceJpaEntity =
            mappingJpaRepository.save(
                IngredientAvoidanceSubstanceJpaEntity(ingredientId = ingredientId, substanceId = substanceId),
            )

        given("재료 id 집합으로 매핑 성분 조회") {
            `when`("매핑된 재료들을 조회하면") {
                then("재료별 연결 성분 집합을 코드→enum 으로 정확히 반환한다") {
                    val peanutId = saveSubstance(AvoidanceSubstance.PEANUT)
                    val milkId = saveSubstance(AvoidanceSubstance.MILK)
                    saveMapping(ingredientId = 1001L, substanceId = peanutId)
                    saveMapping(ingredientId = 1002L, substanceId = milkId)

                    adapter.findByIngredientIds(setOf(1001L, 1002L)) shouldBe mapOf(
                        1001L to setOf(AvoidanceSubstance.PEANUT),
                        1002L to setOf(AvoidanceSubstance.MILK),
                    )
                }
            }

            `when`("매핑된 재료와 미매핑 재료를 함께 조회하면") {
                then("매핑된 재료만 반환하고 미매핑 재료 키는 생략한다") {
                    val eggId = saveSubstance(AvoidanceSubstance.EGG)
                    saveMapping(ingredientId = 2001L, substanceId = eggId)

                    val result = adapter.findByIngredientIds(setOf(2001L, 2002L))

                    result[2001L] shouldBe setOf(AvoidanceSubstance.EGG)
                    result shouldNotContainKey 2002L
                }
            }

            `when`("한 재료가 여러 성분에·한 성분이 여러 재료에 연결돼 있으면") {
                then("다대다 관계를 재료별 집합으로 모두 반영한다") {
                    val soyId = saveSubstance(AvoidanceSubstance.SOY)
                    val wheatId = saveSubstance(AvoidanceSubstance.WHEAT)
                    saveMapping(ingredientId = 3001L, substanceId = soyId)
                    saveMapping(ingredientId = 3001L, substanceId = wheatId)
                    saveMapping(ingredientId = 3002L, substanceId = soyId)

                    adapter.findByIngredientIds(setOf(3001L, 3002L)) shouldBe mapOf(
                        3001L to setOf(AvoidanceSubstance.SOY, AvoidanceSubstance.WHEAT),
                        3002L to setOf(AvoidanceSubstance.SOY),
                    )
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
                    val abaloneId = saveSubstance(AvoidanceSubstance.ABALONE)
                    val deletedMapping = saveMapping(ingredientId = 5001L, substanceId = abaloneId)
                    saveMapping(ingredientId = 5002L, substanceId = abaloneId)

                    deletedMapping.delete()
                    mappingJpaRepository.save(deletedMapping)

                    val result = adapter.findByIngredientIds(setOf(5001L, 5002L))

                    result[5002L] shouldBe setOf(AvoidanceSubstance.ABALONE)
                    result shouldNotContainKey 5001L
                }
            }
        }

        given("성분이 소프트삭제된 매핑") {
            `when`("재료가 소프트삭제된 성분 하나에만 매핑돼 있으면") {
                then("@SQLRestriction 으로 성분이 제외돼 그 재료 키가 생략되고 살아있는 형제 재료는 반환된다") {
                    val shrimp = substanceJpaRepository.save(
                        AvoidanceSubstanceJpaEntity(
                            code = AvoidanceSubstance.SHRIMP.name,
                            koreanName = AvoidanceSubstance.SHRIMP.koName,
                        ),
                    )
                    val crabId = saveSubstance(AvoidanceSubstance.CRAB)
                    saveMapping(ingredientId = 6001L, substanceId = shrimp.id)
                    saveMapping(ingredientId = 6002L, substanceId = crabId)

                    shrimp.delete()
                    substanceJpaRepository.save(shrimp)

                    val result = adapter.findByIngredientIds(setOf(6001L, 6002L))

                    result shouldNotContainKey 6001L
                    result[6002L] shouldBe setOf(AvoidanceSubstance.CRAB)
                }
            }
        }

        given("매핑된 성분 코드가 enum 과 비매칭") {
            `when`("재료가 enum 에 없는 코드의 성분에 매핑돼 있으면") {
                then("그 성분은 제외되고 같은 재료의 유효 성분만 반환하며 비매칭만 가진 재료 키는 생략된다") {
                    val unknownSubstanceId = saveRawSubstance(code = "NOT_A_REAL_CODE", koreanName = "가짜")
                    val tunaId = saveSubstance(AvoidanceSubstance.TUNA)
                    saveMapping(ingredientId = 7001L, substanceId = unknownSubstanceId)
                    saveMapping(ingredientId = 7001L, substanceId = tunaId)
                    saveMapping(ingredientId = 7002L, substanceId = unknownSubstanceId)

                    val result = adapter.findByIngredientIds(setOf(7001L, 7002L))

                    result[7001L] shouldBe setOf(AvoidanceSubstance.TUNA)
                    result shouldNotContainKey 7002L
                }
            }
        }
    }
}
