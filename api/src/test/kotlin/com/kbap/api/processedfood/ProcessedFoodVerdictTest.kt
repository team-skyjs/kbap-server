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
        `when`("향료만 들어 있고 회피 재료와 무관하면 — R-116") {
            then("향료는 내용 불명으로 보지 않아 SAFE 다") {
                val judgement = ProcessedFoodJudge.judge(bananaMilkAllergens, bananaMilkRaw, avoiding(IngredientCode.SHRIMP))

                judgement.verdict shouldBe ProcessedFoodVerdict.SAFE
                judgement.unclearIngredients.shouldBeEmpty()
            }
        }
        `when`("혼합제제가 들어 있고 회피 재료와 무관하면") {
            then("SAFE_WITH_NOTE 이고 내용 불명 원재료를 준다") {
                val judgement = ProcessedFoodJudge.judge(null, "소맥분(밀), 설탕, 혼합제제(유화제), 합성향료", avoiding(IngredientCode.SHRIMP))

                judgement.verdict shouldBe ProcessedFoodVerdict.SAFE_WITH_NOTE
                judgement.unclearIngredients shouldBe listOf("혼합제제")
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
            then("서술형('함유하고·함유되어') 뒤는 경계라 앞의 알레르기 표시는 AVOID 다 — 공부 2R") {
                listOf(
                    "우유를 함유하고 있으며 땅콩 혼입 가능",
                    "우유가 함유되어 있으며, 같은 제조시설에서 땅콩을 사용한 제품을 제조합니다",
                ).forEach { allergens ->
                    val judgement = ProcessedFoodJudge.judge(allergens, "소맥분, 설탕", avoiding(IngredientCode.MILK, IngredientCode.PEANUT))
                    judgement.verdict shouldBe ProcessedFoodVerdict.AVOID
                    judgement.avoidMatches.map { it.code } shouldBe listOf(IngredientCode.MILK)
                }
            }
            then("'함유한 제품과 같은 제조시설'도 경계가 아니라 혼입 문장 그대로다 — Codex P2") {
                ProcessedFoodJudge.judge("우유를 함유한 제품과 같은 제조시설에서 제조합니다", "소맥분, 설탕", avoiding(IngredientCode.MILK)).verdict shouldBe ProcessedFoodVerdict.CAUTION
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
                ProcessedFoodJudge.judge(null, "감자, 바베큐시즈닝, 양념(간장, 설탕), 조미료, 믹스, 수프베이스(사골, 정제소금)", null).unclearIngredients shouldBe
                    listOf("바베큐시즈닝", "조미료", "믹스")
            }
            then("베이스·믹스는 항목 전체가 그 낱말일 때만이다 — 토마토베이스·견과믹스처럼 원료가 드러나면 아니다(공부 2R)") {
                ProcessedFoodJudge.judge(null, "토마토베이스, 견과믹스, 베이스, 핫케이크믹스", null).unclearIngredients shouldBe listOf("베이스")
            }
        }
        `when`("원재료 없이 알레르기 표시가 혼입 문장뿐이면 — Codex P1") {
            then("혼입 근거가 있으니 CAUTION 이다(판정 없음 null 로 버리지 않는다)") {
                val judgement = ProcessedFoodJudge.judge("이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.", null, avoiding(IngredientCode.PEANUT))

                judgement.verdict shouldBe ProcessedFoodVerdict.CAUTION
                judgement.mayContainMatches.map { it.code } shouldBe listOf(IngredientCode.PEANUT)
            }
        }
        `when`("원재료를 모르고 알레르기 표시만 있으면 — 공부 3R") {
            then("회피 재료가 의무 알레르기 가족 밖에 하나라도 있으면 SAFE 를 내지 않는다(null)") {
                ProcessedFoodJudge.judge("밀, 대두 함유", "알수없음", avoiding(IngredientCode.GARLIC, IngredientCode.GELATIN, IngredientCode.MEAT)).verdict shouldBe null
            }
            then("회피 재료가 모두 의무 알레르기 가족 안이면 알레르기 표시로 판정한다") {
                ProcessedFoodJudge.judge("밀, 대두 함유", "알수없음", avoiding(IngredientCode.MILK)).verdict shouldBe ProcessedFoodVerdict.SAFE
            }
            then("알레르기 표시에 회피 재료가 보이면 가족 밖 회피가 섞여도 AVOID 다(확인된 검출은 버리지 않는다)") {
                ProcessedFoodJudge.judge("우유 함유", "알수없음", avoiding(IngredientCode.MILK, IngredientCode.GARLIC)).verdict shouldBe ProcessedFoodVerdict.AVOID
            }
        }
        `when`("원재료 칸이 자리표시값 변형이면 — 공부 3R") {
            then("'알 수 없음'을 계란으로 읽지 않고, 변형도 모두 근거 없음으로 본다") {
                ProcessedFoodJudge.judge(null, "알 수 없음", avoiding(IngredientCode.MILK)).verdict shouldBe null
                ProcessedFoodJudge.judge(null, "알 수 없음", null).majorAllergens.shouldBeEmpty()
                listOf("해당없음", "없음", "-", "(알수없음)", "알수없음.", "미기재", "표시없음", "N/A").forEach { raw ->
                    ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.MILK)).verdict shouldBe null
                }
            }
        }
        `when`("알레르기 칸 자리표시값에 구두점이 붙어 있으면 — 공부 4R") {
            then("'(알수없음)'·'알수없음.'도 표시 없음으로 보아 원재료까지 모르면 판정하지 않는다") {
                listOf("(알수없음)", "알수없음.").forEach { allergens ->
                    ProcessedFoodJudge.judge(allergens, "알수없음", avoiding(IngredientCode.MILK)).verdict shouldBe null
                }
            }
        }
        `when`("마침표 뒤에 띄어쓰기 없이 혼입 문장이 붙으면 — Codex P1") {
            then("마침표를 문장 경계로 보아 앞 원재료는 AVOID 근거이고, 소수점은 자르지 않는다") {
                val raw = "우유, 설탕.이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다"
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.MILK)).verdict shouldBe ProcessedFoodVerdict.AVOID
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                ProcessedFoodJudge.judge(null, "원유 85.5%, 설탕 1.2%", avoiding(IngredientCode.MILK)).avoidMatches.single().evidence shouldContain "원유"
            }
        }
        `when`("확정 원재료와 혼입 문구가 한 문장에 쉼표로 붙어 있으면 — Codex P1") {
            then("혼입 표지가 있는 쉼표 조각만 혼입이고 앞 조각은 확정 원재료다(애매하면 AVOID 쪽)") {
                val raw = "소맥분, 설탕, 우유 함유 가능"
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.WHEAT)).verdict shouldBe ProcessedFoodVerdict.AVOID
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.MILK)).verdict shouldBe ProcessedFoodVerdict.CAUTION
            }
            then("같은 제조시설 문장은 쉼표로 나누지 않고 통째로 혼입이다") {
                ProcessedFoodJudge.judge("이 제품은 땅콩, 대두를 사용한 제품과 같은 제조시설에서 제조하고 있습니다.", "소맥분, 설탕", avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
            }
        }
        `when`("같은 라인·설비·장소에서 생산한다는 문장이면 — 공부 #457 참고") {
            then("혼입 문장으로 보아 CAUTION 이고 문장 전체가 혼입이다") {
                listOf(
                    "이 제품은 땅콩, 대두를 사용한 제품과 같은 라인에서 생산합니다.",
                    "땅콩을 사용한 제품과 같은 생산 설비에서 제조합니다.",
                    "땅콩을 취급하는 같은 장소에서 생산합니다.",
                ).forEach { allergens ->
                    val judgement = ProcessedFoodJudge.judge(allergens, "소맥분, 설탕", avoiding(IngredientCode.PEANUT, IngredientCode.SOY))
                    judgement.verdict shouldBe ProcessedFoodVerdict.CAUTION
                    judgement.avoidMatches.shouldBeEmpty()
                }
            }
        }
        `when`("원재료 칸에서 접두 없는 '제품은'이 쉼표 없이 시설 경고를 열면 — Codex P1(#459 vK9DG)") {
            then("앞 원재료는 확정, 경고만 혼입이다") {
                val raw = "소맥분(밀) 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다"
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.WHEAT)).verdict shouldBe ProcessedFoodVerdict.AVOID
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
            }
        }
        `when`("원재료 칸에서 여는 말이 앞 낱말에 공백 없이 붙어 시설 경고를 열면 — Codex P1(#459, 세 번째 같은 계열)") {
            then("여는 말이 시작하는 문자 위치에서 잘라 앞은 확정, 뒤만 혼입이다") {
                val raw = "소맥분(밀)이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다"
                ProcessedFoodLabelText.splitMayContain(raw, ingredientField = true) shouldBe
                    ("소맥분(밀)" to "이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다")
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.WHEAT)).verdict shouldBe ProcessedFoodVerdict.AVOID
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
            }
            then("공백·쉼표·접두 변형이 한 규칙으로 같은 결과다") {
                listOf(
                    "소맥분(밀), 이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다",
                    "소맥분(밀),이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다",
                    "소맥분(밀) 본 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다",
                    "소맥분(밀)본품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다",
                    "소맥분(밀) 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다",
                ).forEach { raw ->
                    ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.WHEAT)).verdict shouldBe ProcessedFoodVerdict.AVOID
                    ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                }
            }
        }
        `when`("'같은 제조설비'·'같은 제조라인'·'같은 제조장소' 문장이면 — Codex P2(#459 vG-R3)") {
            then("알레르기 칸이든 원재료 칸이든 혼입으로 보아 CAUTION 이다") {
                listOf("같은 제조설비", "같은 제조라인", "동일한 제조 라인", "같은 제조장소").forEach { place ->
                    val warning = "땅콩을 사용한 제품과 ${place}에서 제조합니다"
                    ProcessedFoodJudge.judge(warning, "소맥분, 설탕", avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                    ProcessedFoodJudge.judge(null, "소맥분, 설탕, $warning", avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                }
            }
        }
        `when`("원재료 칸에서 시설·라인 문구가 원재료 목록과 한 문장에 붙으면 — Codex P1·공부 #459") {
            then("표지가 든 쉼표 조각만 혼입이고 앞 원재료는 확정이다") {
                val glued = "소맥분(밀), 설탕, 땅콩과 같은 라인에서 생산"
                ProcessedFoodJudge.judge(null, glued, avoiding(IngredientCode.WHEAT)).verdict shouldBe ProcessedFoodVerdict.AVOID
                ProcessedFoodJudge.judge(null, glued, avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                ProcessedFoodJudge.judge(null, "원재료명: 소맥분, 설탕, 본 제품은 땅콩을 사용한 제품과 같은 생산라인에서 생산합니다", avoiding(IngredientCode.WHEAT)).verdict shouldBe ProcessedFoodVerdict.AVOID
            }
            then("'이 제품은 …를 사용한 제품과 같은 제조 시설'처럼 여는 말로 시작하는 원재료 칸 문장은 통째로 혼입이다(로컬 실데이터 형태)") {
                val raw = "쌀, 콩나물, 바지락살, 정제소금. 이 제품은 난류, 우유, 메밀, 땅콩, 대두, 밀, 고등어, 게, 돼지고기, 복숭아, 토마토, 새우를 사용한 제품과 같은 제조 시설에서 제조하고 있습니다."
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.MILK)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.EGG)).verdict shouldBe ProcessedFoodVerdict.CAUTION
            }
            then("여는 말이 바로 앞 원재료와 한 조각에 붙어도 여는 말이 새 문장을 시작한다 — 공부 #459") {
                mapOf(
                    "소맥분, 설탕, 우유 본 제품은 땅콩을 사용한 제품과 같은 시설에서 제조합니다" to IngredientCode.MILK,
                    "소맥분(밀) 본 제품은 땅콩을 사용한 제품과 같은 시설에서 제조합니다" to IngredientCode.WHEAT,
                    "소맥분, 설탕, 우유 해당 제품은 땅콩을 사용한 제품과 같은 라인에서 생산합니다" to IngredientCode.MILK,
                    "소맥분, 설탕, 우유 당 제품은 땅콩을 사용한 제품과 같은 시설에서 제조합니다" to IngredientCode.MILK,
                    "소맥분, 설탕, 우유 본품은 땅콩을 사용한 제품과 같은 시설에서 제조합니다" to IngredientCode.MILK,
                ).forEach { (raw, definite) ->
                    ProcessedFoodJudge.judge(null, raw, avoiding(definite)).verdict shouldBe ProcessedFoodVerdict.AVOID
                    ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                }
            }
            then("쉼표 뒤 띄어쓰기 없이 여는 말이 붙어도 앞 원재료는 확정이다 — Codex P1(#458)") {
                val raw = "소맥분(밀),설탕,이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조합니다"
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.WHEAT)).verdict shouldBe ProcessedFoodVerdict.AVOID
                ProcessedFoodJudge.judge(null, raw, avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
            }
            then("알레르기 칸에서 수식어 하나가 목록 전체에 걸리면 목록 전체가 혼입이다 — Codex P1(#457)") {
                ProcessedFoodJudge.judge("땅콩, 대두 함유 가능", "소맥분, 설탕", avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
                ProcessedFoodJudge.judge(null, "소맥분, 설탕, 우유 함유 가능", avoiding(IngredientCode.WHEAT)).verdict shouldBe ProcessedFoodVerdict.AVOID
            }
            then("알레르기 칸의 시설 문장은 통째로 혼입이다") {
                ProcessedFoodJudge.judge("이 제품은 땅콩, 대두를 사용한 제품과 같은 시설에서 제조합니다", "소맥분, 설탕", avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
            }
        }
        `when`("확정 근거 없이 혼입 문장만 있고 내 회피와 겹치지 않으면 — Codex P1") {
            then("밀이 없다는 근거가 아니므로 SAFE 가 아니라 null 이다") {
                ProcessedFoodJudge.judge("땅콩 혼입 가능", null, avoiding(IngredientCode.WHEAT)).verdict shouldBe null
                ProcessedFoodJudge.judge("땅콩 혼입 가능", null, avoiding(IngredientCode.PEANUT)).verdict shouldBe ProcessedFoodVerdict.CAUTION
            }
        }
        `when`("원재료와 알레르기 표시가 둘 다 없으면") {
            then("회원이어도 판정하지 않는다(null) — 근거 없는 SAFE 를 내지 않는다") {
                listOf(null to null, "알수없음" to " ", null to "알수없음", "알수없음" to "정보없음", "미표시" to "모름").forEach { (allergens, raw) ->
                    ProcessedFoodJudge.judge(allergens, raw, avoiding(IngredientCode.MILK)).verdict shouldBe null
                }
            }
        }
    }
})
