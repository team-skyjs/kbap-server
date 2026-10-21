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
import com.kbap.common.domain.ingredient.model.IngredientCode.ALMOND
import com.kbap.common.domain.ingredient.model.IngredientCode.BRAZIL_NUT
import com.kbap.common.domain.ingredient.model.IngredientCode.CASHEW
import com.kbap.common.domain.ingredient.model.IngredientCode.CHESTNUT
import com.kbap.common.domain.ingredient.model.IngredientCode.CRUSTACEAN
import com.kbap.common.domain.ingredient.model.IngredientCode.GELATIN
import com.kbap.common.domain.ingredient.model.IngredientCode.HAZELNUT
import com.kbap.common.domain.ingredient.model.IngredientCode.LAMB
import com.kbap.common.domain.ingredient.model.IngredientCode.MACADAMIA
import com.kbap.common.domain.ingredient.model.IngredientCode.MEAT
import com.kbap.common.domain.ingredient.model.IngredientCode.MEAT_BROTH
import com.kbap.common.domain.ingredient.model.IngredientCode.MOLLUSK
import com.kbap.common.domain.ingredient.model.IngredientCode.NUTS
import com.kbap.common.domain.ingredient.model.IngredientCode.PEANUT
import com.kbap.common.domain.ingredient.model.IngredientCode.PECAN
import com.kbap.common.domain.ingredient.model.IngredientCode.PINE_NUT
import com.kbap.common.domain.ingredient.model.IngredientCode.PISTACHIO
import com.kbap.common.domain.ingredient.model.IngredientCode.WALNUT
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
                    SEAFOOD to seafoodLeaves + setOf(CRUSTACEAN, MOLLUSK),
                    BROTH to setOf(DASHI, FISH_SAUCE, MEAT_BROTH),
                    POULTRY to setOf(CHICKEN),
                    PORK to setOf(LARD, GELATIN),
                    BEEF to setOf(TALLOW, GELATIN),
                    ALCOHOL to setOf(MIRIN, COOKING_WINE),
                    NUTS to setOf(WALNUT, PINE_NUT, ALMOND, CASHEW, PISTACHIO, HAZELNUT, MACADAMIA, PECAN, BRAZIL_NUT, CHESTNUT, PEANUT),
                    CRUSTACEAN to setOf(SHRIMP, SALTED_SHRIMP, CRAB, CRAYFISH, LOBSTER),
                    MOLLUSK to setOf(SQUID, OCTOPUS, CLAM, SHORT_NECK_CLAM, OYSTER, OYSTER_SAUCE, ABALONE, MUSSEL, SCALLOP),
                    MEAT to setOf(BEEF, TALLOW, PORK, LARD, POULTRY, CHICKEN, LAMB, MEAT_BROTH, GELATIN),
                )
            }
        }

        `when`("KB-842 이전 표(R-100)와 비교하면") {
            then("모든 code 의 함의가 이전 것을 전부 포함한다 — 넓어지는 방향만") {
                val before = mapOf(
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
                before.filter { (code, implied) -> !code.impliedCodes.containsAll(implied) }.keys shouldBe emptySet()
            }
        }

        `when`("묶음 판정 표(KB-842)의 각 행을 보면") {
            then("묶음을 고른 회원의 판정 집합에 하위 재료가 들어 있다") {
                com.kbap.common.domain.ingredient.AvoidanceGroupMatrix.rows
                    .filterNot { it.member in Avoidance(setOf(it.group)).codes }
                    .map { "${it.group}→${it.member}" } shouldBe emptyList()
            }
            then("표가 각 묶음의 닫힘 집합을 빠짐없이 덮는다") {
                com.kbap.common.domain.ingredient.AvoidanceGroupMatrix.rows.groupBy({ it.group }, { it.member })
                    .mapValues { it.value.toSet() } shouldBe listOf(NUTS, CRUSTACEAN, MOLLUSK, MEAT).associateWith { it.impliedCodes }
            }
        }
        `when`("해산물을 새 묶음(갑각류·연체류)과 비교하면 — 공부 막음") {
            then("해산물이 두 묶음을 함의해 '갑각류'·'연체류' 표기도 걸리고, 이전 하위는 하나도 빠지지 않는다") {
                SEAFOOD.impliedCodes shouldBe seafoodLeaves + setOf(CRUSTACEAN, MOLLUSK)
                seafoodLeaves.containsAll(CRUSTACEAN.impliedCodes + MOLLUSK.impliedCodes) shouldBe true
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
                listOf(DAIRY, MILK, SEAFOOD, FISH, BROTH, POULTRY, ALCOHOL, NUTS, CRUSTACEAN, MOLLUSK, MEAT).filter { it.impliedCodes.isEmpty() } shouldBe emptyList()
            }
        }

        `when`("하위 재료 쪽에서 보면") {
            then("상위 카테고리를 함의하지 않는다 — 새우 회피가 해산물 전체로 번지지 않고, 자기 자신도 함의하지 않는다") {
                val topGroups = setOf(SEAFOOD, DAIRY, NUTS, MEAT)
                IngredientCode.entries.filter { it.impliedCodes.any { implied -> implied in topGroups } || it in it.impliedCodes } shouldBe emptyList()
                IngredientCode.entries.filter { it.impliedCodes.any { implied -> implied == CRUSTACEAN || implied == MOLLUSK } } shouldBe listOf(SEAFOOD)
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
