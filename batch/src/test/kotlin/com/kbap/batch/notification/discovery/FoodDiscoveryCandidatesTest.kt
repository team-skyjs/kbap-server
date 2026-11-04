package com.kbap.batch.notification.discovery

import com.kbap.common.domain.BaseEntity
import com.kbap.common.domain.LanguageCode
import com.kbap.common.domain.food.model.Food
import com.kbap.common.domain.food.model.FoodContentStatus
import com.kbap.common.domain.food.model.FoodIngredient
import com.kbap.common.domain.food.model.RiskLevel
import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.random.Random

private val ALL_LANG_TRANSLATIONS = LanguageCode.entries.filter { it != LanguageCode.KO }.associate { it.code to "desc-${it.code}" }

private fun food(
    id: Long,
    vararg ingredients: Pair<IngredientCode, Int>,
    translations: Map<String, String> = ALL_LANG_TRANSLATIONS,
    description: String = "설명-$id",
    status: FoodContentStatus = FoodContentStatus.READY,
    assessed: Boolean = true,
): Food {
    val food = Food(
        koreanName = "음식$id",
        description = description,
        descriptionTranslations = translations,
        contentStatus = status,
        ingredients = if (assessed) ingredients.map { (code, percent) -> FoodIngredient(code.name, percent) } else null,
    )
    BaseEntity::class.java.getDeclaredField("id").apply { isAccessible = true }.set(food, id)
    return food
}

private fun codes(vararg chosen: IngredientCode): Set<String> = Avoidance(chosen.toSet()).codeNames

class FoodDiscoveryCandidatesTest : BehaviorSpec({
    val ko = setOf(LanguageCode.KO)

    given("회피 재료 판정") {
        `when`("돼지고기 회피 회원에게 돼지고기가 1% 든 음식과 안전한 음식이 있으면") {
            val porkTrace = food(1, IngredientCode.PORK to 1, IngredientCode.WHEAT to 90)
            val safe = food(2, IngredientCode.WHEAT to 90)
            val candidates = FoodDiscoveryCandidates(listOf(porkTrace, safe))

            then("위험도 집계로는 SAFE 인 음식이지만 후보에서 빠지고 어떤 시드로도 선택되지 않는다") {
                porkTrace.overallRisk(codes(IngredientCode.PORK)) shouldBe RiskLevel.SAFE
                candidates.safeFor(codes(IngredientCode.PORK)).map { it.id } shouldBe listOf(2L)
                (0 until 200).forEach { seed ->
                    candidates.pick(codes(IngredientCode.PORK), ko, emptySet(), Random(seed))?.id shouldBe 2L
                }
            }
        }

        `when`("상위 묶음(육류)을 고른 회원에게 하위 재료(라드)만 든 음식이 있으면") {
            val lardOnly = food(1, IngredientCode.LARD to 5)
            val candidates = FoodDiscoveryCandidates(listOf(lardOnly))

            then("묶음 확장으로 겹쳐 선택되지 않는다") {
                candidates.pick(codes(IngredientCode.MEAT), ko, emptySet(), Random(0)) shouldBe null
                candidates.pick(emptySet(), ko, emptySet(), Random(0))?.id shouldBe 1L
            }
        }
    }

    given("풀 적재") {
        `when`("READY 가 아니거나 재료 미평가 음식이 섞여 있으면") {
            val candidates = FoodDiscoveryCandidates(
                listOf(
                    food(1, IngredientCode.WHEAT to 90),
                    food(2, IngredientCode.WHEAT to 90, status = FoodContentStatus.PENDING_REVIEW),
                    food(3, assessed = false),
                ),
            )

            then("READY·평가 완료 음식만 남는다") {
                candidates.ids shouldBe setOf(1L)
            }
        }
    }

    given("기기 언어별 설명 번역") {
        val noThai = food(1, translations = ALL_LANG_TRANSLATIONS - "th")
        val onlyEn = food(2, translations = mapOf("en" to "desc-en"))
        val blankKo = food(3, description = " ")
        val candidates = FoodDiscoveryCandidates(listOf(noThai, onlyEn, blankKo))

        `when`("태국어 기기 회원이면") {
            then("태국어 설명이 없는 음식은 빠진다") {
                (0 until 50).map { candidates.pick(emptySet(), setOf(LanguageCode.TH), emptySet(), Random(it))?.id }.toSet() shouldBe setOf(3L)
            }
        }

        `when`("한국어 기기 회원이면") {
            then("원문 설명이 비어 있지 않은 음식만 후보이고 문구는 원문 설명이다") {
                val picks = (0 until 50).mapNotNull { candidates.pick(emptySet(), ko, emptySet(), Random(it)) }
                picks.map { it.id }.toSet() shouldBe setOf(1L, 2L)
                picks.first { it.id == 1L }.text.getValue(LanguageCode.KO).description shouldBe "설명-1"
            }
        }

        `when`("en·ja 기기 두 대 회원이면") {
            then("두 언어 설명이 모두 있는 음식만 후보다") {
                (0 until 50).map { candidates.pick(emptySet(), setOf(LanguageCode.EN, LanguageCode.JA), emptySet(), Random(it))?.id }.toSet() shouldBe setOf(1L, 3L)
            }
        }
    }

    given("이력·북마크 제외") {
        val candidates = FoodDiscoveryCandidates(listOf(food(1), food(2), food(3)))

        `when`("일부 음식이 제외 목록에 있으면") {
            then("남은 음식만 선택된다") {
                (0 until 50).map { candidates.pick(emptySet(), ko, setOf(1L, 2L), Random(it))?.id }.toSet() shouldBe setOf(3L)
            }
        }

        `when`("모든 음식이 제외되면") {
            then("null 이다") {
                candidates.pick(emptySet(), ko, setOf(1L, 2L, 3L), Random(0)) shouldBe null
            }
        }
    }

    given("회피 집합별 메모") {
        `when`("같은 회피 집합 회원 100명과 다른 집합 회원 1명, 무회피 회원 1명을 고르면") {
            val candidates = FoodDiscoveryCandidates(listOf(food(1, IngredientCode.PORK to 50), food(2, IngredientCode.WHEAT to 90)))
            repeat(100) { candidates.pick(codes(IngredientCode.PORK), ko, emptySet(), Random(it)) }
            candidates.pick(codes(IngredientCode.EGG), ko, emptySet(), Random(0))
            candidates.pick(emptySet(), ko, emptySet(), Random(0))

            then("안전 후보 계산은 고유 집합 수만큼만 일어난다") {
                candidates.memoSize shouldBe 3
                candidates.safeFor(codes(IngredientCode.PORK)).map { it.id } shouldNotContain 1L
            }
        }
    }
})
