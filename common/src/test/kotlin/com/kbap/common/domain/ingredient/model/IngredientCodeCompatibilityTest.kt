package com.kbap.common.domain.ingredient.model

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe

class IngredientCodeCompatibilityTest : BehaviorSpec({
    given("운영 카탈로그에 후속 배포가 추가할 회피 코드") {
        val codes = setOf("NUTS", "CRUSTACEAN", "MOLLUSK", "MEAT", "MEAT_BROTH", "LAMB")

        `when`("구 서버가 문자열 enum 값으로 읽으면") {
            then("여섯 코드를 모두 해석한다") {
                codes.map(IngredientCode::valueOf).map { it.name }.toSet() shouldBe codes
            }
        }

        `when`("신규 묶음 코드의 회피 판정을 계산하면") {
            then("하위 성분을 빠뜨리지 않는다") {
                Avoidance(setOf(IngredientCode.valueOf("NUTS"))).codeNames shouldContainAll
                    setOf("PEANUT", "WALNUT", "ALMOND")
                Avoidance(setOf(IngredientCode.valueOf("CRUSTACEAN"))).codeNames shouldContainAll
                    setOf("SHRIMP", "SALTED_SHRIMP", "CRAB")
                Avoidance(setOf(IngredientCode.valueOf("MOLLUSK"))).codeNames shouldContainAll
                    setOf("SQUID", "OYSTER_SAUCE", "SCALLOP")
                Avoidance(setOf(IngredientCode.valueOf("MEAT"))).codeNames shouldContainAll
                    setOf("BEEF", "TALLOW", "PORK", "LARD", "CHICKEN", "LAMB", "MEAT_BROTH")
            }
        }
    }

    given("기존 회원이 고른 코드") {
        `when`("기존 회피 판정을 계산하면") {
            then("기존 코드의 함의 집합은 유지된다") {
                IngredientCode.valueOf("DAIRY").impliedCodes.map { it.name }.toSet() shouldBe
                    setOf("MILK", "GOAT_MILK", "BUTTER", "CHEESE", "GHEE")
                IngredientCode.valueOf("FISH").impliedCodes.map { it.name }.toSet() shouldBe
                    setOf("MACKEREL", "SALMON", "TUNA", "COD", "ANCHOVY", "FISH_SAUCE", "DASHI")
                IngredientCode.valueOf("SEAFOOD").impliedCodes.map { it.name }.toSet() shouldContainAll
                    setOf("FISH", "CRUSTACEAN", "MOLLUSK", "SHRIMP", "OYSTER_SAUCE")
            }
        }
    }
})
