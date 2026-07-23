package com.kbap.infra.llm.food

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class FoodContentJsonParserTest : BehaviorSpec({
    val parser = FoodContentJsonParser

    data class Sample(val name: String = "", val value: Int = 0)

    given("FoodContentJsonParser") {
        `when`("정상 JSON 을 파싱하면") {
            then("DTO 로 역직렬화한다") {
                parser.parse<Sample>("""{"name": "불고기", "value": 3}""") shouldBe Sample("불고기", 3)
            }
        }

        `when`("```json 코드펜스로 감싼 응답이면") {
            then("코드펜스를 벗기고 파싱한다") {
                val raw = "```json\n{\"name\": \"김치\", \"value\": 7}\n```"
                parser.parse<Sample>(raw) shouldBe Sample("김치", 7)
            }
        }

        `when`("JSON 이 아닌 응답이면") {
            then("FoodContentParseException 을 던진다") {
                shouldThrow<FoodContentParseException> { parser.parse<Sample>("죄송합니다, 답할 수 없습니다") }
            }
        }
    }
})
