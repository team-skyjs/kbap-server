package com.kbap.api.processedfood

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class ProcessedFoodLabelTextTest : BehaviorSpec({
    given("성분표 OCR 원문(여러 면)") {
        `when`("원재료·알레르기 표시·같은 제조시설 문구가 섞여 오면") {
            then("줄을 표시란·함유 가능·원재료로 나눈다") {
                val text = ProcessedFoodLabelText.of(
                    listOf(
                        "원재료명: 소맥분(밀), 새우 10%, 설탕\n알레르기 유발물질: 밀, 새우 함유",
                        "이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다.",
                    ),
                )
                text.allergens shouldBe "알레르기 유발물질: 밀, 새우 함유"
                text.mayContain shouldBe "이 제품은 땅콩을 사용한 제품과 같은 제조시설에서 제조하고 있습니다."
                text.rawMaterials shouldBe "원재료명: 소맥분(밀), 새우 10%, 설탕"
            }
        }
        `when`("표시란과 같은 제조시설 문구가 한 줄에 붙어 오면 — 공부 B") {
            then("문장 단위로 나눠 표시란 재료는 표시란에, 함유 가능 문구만 함유 가능으로 간다") {
                val text = ProcessedFoodLabelText.of(listOf("알레르기 유발물질: 밀, 대두, 우유, 쇠고기 함유. 이 제품은 메밀을 사용한 제품과 같은 제조시설에서 제조합니다."))
                text.allergens shouldBe "알레르기 유발물질: 밀, 대두, 우유, 쇠고기 함유"
                text.mayContain shouldBe "이 제품은 메밀을 사용한 제품과 같은 제조시설에서 제조합니다."
            }
        }
        `when`("같은 제조시설 문구가 OCR 줄바꿈으로 쪼개져 오면 — 공부 C·Codex P2") {
            then("한 문장으로 이어 함유 가능으로 보고, 원재료로 새지 않는다") {
                val text = ProcessedFoodLabelText.of(listOf("원재료명: 감자, 식물성유지\n이 제품은 메밀을 사용한 제품과 같은\n제조시설에서 제조하고 있습니다."))
                text.mayContain shouldBe "이 제품은 메밀을 사용한 제품과 같은 제조시설에서 제조하고 있습니다."
                text.rawMaterials shouldBe "원재료명: 감자, 식물성유지"
            }
        }
        `when`("여러 면에 같은 줄이 반복되거나 공백·전각만 다르면") {
            then("한 번만 남기고 원문은 줄 순서대로 이어 붙인다") {
                val text = ProcessedFoodLabelText.of(listOf("원재료명: 감자,  식물성유지\n\n", "원재료명: 감자, 식물성유지", "ＭＳＧ 무첨가"))
                text.rawText shouldBe "원재료명: 감자, 식물성유지\nMSG 무첨가"
                text.allergens shouldBe null
                text.mayContain shouldBe null
            }
        }
        `when`("'…함유'로 끝나는 줄이나 혼입·포함될 수 문구면") {
            then("앞은 표시란, 뒤는 함유 가능으로 본다") {
                val text = ProcessedFoodLabelText.of(listOf("우유, 대두 함유.", "메밀이 혼입될 수 있습니다", "땅콩이 포함될 수 있음", "동일한 제조공정에서 호두를 사용"))
                text.allergens shouldBe "우유, 대두 함유"
                text.mayContain shouldBe "메밀이 혼입될 수 있습니다\n땅콩이 포함될 수 있음\n동일한 제조공정에서 호두를 사용"
                text.rawMaterials shouldBe ""
            }
        }
    }
})
