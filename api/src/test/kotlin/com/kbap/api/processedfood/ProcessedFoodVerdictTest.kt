package com.kbap.api.processedfood

import com.kbap.common.domain.ingredient.model.Avoidance
import com.kbap.common.domain.ingredient.model.IngredientCode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

class ProcessedFoodVerdictTest : BehaviorSpec({
    fun avoiding(vararg codes: IngredientCode) = Avoidance(codes.toSet())

    val bananaMilkAllergens = "우유 함유"
    val bananaMilkRaw = "원유(국산) 85%, 정제수, 설탕, 바나나농축과즙(이스라엘산), 합성향료(바나나향), 카로틴"
    val crackerAllergens = "밀, 대두 함유. 이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다."
    val crackerRaw = "소맥분(밀:미국산), 대두유, 설탕, 정제소금"

    given("가공식품 판정(KB-866)") {
        `when`("바나나맛우유를 우유 회피 회원이 보면") {
            then("AVOID 이고 걸린 회피 재료와 근거를 준다") {
                val judgement = ProcessedFoodJudge.judge(bananaMilkAllergens, bananaMilkRaw, avoiding(IngredientCode.MILK))

                judgement.verdict shouldBe ProcessedFoodVerdict.AVOID
                judgement.avoidMatches.map { it.code } shouldBe listOf(IngredientCode.MILK)
                judgement.avoidMatches.single().evidence shouldContain "우유"
                judgement.mayContainMatches.shouldBeEmpty()
            }
        }
        `when`("땅콩이 혼입 가능 표기에만 있고 땅콩 회피 회원이 보면") {
            then("CAUTION 이고 혼입 가능 근거를 준다 — 원재료 검출로 세지 않는다") {
                val judgement = ProcessedFoodJudge.judge(crackerAllergens, crackerRaw, avoiding(IngredientCode.PEANUT))

                judgement.verdict shouldBe ProcessedFoodVerdict.CAUTION
                judgement.avoidMatches.shouldBeEmpty()
                judgement.mayContainMatches.map { it.code } shouldBe listOf(IngredientCode.PEANUT)
                judgement.mayContainMatches.single().evidence shouldBe listOf("땅콩")
            }
        }
        `when`("향료가 들어 있고 회피 재료와 무관하면") {
            then("SAFE_WITH_NOTE 이고 내용 불명 원재료를 준다") {
                val judgement = ProcessedFoodJudge.judge(bananaMilkAllergens, bananaMilkRaw, avoiding(IngredientCode.SHRIMP))

                judgement.verdict shouldBe ProcessedFoodVerdict.SAFE_WITH_NOTE
                judgement.unclearIngredients shouldBe listOf("합성향료")
            }
        }
        `when`("회피 재료도 내용 불명 원재료도 없으면") {
            then("SAFE 다 — 81종 밖 원재료(설탕·정제소금·미국산)는 판정에 넣지 않는다") {
                val judgement = ProcessedFoodJudge.judge("밀 함유", crackerRaw, avoiding(IngredientCode.SHRIMP))

                judgement.verdict shouldBe ProcessedFoodVerdict.SAFE
                judgement.unclearIngredients.shouldBeEmpty()
            }
        }
        `when`("회피 재료가 원재료에도 있고 다른 회피 재료가 혼입 가능에도 있으면") {
            then("AVOID 가 이기고 혼입 가능 재료는 따로 남는다") {
                val judgement = ProcessedFoodJudge.judge(crackerAllergens, crackerRaw, avoiding(IngredientCode.WHEAT, IngredientCode.PEANUT))

                judgement.verdict shouldBe ProcessedFoodVerdict.AVOID
                judgement.avoidMatches.map { it.code } shouldBe listOf(IngredientCode.WHEAT)
                judgement.mayContainMatches.map { it.code } shouldBe listOf(IngredientCode.PEANUT)
            }
        }
        `when`("고른 회피 재료가 함의하는 재료가 들어 있으면") {
            then("고른 코드로 AVOID 에 묶인다(우유 회피 → 버터)") {
                val judgement = ProcessedFoodJudge.judge(null, "소맥분, 버터, 설탕", avoiding(IngredientCode.MILK))

                judgement.verdict shouldBe ProcessedFoodVerdict.AVOID
                judgement.avoidMatches.single().code shouldBe IngredientCode.MILK
                judgement.avoidMatches.single().evidence shouldContain "버터"
            }
        }
        `when`("비회원이거나 회피 재료가 0개이면") {
            then("판정은 null 이고 한국 표시 의무 알레르기 중 들어 있는 코드만 준다(혼입 가능은 빼고)") {
                listOf(null, Avoidance.NONE).forEach { avoidance ->
                    val judgement = ProcessedFoodJudge.judge(crackerAllergens, crackerRaw, avoidance)

                    judgement.verdict shouldBe null
                    judgement.majorAllergens shouldBe listOf(IngredientCode.SOY, IngredientCode.WHEAT).sortedBy { it.ordinal }
                    judgement.majorAllergens shouldNotContain IngredientCode.PEANUT
                }
            }
        }
        `when`("원재료와 알레르기 표시가 둘 다 없으면") {
            then("회원이어도 판정하지 않는다(null) — 근거 없는 SAFE 를 내지 않는다") {
                listOf(null to null, "알수없음" to " ").forEach { (allergens, raw) ->
                    ProcessedFoodJudge.judge(allergens, raw, avoiding(IngredientCode.MILK)).verdict shouldBe null
                }
            }
        }
    }
})
