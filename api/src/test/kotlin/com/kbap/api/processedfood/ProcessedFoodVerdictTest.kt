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
            then("CAUTION 이고 근거로 혼입 가능 문구를 준다 — 원재료 검출로 세지 않는다") {
                val judgement = ProcessedFoodJudge.judge(crackerAllergens, crackerRaw, avoiding(IngredientCode.PEANUT))

                judgement.verdict shouldBe ProcessedFoodVerdict.CAUTION
                judgement.avoidMatches.shouldBeEmpty()
                judgement.mayContainMatches.map { it.code } shouldBe listOf(IngredientCode.PEANUT)
                judgement.mayContainMatches.single().evidence shouldBe listOf("이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다")
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
        `when`("의무 알레르기의 하위 재료만 들어 있으면 — 공부 1R") {
            then("상위 의무 코드로 올린다(치즈분말 → 우유, 바지락·가리비 → 조개류, 라드 → 돼지고기, 우지 → 쇠고기) — 젤라틴은 출처를 몰라 올리지 않는다") {
                ProcessedFoodJudge.judge(null, "옥수수, 치즈분말, 설탕", null).majorAllergens shouldBe listOf(IngredientCode.MILK)
                ProcessedFoodJudge.judge(null, "쌀, 바지락, 정제소금", null).majorAllergens shouldBe listOf(IngredientCode.CLAM)
                ProcessedFoodJudge.judge(null, "쌀, 가리비, 정제소금", null).majorAllergens shouldBe listOf(IngredientCode.CLAM)
                ProcessedFoodJudge.judge(null, "소맥분, 라드", null).majorAllergens shouldBe listOf(IngredientCode.WHEAT, IngredientCode.PORK).sortedBy { it.ordinal }
                ProcessedFoodJudge.judge(null, "쌀, 우지", null).majorAllergens shouldBe listOf(IngredientCode.BEEF)
                ProcessedFoodJudge.judge(null, "설탕, 젤라틴", null).majorAllergens.shouldBeEmpty()
            }
        }
        `when`("'함유' 뒤에 구두점 없이 혼입 문구가 붙으면 — 공부 1R") {
            then("'함유'까지는 알레르기 표시로 읽어 AVOID 이고 혼입 문구만 CAUTION 근거다") {
                listOf(
                    "밀,대두,우유 함유(땅콩 혼입 가능)",
                    "대두, 우유 함유 땅콩 혼입 가능",
                    "우유,대두,밀,돼지고기 함유이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.",
                ).forEach { allergens ->
                    val judgement = ProcessedFoodJudge.judge(allergens, "소맥분, 설탕", avoiding(IngredientCode.MILK, IngredientCode.PEANUT))
                    judgement.verdict shouldBe ProcessedFoodVerdict.AVOID
                    judgement.avoidMatches.map { it.code } shouldBe listOf(IngredientCode.MILK)
                    judgement.mayContainMatches.map { it.code } shouldBe listOf(IngredientCode.PEANUT)
                }
            }
            then("'함유된·함유될·함유 가능'은 경계가 아니라 혼입 문장 그대로다") {
                listOf(
                    "이 제품은 우유가 함유된 제품과 같은 시설에서 제조합니다",
                    "우유가 함유될 수 있습니다",
                    "우유 함유 가능",
                ).forEach { allergens ->
                    ProcessedFoodJudge.judge(allergens, "소맥분, 설탕", avoiding(IngredientCode.MILK)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                }
            }
        }
        `when`("시즈닝·조미료·양념·믹스·베이스가 원재료로 나오면 — 공부 1R") {
            then("괄호 하위 원재료 없이 단독일 때만 내용 불명이다") {
                ProcessedFoodJudge.judge(null, "감자, 바베큐시즈닝, 양념(간장, 설탕), 조미료, 핫케이크믹스, 수프베이스(사골, 정제소금)", null).unclearIngredients shouldBe
                    listOf("바베큐시즈닝", "조미료", "핫케이크믹스")
            }
        }
        `when`("원재료 없이 알레르기 표시가 혼입 문장뿐이면 — Codex P1") {
            then("혼입 근거가 있으니 CAUTION 이다(판정 없음 null 로 버리지 않는다)") {
                val judgement = ProcessedFoodJudge.judge("이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.", null, avoiding(IngredientCode.PEANUT))

                judgement.verdict shouldBe ProcessedFoodVerdict.CAUTION
                judgement.mayContainMatches.map { it.code } shouldBe listOf(IngredientCode.PEANUT)
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
