package com.meogo.application.client.food.usecase

import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.AvoidanceSubstanceCode
import com.meogo.core.avoidance.IngredientAvoidanceSubstanceRepository
import com.meogo.core.kernel.lang.LanguageCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class FoodAvoidanceSubstanceResolverTest : BehaviorSpec({
    fun substance(
        code: AvoidanceSubstanceCode,
        koreanName: String,
        translations: Map<LanguageCode, String> = emptyMap(),
    ): AvoidanceSubstance =
        AvoidanceSubstance.reconstitute(
            id = code.ordinal.toLong() + 1,
            code = code,
            koreanName = koreanName,
            translations = translations,
        )

    fun resolverWith(mapping: Map<Long, Set<AvoidanceSubstance>>): FoodAvoidanceSubstanceResolver {
        val fakeRepository = object : IngredientAvoidanceSubstanceRepository {
            override fun findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>> =
                mapping.filterKeys { it in ingredientIds }
        }
        return FoodAvoidanceSubstanceResolver(fakeRepository)
    }

    val peanut = substance(AvoidanceSubstanceCode.PEANUT, "땅콩", mapOf(LanguageCode.EN to "Peanut"))
    val milk = substance(AvoidanceSubstanceCode.MILK, "우유")
    val egg = substance(AvoidanceSubstanceCode.EGG, "계란")
    val wheat = substance(AvoidanceSubstanceCode.WHEAT, "밀")

    given("음식 구성 재료의 회피·주의 성분 합집합 도출") {
        `when`("구성 재료들이 서로 다른 성분에 매핑돼 있으면") {
            then("모든 매핑 성분 어그리게이트의 합집합을 반환한다") {
                val resolver = resolverWith(
                    mapOf(
                        101L to setOf(peanut),
                        102L to setOf(milk, egg),
                    ),
                )

                resolver.resolve(setOf(101L, 102L)).map { it.code }.toSet() shouldBe
                    setOf(AvoidanceSubstanceCode.PEANUT, AvoidanceSubstanceCode.MILK, AvoidanceSubstanceCode.EGG)
            }
        }

        `when`("서로 다른 재료가 같은 code 의 서로 다른 인스턴스에 매핑돼 있으면") {
            then("code 기준으로 중복 제거되어 합집합 크기가 1이다") {
                val soyFromIngredientA = substance(AvoidanceSubstanceCode.SOY, "대두")
                val soyFromIngredientB = substance(AvoidanceSubstanceCode.SOY, "대두")

                val resolver = resolverWith(
                    mapOf(
                        501L to setOf(soyFromIngredientA),
                        502L to setOf(soyFromIngredientB),
                    ),
                )

                val resolved = resolver.resolve(setOf(501L, 502L))

                resolved shouldHaveSize 1
                resolved.single().code shouldBe AvoidanceSubstanceCode.SOY
            }
        }

        `when`("같은 code 의 다른 인스턴스와 다른 성분이 섞여 매핑돼 있으면") {
            then("같은 code 는 한 번만 남고 서로 다른 code 는 각각 남는다") {
                val soyFromIngredientA = substance(AvoidanceSubstanceCode.SOY, "대두")
                val soyFromIngredientB = substance(AvoidanceSubstanceCode.SOY, "대두")

                val resolver = resolverWith(
                    mapOf(
                        201L to setOf(soyFromIngredientA),
                        202L to setOf(soyFromIngredientB, wheat),
                    ),
                )

                val resolved = resolver.resolve(setOf(201L, 202L))

                resolved shouldHaveSize 2
                resolved.map { it.code }.toSet() shouldBe
                    setOf(AvoidanceSubstanceCode.SOY, AvoidanceSubstanceCode.WHEAT)
            }
        }

        `when`("반환된 어그리게이트의 표시명을 물으면") {
            then("어그리게이트가 언어별 표시명을 스스로 답한다") {
                val resolver = resolverWith(mapOf(401L to setOf(peanut)))

                val resolved = resolver.resolve(setOf(401L)).single()

                resolved.displayName(LanguageCode.EN) shouldBe "Peanut"
                resolved.displayName(LanguageCode.KO) shouldBe "땅콩"
            }
        }

        `when`("매핑된 재료가 하나도 없으면") {
            then("빈 집합을 반환한다") {
                val resolver = resolverWith(emptyMap())

                resolver.resolve(setOf(301L, 302L)) shouldBe emptySet()
            }
        }
    }
})
