package com.kbap.common.domain.ingredient.model

import com.kbap.common.domain.ingredient.model.IngredientCode.ALCOHOL
import com.kbap.common.domain.ingredient.model.IngredientCode.SQUID
import com.kbap.common.domain.ingredient.model.IngredientCode.SHORT_NECK_CLAM
import com.kbap.common.domain.ingredient.model.IngredientCode.SCALLOP
import com.kbap.common.domain.ingredient.model.IngredientCode.OCTOPUS
import com.kbap.common.domain.ingredient.model.IngredientCode.MUSSEL
import com.kbap.common.domain.ingredient.model.IngredientCode.LOBSTER
import com.kbap.common.domain.ingredient.model.IngredientCode.CRAYFISH
import com.kbap.common.domain.ingredient.model.IngredientCode.CRAB
import com.kbap.common.domain.ingredient.model.IngredientCode.CLAM
import com.kbap.common.domain.ingredient.model.IngredientCode.ABALONE
import com.kbap.common.domain.ingredient.model.IngredientCode.ANCHOVY
import com.kbap.common.domain.ingredient.model.IngredientCode.BEEF
import com.kbap.common.domain.ingredient.model.IngredientCode.BROTH
import com.kbap.common.domain.ingredient.model.IngredientCode.BUTTER
import com.kbap.common.domain.ingredient.model.IngredientCode.CHEESE
import com.kbap.common.domain.ingredient.model.IngredientCode.CHICKEN
import com.kbap.common.domain.ingredient.model.IngredientCode.COD
import com.kbap.common.domain.ingredient.model.IngredientCode.COOKING_WINE
import com.kbap.common.domain.ingredient.model.IngredientCode.DAIRY
import com.kbap.common.domain.ingredient.model.IngredientCode.DASHI
import com.kbap.common.domain.ingredient.model.IngredientCode.FISH
import com.kbap.common.domain.ingredient.model.IngredientCode.FISH_SAUCE
import com.kbap.common.domain.ingredient.model.IngredientCode.GHEE
import com.kbap.common.domain.ingredient.model.IngredientCode.GOAT_MILK
import com.kbap.common.domain.ingredient.model.IngredientCode.LARD
import com.kbap.common.domain.ingredient.model.IngredientCode.MACKEREL
import com.kbap.common.domain.ingredient.model.IngredientCode.MILK
import com.kbap.common.domain.ingredient.model.IngredientCode.MIRIN
import com.kbap.common.domain.ingredient.model.IngredientCode.OYSTER
import com.kbap.common.domain.ingredient.model.IngredientCode.OYSTER_SAUCE
import com.kbap.common.domain.ingredient.model.IngredientCode.PORK
import com.kbap.common.domain.ingredient.model.IngredientCode.POULTRY
import com.kbap.common.domain.ingredient.model.IngredientCode.SALMON
import com.kbap.common.domain.ingredient.model.IngredientCode.SALTED_SHRIMP
import com.kbap.common.domain.ingredient.model.IngredientCode.SEAFOOD
import com.kbap.common.domain.ingredient.model.IngredientCode.SHRIMP
import com.kbap.common.domain.ingredient.model.IngredientCode.TALLOW
import com.kbap.common.domain.ingredient.model.IngredientCode.TUNA
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

class AvoidanceTest : BehaviorSpec({
    given("회피 함의 표(R-100 — 카테고리 회피는 하위 재료를 전부 건다)") {
        val seafoodLeaves = setOf(
            FISH, MACKEREL, SALMON, TUNA, COD, ANCHOVY, FISH_SAUCE, DASHI, SHRIMP, SALTED_SHRIMP, CRAB, CRAYFISH, LOBSTER,
            SQUID, OCTOPUS, OYSTER, OYSTER_SAUCE, ABALONE, MUSSEL, CLAM, SHORT_NECK_CLAM, SCALLOP,
        )

        `when`("표 전체를 보면") {
            then("직접 쌍을 따라 끝까지 펼친(추이) 집합과 정확히 같다") {
                IngredientCode.entries.filter { it.impliedCodes.isNotEmpty() }.associateWith { it.impliedCodes } shouldBe mapOf(
                    MILK to setOf(GOAT_MILK, BUTTER, CHEESE, GHEE),
                    DAIRY to setOf(MILK, GOAT_MILK, BUTTER, CHEESE, GHEE),
                    FISH to setOf(MACKEREL, SALMON, TUNA, COD, ANCHOVY, FISH_SAUCE, DASHI),
                    ANCHOVY to setOf(FISH_SAUCE, DASHI),
                    SHRIMP to setOf(SALTED_SHRIMP),
                    OYSTER to setOf(OYSTER_SAUCE),
                    CLAM to setOf(SHORT_NECK_CLAM, OYSTER, OYSTER_SAUCE, ABALONE, MUSSEL, SCALLOP),
                    SEAFOOD to seafoodLeaves,
                    BROTH to setOf(DASHI, FISH_SAUCE),
                    POULTRY to setOf(CHICKEN),
                    PORK to setOf(LARD),
                    BEEF to setOf(TALLOW),
                    ALCOHOL to setOf(MIRIN, COOKING_WINE),
                )
            }
        }

        `when`("R-100 이전 표와 비교하면") {
            then("모든 code 의 함의가 이전 것을 전부 포함한다 — 회피에서 안전으로 바뀌는 판정 0(넓어지는 방향만)") {
                val before = mapOf(
                    MILK to setOf(GOAT_MILK, BUTTER, CHEESE, GHEE),
                    DAIRY to setOf(MILK, GOAT_MILK, BUTTER, CHEESE, GHEE),
                    FISH to setOf(MACKEREL, SALMON, TUNA, COD, ANCHOVY, FISH_SAUCE, DASHI),
                    ANCHOVY to setOf(FISH_SAUCE, DASHI),
                    SHRIMP to setOf(SALTED_SHRIMP),
                    OYSTER to setOf(OYSTER_SAUCE),
                    POULTRY to setOf(CHICKEN),
                    PORK to setOf(LARD),
                    BEEF to setOf(TALLOW),
                    ALCOHOL to setOf(MIRIN, COOKING_WINE),
                )
                before.filter { (code, implied) -> !code.impliedCodes.containsAll(implied) }.keys shouldBe emptySet()
            }
        }

        `when`("카테고리 성격 code 를 보면") {
            then("하위 재료가 하나 이상 있다") {
                listOf(DAIRY, MILK, SEAFOOD, FISH, BROTH, POULTRY, ALCOHOL).filter { it.impliedCodes.isEmpty() } shouldBe emptyList()
            }
        }

        `when`("하위 재료 쪽에서 보면") {
            then("상위 카테고리를 함의하지 않는다 — 새우 회피가 해산물 전체로 번지지 않고, 자기 자신도 함의하지 않는다") {
                IngredientCode.entries.filter { SEAFOOD in it.impliedCodes || DAIRY in it.impliedCodes || it in it.impliedCodes } shouldBe emptyList()
                SHRIMP.impliedCodes shouldBe setOf(SALTED_SHRIMP)
            }
        }
    }

    given("조개(조개류)를 회피하는 회원 — 법정 '조개류(굴·전복·홍합 포함)'") {
        `when`("판정용 회피 집합을 만들면") {
            then("바지락·굴·굴소스·전복·홍합·가리비가 함께 걸린다") {
                Avoidance(setOf(CLAM)).codes shouldBe setOf(CLAM, SHORT_NECK_CLAM, OYSTER, OYSTER_SAUCE, ABALONE, MUSSEL, SCALLOP)
            }
        }
    }

    given("새우를 회피하는 회원") {
        val avoidance = Avoidance(setOf(SHRIMP))

        `when`("판정용 회피 집합을 만들면") {
            then("새우젓이 함께 들어간다") {
                avoidance.codeNames shouldBe setOf("SHRIMP", "SALTED_SHRIMP")
            }
        }

        `when`("새우젓의 판정 근거를 물으면") {
            then("회원이 고른 새우를 가리킨다") {
                avoidance.matchedBy("SALTED_SHRIMP") shouldBe "SHRIMP"
            }
        }

        `when`("새우 자체의 판정 근거를 물으면") {
            then("직접 매치라 자기 자신이다") {
                avoidance.matchedBy("SHRIMP") shouldBe "SHRIMP"
            }
        }

        `when`("회피와 무관한 재료의 근거를 물으면") {
            then("null 이다") {
                avoidance.matchedBy("SOY").shouldBeNull()
            }
        }
    }

    given("새우젓만 회피하는 회원") {
        `when`("판정용 회피 집합을 만들면") {
            then("새우는 들어가지 않는다 — 역방향 함의 없음") {
                Avoidance(setOf(SALTED_SHRIMP)).codeNames shouldBe setOf("SALTED_SHRIMP")
            }
        }
    }

    given("어류와 멸치를 함께 회피하는 회원") {
        `when`("멸치액젓의 근거를 물으면") {
            then("직접 고른 code 중 표 순서가 앞선 것을 가리킨다") {
                Avoidance(setOf(ANCHOVY, FISH)).matchedBy("FISH_SAUCE") shouldBe
                    listOf(ANCHOVY, FISH).minBy { it.ordinal }.name
            }
        }
    }
})
