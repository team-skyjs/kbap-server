package com.kbap.common.infra.llm.ingredient

import com.fasterxml.jackson.databind.ObjectMapper
import com.kbap.common.domain.ingredient.model.IngredientCode
import com.kbap.common.port.llm.IngredientDecisionFood
import com.kbap.common.port.llm.IngredientPresence
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class OpenAiIngredientDeciderTest : BehaviorSpec({
    val coriander = IngredientCode.valueOf("CORIANDER")

    given("판정 요청") {
        `when`("음식과 코드 목록을 넘기면") {
            then("이름·설명·긴 설명·현재 코드와 코드별 한국어 이름을 담는다") {
                val message = OpenAiIngredientDecider.userMessage(
                    IngredientDecisionFood("쌀국수", "소고기 육수 국수", "고수를 올린다", listOf("BEEF", "WHEAT")),
                    listOf(coriander, IngredientCode.GARLIC),
                )

                message shouldContain "음식: 쌀국수"
                message shouldContain "긴 설명: 고수를 올린다"
                message shouldContain "현재 재료 코드: BEEF, WHEAT"
                message shouldContain "CORIANDER\t고수"
                message shouldContain "GARLIC\t마늘"
            }
        }
        `when`("출력 스키마를 만들면") {
            then("물은 코드만 enum 으로 받고 네 필드가 모두 필수다") {
                val schema = ObjectMapper().readTree(OpenAiIngredientDecider.schemaOf(listOf(coriander, IngredientCode.GARLIC)))
                val item = schema.path("properties").path("decisions").path("items")

                item.path("properties").path("code").path("enum").map { it.asText() } shouldBe listOf("CORIANDER", "GARLIC")
                item.path("required").map { it.asText() } shouldBe listOf("code", "contains", "probability", "reason")
                item.path("additionalProperties").asBoolean() shouldBe false
            }
        }
    }

    given("구조화 출력 해석") {
        `when`("물은 코드마다 답이 오면") {
            then("코드별 포함 여부·0~1 로 자른 확률·근거를 돌려준다") {
                val parsed = OpenAiIngredientDecider.parse(
                    """{"decisions":[{"code":"CORIANDER","contains":true,"probability":1.4,"reason":" 고명 "},{"code":"GARLIC","contains":false,"probability":0.03,"reason":"양념"}]}""",
                    listOf(coriander, IngredientCode.GARLIC),
                )

                parsed shouldBe mapOf(
                    coriander to IngredientPresence(true, 1.0, "고명"),
                    IngredientCode.GARLIC to IngredientPresence(false, 0.03, "양념"),
                )
            }
        }
        `when`("묻지 않은 코드·모르는 코드·같은 코드 중복이 섞이면") {
            then("묻지 않은 것과 모르는 것은 버리고 중복은 첫 답만 쓴다") {
                val parsed = OpenAiIngredientDecider.parse(
                    """{"decisions":[{"code":"CORIANDER","contains":true,"probability":0.5,"reason":"a"},{"code":"CORIANDER","contains":false,"probability":0,"reason":"b"},{"code":"PORK","contains":true,"probability":1,"reason":"c"},{"code":"NOPE","contains":true,"probability":1,"reason":"d"}]}""",
                    listOf(coriander),
                )

                parsed shouldBe mapOf(coriander to IngredientPresence(true, 0.5, "a"))
            }
        }
    }
})
