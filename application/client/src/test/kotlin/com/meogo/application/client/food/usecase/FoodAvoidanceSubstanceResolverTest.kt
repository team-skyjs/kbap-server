package com.meogo.application.client.food.usecase

import com.meogo.core.avoidance.AvoidanceSubstance
import com.meogo.core.avoidance.IngredientAvoidanceSubstanceRepository
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

class FoodAvoidanceSubstanceResolverTest : BehaviorSpec({
    fun resolverWith(mapping: Map<Long, Set<AvoidanceSubstance>>): FoodAvoidanceSubstanceResolver {
        val fakeRepository = object : IngredientAvoidanceSubstanceRepository {
            override fun findByIngredientIds(ingredientIds: Set<Long>): Map<Long, Set<AvoidanceSubstance>> =
                mapping.filterKeys { it in ingredientIds }
        }
        return FoodAvoidanceSubstanceResolver(fakeRepository)
    }

    given("음식 구성 재료의 회피·주의 성분 합집합 도출") {
        `when`("구성 재료들이 서로 다른 성분에 매핑돼 있으면") {
            then("모든 매핑 성분의 합집합을 반환한다") {
                val resolver = resolverWith(
                    mapOf(
                        101L to setOf(AvoidanceSubstance.PEANUT),
                        102L to setOf(AvoidanceSubstance.MILK, AvoidanceSubstance.EGG),
                    ),
                )

                resolver.resolve(setOf(101L, 102L)) shouldBe
                    setOf(AvoidanceSubstance.PEANUT, AvoidanceSubstance.MILK, AvoidanceSubstance.EGG)
            }
        }

        `when`("여러 재료가 같은 성분에 매핑돼 있으면") {
            then("그 성분은 합집합에 한 번만 포함된다") {
                val resolver = resolverWith(
                    mapOf(
                        201L to setOf(AvoidanceSubstance.SOY),
                        202L to setOf(AvoidanceSubstance.SOY, AvoidanceSubstance.WHEAT),
                    ),
                )

                val resolved = resolver.resolve(setOf(201L, 202L))

                resolved shouldBe setOf(AvoidanceSubstance.SOY, AvoidanceSubstance.WHEAT)
                resolved shouldHaveSize 2
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
